package com.cablestat.record.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
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

class Repository(private val db: AppDatabase) {

    // ---------- 种子数据（首次启动写入候选池） ----------

    private val defaultWireSpecs = listOf(
        "1.0" to 1.0, "1.5" to 1.5, "2.5" to 2.5, "4.0" to 4.0, "6.0" to 6.0,
        "10.0" to 10.0, "16.0" to 16.0, "25.0" to 25.0, "35.0" to 35.0,
        "50.0" to 50.0, "70.0" to 70.0, "95.0" to 95.0, "120.0" to 120.0,
        "150.0" to 150.0, "185.0" to 185.0, "240.0" to 240.0
    )

    private val defaultColors = listOf("红", "黄", "绿", "蓝", "黄绿", "黑", "棕", "白", "灰")

    private val defaultBusbarSpecs = listOf(
        "20×3", "20×4", "25×3", "30×3", "30×4", "30×5", "40×4", "40×5", "40×6",
        "50×5", "50×6", "50×8", "60×6", "60×8", "60×10", "80×6", "80×8", "80×10",
        "100×8", "100×10", "120×8", "120×10", "125×10"
    )

    suspend fun seedIfEmpty() {
        if (db.specDao().listByKind(Kind.WIRE).isEmpty()) {
            defaultWireSpecs.forEach { (label, key) ->
                db.specDao().insert(SpecEntity(UUID.randomUUID().toString(), Kind.WIRE, label, key))
            }
        }
        if (db.specDao().listByKind(Kind.BUSBAR).isEmpty()) {
            defaultBusbarSpecs.forEach { label ->
                db.specDao().insert(SpecEntity(UUID.randomUUID().toString(), Kind.BUSBAR, label, busbarKey(label)))
            }
        }
        if (db.colorDao().listAll().isEmpty()) {
            defaultColors.forEachIndexed { i, c ->
                db.colorDao().insert(ColorEntity(UUID.randomUUID().toString(), c, i))
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

        /** 线缆规格 "1.5" → 1.5，不可解析给极大值排末尾 */
        fun wireKey(label: String): Double = label.trim().toDoubleOrNull() ?: Double.MAX_VALUE

        /** 判断规格是否为铜排样式 */
        fun isBusbarLike(spec: String): Boolean =
            spec.any { it == '×' || it == 'x' || it == 'X' || it == '*' }
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
        val now = System.currentTimeMillis()
        db.recordDao().insert(
            RecordEntity(
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
                .sortedWith(compareBy<SpecAgg> { orderOf(specOrder, it.spec) }.thenBy { it.spec })
        }
        return group(Kind.WIRE, wireOrder) to group(Kind.BUSBAR, busOrder)
    }

    private fun orderOf(order: Map<String, Double>, spec: String): Double =
        order[spec] ?: if (isBusbarLike(spec)) busbarKey(spec) else wireKey(spec)

    // ---------- 候选池 ----------

    fun observeWireSpecs(): LiveData<List<SpecEntity>> = db.specDao().observeByKind(Kind.WIRE)
    fun observeBusbarSpecs(): LiveData<List<SpecEntity>> = db.specDao().observeByKind(Kind.BUSBAR)
    fun observeColors(): LiveData<List<ColorEntity>> = db.colorDao().observeAll()

    suspend fun addSpec(kind: String, label: String) {
        val normalized = if (kind == Kind.WIRE) normalizeWireLabel(label) else label.trim()
        val key = if (kind == Kind.BUSBAR) busbarKey(label) else wireKey(label)
        db.specDao().insert(SpecEntity(UUID.randomUUID().toString(), kind, normalized, key))
    }

    /** 线缆规格尾零归一：4.0 → 4，4.00 → 4，避免与内置候选重复 */
    private fun normalizeWireLabel(label: String): String {
        val v = label.trim().toDoubleOrNull()
        return if (v != null && v == Math.floor(v)) v.toLong().toString() else label.trim()
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