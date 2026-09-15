package com.cablestat.record.data

import com.cablestat.record.core.XlsxWriter
import com.cablestat.record.data.db.Kind
import com.cablestat.record.data.db.ProjectEntity
import com.cablestat.record.data.db.RecordEntity
import com.cablestat.record.util.DT
import com.cablestat.record.util.Fmt

/** 根据记录集构建 Excel 工作表定义 */
object Export {

    fun build(
        projects: List<ProjectEntity>,
        records: List<RecordEntity>
    ): List<XlsxWriter.SheetDef> {
        val sheets = mutableListOf<XlsxWriter.SheetDef>()
        val projName = projects.associate { it.id to it.name }

        // 项目汇总
        val projRows = projects.map { p ->
            val rs = records.filter { it.projectId == p.id }
            val wire = rs.filter { it.kind == Kind.WIRE }.sumOf { it.lengthMm }
            val bus = rs.filter { it.kind == Kind.BUSBAR }.sumOf { it.lengthMm }
            val total = wire + bus
            listOf(
                p.name,
                p.remark,
                wire.toString(),
                Fmt.meterText(wire),
                bus.toString(),
                Fmt.meterText(bus),
                total.toString(),
                Fmt.meterText(total),
                rs.size.toString()
            )
        }
        sheets.add(
            XlsxWriter.SheetDef(
                "项目汇总",
                listOf("项目", "备注", "线缆长(mm)", "线缆长(m)", "铜排长(mm)", "铜排长(m)", "总长(mm)", "总长(m)", "记录数"),
                projRows
            )
        )

        // 线缆汇总
        val wireCombo = records.filter { it.kind == Kind.WIRE }
            .groupBy { it.projectId to it.spec to it.color }
            .map { (key, rs) ->
                val (projSpec, color) = key
                val (projId, spec) = projSpec
                buildString3(projName, projId, spec, color, rs)
            }
        sheets.add(
            XlsxWriter.SheetDef(
                "线缆汇总",
                listOf("项目", "规格(mm²)", "颜色", "次数", "长度(mm)", "长度(m)"),
                wireCombo
            )
        )

        // 铜排汇总
        val busCombo = records.filter { it.kind == Kind.BUSBAR }
            .groupBy { it.projectId to it.spec }
            .map { (key, rs) ->
                val (projId, spec) = key
                listOf(
                    projName[projId] ?: "",
                    spec,
                    rs.size.toString(),
                    rs.sumOf { it.lengthMm }.toString(),
                    Fmt.meterText(rs.sumOf { it.lengthMm })
                )
            }
        sheets.add(
            XlsxWriter.SheetDef(
                "铜排汇总",
                listOf("项目", "规格(宽×厚)", "次数", "长度(mm)", "长度(m)"),
                busCombo
            )
        )

        // 线缆明细
        val wireDetail = records.filter { it.kind == Kind.WIRE }
            .sortedByDescending { it.createdAt }
            .map {
                listOf(
                    DT.dateTimeSec(it.createdAt),
                    projName[it.projectId] ?: "",
                    it.spec,
                    it.color,
                    it.lengthMm.toString(),
                    Fmt.meterText(it.lengthMm),
                    it.note
                )
            }
        sheets.add(
            XlsxWriter.SheetDef(
                "线缆明细",
                listOf("时间", "项目", "规格(mm²)", "颜色", "长度(mm)", "长度(m)", "备注"),
                wireDetail,
                wrapCols = setOf(6)
            )
        )

        // 铜排明细
        val busDetail = records.filter { it.kind == Kind.BUSBAR }
            .sortedByDescending { it.createdAt }
            .map {
                listOf(
                    DT.dateTimeSec(it.createdAt),
                    projName[it.projectId] ?: "",
                    it.spec,
                    it.lengthMm.toString(),
                    Fmt.meterText(it.lengthMm),
                    it.note
                )
            }
        sheets.add(
            XlsxWriter.SheetDef(
                "铜排明细",
                listOf("时间", "项目", "规格(宽×厚)", "长度(mm)", "长度(m)", "备注"),
                busDetail,
                wrapCols = setOf(5)
            )
        )

        return sheets
    }

    private fun buildString3(projName: Map<String, String>, projId: String, spec: String, color: String, rs: List<RecordEntity>): List<String> =
        listOf(
            projName[projId] ?: "",
            spec,
            color,
            rs.size.toString(),
            rs.sumOf { it.lengthMm }.toString(),
            Fmt.meterText(rs.sumOf { it.lengthMm })
        )
}