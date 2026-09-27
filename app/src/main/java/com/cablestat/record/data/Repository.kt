package com.cablestat.record.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.room.withTransaction
import com.cablestat.record.data.db.AppDatabase
import com.cablestat.record.data.db.ColorEntity
import com.cablestat.record.data.db.Kind
import com.cablestat.record.data.db.ProjectEntity
import com.cablestat.record.data.db.RecordEntity
import com.cablestat.record.data.db.SpecEntity
import java.util.UUID

/** 同一 分类×规格×颜色 的累加小计 */
data class ComboAgg(
    val kind: String,
    val spec: String,
    val color: String,
    val totalMm: Long,
    val count: Long
)

/** 同一规格的分组汇总（规格组内再按颜色细分） */
data class SpecAgg(
    val kind: String,
    val spec: String,
    val totalMm: Long,
    val count: Long,
    val combos: List<ComboAgg>
)

/** 项目统计卡片数据 */
data class ProjectStats(
    val totalMm: Long,
    val wireMm: Long,
    val busbarMm: Long,
    val recordCount: Long
)

/** 项目卡片（含实时统计） */
data class ProjectCard(
    val project: ProjectEntity,
    val stats: ProjectStats
)

/** 多行长度输入的解析结果：合法长度列表 + 无法识别片段 */
data class LengthParse(
    val ok: List<Long>,
    val invalid: List<String>
)

/** 多芯电缆规格解析结果：主芯数×单芯截面 + 附加芯数（如 3×2.5+1×1.5 → 主3芯2.5 + 附加1芯） */
data class WireCoreInfo(
    val mainCores: Int,
    val mainArea: Double,
    val extraCores: Int
)

class Repository(private val db: AppDatabase) {

    // ---------- 种子数据（首次启动写入候选池） ----------

    private val defaultWireSpecs = listOf(
        "1.0" to 1.0, "1.5" to 1.5, "2.5" to 2.5, "4.0" to 4.0, "6.0" to 6.0,
        "10.0" to 10.0, "16.0" to 16.0, "25.0" to 25.0, "35.0" to 35.0,
        "50.0" to 50.0, "70.0" to 70.0, "95.0" to 95.0, "120.0" to 120.0,
        "150.0" to 150.0, "185.0" to 185.0, "240.0" to 240.0
    )

    /** 多芯电缆常用规格（芯数×单芯截面，含 3+1/3+2/4+1 等带 N/PE 的规格） */
    private val defaultWireMultiSpecs = listOf(
        "2×1.5", "2×2.5", "2×4", "2×6", "2×10",
        "3×1.5", "3×2.5", "3×4", "3×6", "3×10", "3×16", "3×25", "3×35", "3×50", "3×70", "3×95", "3×120",
        "4×1.5", "4×2.5", "4×4", "4×6", "4×10", "4×16", "4×25", "4×35", "4×50", "4×70", "4×95",
        "5×1.5", "5×2.5", "5×4", "5×6", "5×10", "5×16", "5×25",
        "3×2.5+1×1.5", "3×4+1×2.5", "3×6+1×4", "3×10+1×6", "3×16+1×10", "3×25+1×16",
        "3×35+1×16", "3×50+1×25", "3×70+1×35", "3×95+1×50",
        "3×4+2×2.5", "3×6+2×4", "3×10+2×6", "3×16+2×10", "3×25+2×16", "3×35+2×16",
        "3×50+2×25", "3×70+2×35",
        "4×4+1×2.5", "4×6+1×4", "4×10+1×6", "4×16+1×10", "4×25+1×16", "4×35+1×16",
        "4×50+1×25", "4×70+1×35", "4×95+1×50"
    )

    private val defaultColors = listOf("红", "黄", "绿", "蓝", "黄绿", "黑", "棕", "白", "灰")

    private val defaultBusbarSpecs = listOf(
        "20×3", "20×4", "25×3", "30×3", "30×4", "30×5", "40×4", "40×5", "40×6",
        "50×5", "50×6", "50×8", "60×6", "60×8", "60×10", "80×6", "80×8", "80×10",
        "100×8", "100×10", "120×8", "120×10", "125×10"
    )

    /**
     * 幂等补齐内置候选池：只插入缺失项，已有的保留。
     * 这样新增内置规格（如多芯电缆）在升级后也能补进老用户的候选池，且重复启动不会产生重复。
     */
    suspend fun seedIfEmpty() {
        val wireItems = defaultWireSpecs + defaultWireMultiSpecs.map { it to wireKey(it) }
        seedSpecs(Kind.WIRE, wireItems)
        seedSpecs(Kind.BUSBAR, defaultBusbarSpecs.map { it to busbarKey(it) })
        val existingColors = db.colorDao().listAll().map { it.label }.toSet()
        defaultColors.forEachIndexed { i, c ->
            if (c !in existingColors) {
                db.colorDao().insert(ColorEntity(UUID.randomUUID().toString(), c, i))
            }
        }
    }

    private suspend fun seedSpecs(kind: String, items: List<Pair<String, Double>>) {
        val existing = db.specDao().listByKind(kind).map { it.label }.toSet()
        items.forEach { (label, key) ->
            if (label !in existing) {
                db.specDao().insert(SpecEntity(UUID.randomUUID().toString(), kind, label, key))
            }
        }
    }

    // ---------- 工具：规格解析 ----------

    companion object {
        /** 铜排规格 "40×5" → 截面积 200，不可解析给极大值排末尾 */
        fun busbarKey(label: String): Double {
            val parts = label.split('×', 'x', 'X', '*')
            if (parts.size == 2) {
                val a = parts[0].trim().toDoubleOrNull()
                val b = parts[1].trim().toDoubleOrNull()
                if (a != null && b != null) return a * b
            }
            return Double.MAX_VALUE
        }

        // ---------- 线缆规格：单芯 / 多芯 ----------

        /** 多芯排序基准：单芯截面最大 240，远小于该基准，保证多芯排在单芯之后 */
        private const val MULTI_WIRE_ORDER_BASE = 100_000.0

        /** 单芯分组键 */
        const val WIRE_GROUP_SINGLE = "single"

        private const val NUM_PAT = "\\d+(?:\\.\\d+)?"
        private const val CORE_PAT = "$NUM_PAT\\s*[xX×*]\\s*$NUM_PAT"
        private val MULTI_WIRE_REGEX = Regex("^$CORE_PAT(?:\\s*\\+\\s*$CORE_PAT)*$")
        private val CORE_SPLIT = Regex("\\s*\\+\\s*")
        private val CORE_PARTS = Regex("^($NUM_PAT)\\s*[xX×*]\\s*($NUM_PAT)$")
        private val GROUP_KEY_REGEX = Regex("^core(\\d+)(?:plus(\\d+))?$")

        /** 线缆规格 "1.5" → 1.5；多芯 "3×2.5" 按 芯数→截面 排序；不可解析给极大值排末尾 */
        fun wireKey(label: String): Double {
            val t = label.trim()
            t.toDoubleOrNull()?.let { return it }
            val info = parseWireCoreInfo(t) ?: return Double.MAX_VALUE
            val cores = info.mainCores + info.extraCores
            return MULTI_WIRE_ORDER_BASE + cores * 1000.0 + info.mainArea
        }

        /** 解析多芯电缆规格：如 "3×2.5"、"4×4+1×2.5"；非多芯返回 null */
        fun parseWireCoreInfo(spec: String): WireCoreInfo? {
            val t = spec.trim()
            if (!MULTI_WIRE_REGEX.matches(t)) return null
            val parts = CORE_SPLIT.split(t)
            val first = CORE_PARTS.matchEntire(parts[0]) ?: return null
            val mainCores = first.groupValues[1].toDoubleOrNull()?.toInt() ?: return null
            val mainArea = first.groupValues[2].toDoubleOrNull() ?: return null
            var extra = 0
            for (i in 1 until parts.size) {
                val m = CORE_PARTS.matchEntire(parts[i]) ?: return null
                extra += m.groupValues[1].toDoubleOrNull()?.toInt() ?: return null
            }
            return WireCoreInfo(mainCores, mainArea, extra)
        }

        /** 是否为多芯电缆规格（芯数×截面，可带 +N×S） */
        fun isMultiCoreSpec(spec: String): Boolean = parseWireCoreInfo(spec) != null

        /** 是否可作为线缆规格：单芯数字或多芯样式，且数值大于 0 */
        fun isValidWireSpec(spec: String): Boolean {
            val t = spec.trim()
            val v = t.toDoubleOrNull()
            if (v != null) return v > 0
            val info = parseWireCoreInfo(t) ?: return false
            return info.mainCores > 0 && info.mainArea > 0 && info.extraCores >= 0
        }

        /** 线缆规格归一：去空白，把 x、X、星号分隔符统一为 ×，单芯尾零归一（4.0 → 4） */
        fun normalizeWireSpec(label: String): String {
            val t = label.trim().replace(Regex("\\s+"), "")
            if (t.isEmpty()) return t
            if (parseWireCoreInfo(t) != null) {
                return t.replace('x', '×').replace('X', '×').replace('*', '×')
            }
            val v = t.toDoubleOrNull()
            if (v != null && !v.isInfinite() && v == Math.floor(v)) return v.toLong().toString()
            return t
        }

        /** 线缆分组键：单芯 = "single"；"3×2.5" = "core3"；"3×2.5+1×1.5" = "core3plus1" */
        fun wireGroupKey(spec: String): String {
            val info = parseWireCoreInfo(spec) ?: return WIRE_GROUP_SINGLE
            return if (info.extraCores == 0) "core${info.mainCores}" else "core${info.mainCores}plus${info.extraCores}"
        }

        /** 线缆分组显示名 */
        fun wireGroupTitle(key: String): String {
            if (key == WIRE_GROUP_SINGLE) return "单芯"
            val m = GROUP_KEY_REGEX.matchEntire(key) ?: return "其他"
            val main = m.groupValues[1]
            val extra = m.groupValues[2]
            return if (extra.isEmpty()) "${main}芯" else "${main}+${extra}芯"
        }

        /** 线缆分组排序：单芯 → 芯数由少到多，N芯 排在 N+P 之前 */
        fun wireGroupRank(key: String): Double {
            if (key == WIRE_GROUP_SINGLE) return 0.0
            val m = GROUP_KEY_REGEX.matchEntire(key) ?: return Double.MAX_VALUE
            val main = m.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
            val extra = m.groupValues[2].toDoubleOrNull() ?: 0.0
            return main * 100 + if (extra > 0) 50 + extra else 0.0
        }

        /** 给定规格标签列表，返回存在分组的 (键, 显示名)，按芯数排序 */
        fun wireGroupOrder(labels: List<String>): List<Pair<String, String>> =
            labels.map { wireGroupKey(it) }.distinct()
                .sortedBy { wireGroupRank(it) }
                .map { it to wireGroupTitle(it) }

        /** 线缆规格展示："1.5" → "1.5mm²"，"3×2.5" → "3×2.5mm²" */
        fun wireSpecLabel(spec: String): String {
            val t = spec.trim()
            val v = t.toDoubleOrNull()
            if (v != null) {
                val n = if (!v.isInfinite() && v == Math.floor(v)) v.toLong().toString() else v.toString()
                return "${n}mm²"
            }
            return "${t}mm²"
        }

        /** 判断规格是否为铜排样式 */
        fun isBusbarLike(spec: String): Boolean =
            spec.any { it == '×' || it == 'x' || it == 'X' || it == '*' }

        /**
         * 解析长度输入：每行一根，兼容空格/逗号/分号分隔；
         * 同一长度多根可用 "85×5"（85 五根，× 也可用 *），自动展开为明细。
         * 片段必须为纯数字或 "数字×数字"，长度与根数均需大于 0，根数上限 [MAX_MULTIPLY]。
         * 返回值按当前默认长度单位的原始数值（单位换算由调用方完成）。
         */
        fun parseLengths(text: String): LengthParse {
            val ok = mutableListOf<Long>()
            val invalid = mutableListOf<String>()
            // 先把乘法片段内部空白归一（"32 × 3" → "32×3"），避免被分隔符切散
            val normalized = MUL_REGEX.replace(text) { m ->
                "${m.groupValues[1]}×${m.groupValues[2]}"
            }
            normalized.split(SEPARATORS)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { t ->
                    if (!parseToken(t, ok)) invalid.add(t)
                }
            return LengthParse(ok, invalid)
        }

        /** 同长度多根展开上限，防止误输入导致展开过大 */
        private const val MAX_MULTIPLY = 500

        /** "850" 或 "850×5" → 追加到 out；成功返回 true */
        private fun parseToken(t: String, out: MutableList<Long>): Boolean {
            MUL_REGEX.matchEntire(t)?.let { m ->
                val len = m.groupValues[1].toLong()
                val count = m.groupValues[2].toLong()
                if (len <= 0 || count <= 0 || count > MAX_MULTIPLY) return false
                repeat(count.toInt()) { out.add(len) }
                return true
            }
            val v = t.toLongOrNull()
            if (v != null && v > 0) {
                out.add(v)
                return true
            }
            return false
        }

        private val SEPARATORS = Regex("[,\\n，;；\\s]+")
        private val MUL_REGEX = Regex("(\\d+)\\s*[xX×*]\\s*(\\d+)")
    }

    // ---------- 项目 ----------

    fun observeProjects(): LiveData<List<ProjectEntity>> = db.projectDao().observeAll()
    fun observeProject(id: String): LiveData<ProjectEntity?> = db.projectDao().observeById(id)

    /** 项目卡片 + 实时统计（记录变化也会驱动刷新） */
    fun observeProjectCards(): LiveData<List<ProjectCard>> {
        val result = MediatorLiveData<List<ProjectCard>>()
        val projectsLive = db.projectDao().observeAll()
        val recordsLive = db.recordDao().observeAllLive()
        fun refresh() {
            val projects = projectsLive.value
            val records = recordsLive.value
            if (projects == null || records == null) return
            result.value = projects.map { p ->
                val rs = records.filter { it.projectId == p.id }
                val wire = rs.filter { it.kind == Kind.WIRE }.sumOf { it.lengthMm }
                val bus = rs.filter { it.kind == Kind.BUSBAR }.sumOf { it.lengthMm }
                ProjectCard(p, ProjectStats(wire + bus, wire, bus, rs.size.toLong()))
            }
        }
        result.addSource(projectsLive) { refresh() }
        result.addSource(recordsLive) { refresh() }
        refresh()
        return result
    }

    suspend fun addProject(name: String, remark: String): ProjectEntity {
        val now = System.currentTimeMillis()
        val p = ProjectEntity(UUID.randomUUID().toString(), name.trim(), remark.trim(), now, now)
        db.projectDao().insert(p)
        return p
    }

    suspend fun renameProject(p: ProjectEntity, name: String, remark: String) {
        db.projectDao().insert(
            p.copy(name = name.trim(), remark = remark.trim(), updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun deleteProject(id: String) {
        db.projectDao().deleteRecordsByProject(id)
        db.projectDao().deleteById(id)
    }

    /** 项目统计（从记录累加） */
    suspend fun projectStats(projectId: String): ProjectStats {
        val records = db.recordDao().allByProject(projectId)
        val wireMm = records.filter { it.kind == Kind.WIRE }.sumOf { it.lengthMm }
        val busMm = records.filter { it.kind == Kind.BUSBAR }.sumOf { it.lengthMm }
        return ProjectStats(wireMm + busMm, wireMm, busMm, records.size.toLong())
    }

    // ---------- 记录 ----------

    suspend fun addRecord(projectId: String, kind: String, spec: String, color: String, lengthMm: Long, note: String) {
        db.recordDao().insert(
            newRecord(projectId, kind, spec, color, lengthMm, note)
        )
    }

    /** 批量记一笔：一次输入的每一行长度各记一条明细，同规格×颜色自动汇总 */
    suspend fun addRecords(projectId: String, kind: String, spec: String, color: String, lengths: List<Long>, note: String) {
        db.withTransaction {
            lengths.forEach { db.recordDao().insert(newRecord(projectId, kind, spec, color, it, note)) }
        }
    }

    /** 修改单根明细的长度与备注，汇总自动重算 */
    suspend fun updateRecord(id: String, lengthMm: Long, note: String) {
        db.recordDao().updateLengthNote(id, lengthMm, note.trim(), System.currentTimeMillis())
    }

    private fun newRecord(projectId: String, kind: String, spec: String, color: String, lengthMm: Long, note: String): RecordEntity {
        val now = System.currentTimeMillis()
        return RecordEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            kind = kind,
            spec = spec.trim(),
            color = color.trim(),
            lengthMm = lengthMm,
            note = note.trim(),
            createdAt = now,
            updatedAt = now
        )
    }

    suspend fun deleteRecord(id: String) = db.recordDao().deleteById(id)

    fun observeRecords(projectId: String): LiveData<List<RecordEntity>> =
        db.recordDao().observeByProject(projectId)

    suspend fun listCombo(projectId: String, kind: String, spec: String, color: String): List<RecordEntity> =
        db.recordDao().listCombo(projectId, kind, spec, color)

    /** 明细导出用：按 kind/spec/color 过滤（空串不限制） */
    suspend fun listComboFiltered(projectId: String, kind: String, spec: String, color: String): List<RecordEntity> =
        db.recordDao().allByProject(projectId).filter {
            (kind.isEmpty() || it.kind == kind) &&
                (spec.isEmpty() || it.spec == spec) &&
                (color.isEmpty() || it.color == color)
        }

    suspend fun allRecords(): List<RecordEntity> = db.recordDao().all()
    suspend fun listAllProjects(): List<ProjectEntity> = db.projectDao().listAll()

    // ---------- 累加统计（按 规格×颜色 自动累加） ----------

    /** 某项目汇总：(线缆分组, 铜排分组) */
    suspend fun aggregate(projectId: String): Pair<List<SpecAgg>, List<SpecAgg>> {
        val records = db.recordDao().allByProject(projectId)
        val colorOrder = db.colorDao().listAll().map { it.label }
        val wireOrder = db.specDao().listByKind(Kind.WIRE).associate { it.label to it.sortKey }
        val busOrder = db.specDao().listByKind(Kind.BUSBAR).associate { it.label to it.sortKey }
        return buildAggregation(records, colorOrder, wireOrder, busOrder)
    }

    /** 在内存中按 规格→颜色 累加（记录量级为百级，无需 SQL 聚合） */
    fun buildAggregation(
        records: List<RecordEntity>,
        colorOrder: List<String>,
        wireOrder: Map<String, Double>,
        busOrder: Map<String, Double>
    ): Pair<List<SpecAgg>, List<SpecAgg>> {
        fun group(kind: String, specOrder: Map<String, Double>): List<SpecAgg> {
            val list = records.filter { it.kind == kind }
            val combos = list.groupBy { it.spec to it.color }
                .map { (key, rs) ->
                    ComboAgg(kind, key.first, key.second, rs.sumOf { it.lengthMm }, rs.size.toLong())
                }
            return list.groupBy { it.spec }
                .map { (spec, rs) ->
                    SpecAgg(
                        kind = kind,
                        spec = spec,
                        totalMm = rs.sumOf { it.lengthMm },
                        count = rs.size.toLong(),
                        combos = combos.filter { it.spec == spec }.sortedWith { a, b ->
                            val ia = colorOrder.indexOf(a.color)
                            val ib = colorOrder.indexOf(b.color)
                            val ea = if (ia >= 0) ia else 999
                            val eb = if (ib >= 0) ib else 999
                            if (ea != eb) ea.compareTo(eb) else a.color.compareTo(b.color)
                        }
                    )
                }
                .sortedWith(compareBy<SpecAgg> { orderOf(kind, specOrder, it.spec) }.thenBy { it.spec })
        }
        return group(Kind.WIRE, wireOrder) to group(Kind.BUSBAR, busOrder)
    }

    private fun orderOf(kind: String, order: Map<String, Double>, spec: String): Double =
        order[spec] ?: if (kind == Kind.BUSBAR) busbarKey(spec) else wireKey(spec)

    // ---------- 候选池 ----------

    fun observeWireSpecs(): LiveData<List<SpecEntity>> = db.specDao().observeByKind(Kind.WIRE)
    fun observeBusbarSpecs(): LiveData<List<SpecEntity>> = db.specDao().observeByKind(Kind.BUSBAR)
    fun observeColors(): LiveData<List<ColorEntity>> = db.colorDao().observeAll()

    suspend fun addSpec(kind: String, label: String) {
        val normalized = if (kind == Kind.WIRE) normalizeWireSpec(label) else label.trim()
        val key = if (kind == Kind.BUSBAR) busbarKey(normalized) else wireKey(normalized)
        db.specDao().insert(SpecEntity(UUID.randomUUID().toString(), kind, normalized, key))
    }

    suspend fun deleteSpec(id: String) = db.specDao().deleteById(id)

    suspend fun addColor(label: String) {
        val max = db.colorDao().listAll().maxOfOrNull { it.sortKey } ?: -1
        db.colorDao().insert(ColorEntity(UUID.randomUUID().toString(), label.trim(), max + 1))
    }

    suspend fun deleteColor(id: String) = db.colorDao().deleteById(id)

    suspend fun wireSpecs(): List<SpecEntity> = db.specDao().listByKind(Kind.WIRE)
    suspend fun busbarSpecs(): List<SpecEntity> = db.specDao().listByKind(Kind.BUSBAR)
    suspend fun colors(): List<ColorEntity> = db.colorDao().listAll()
}