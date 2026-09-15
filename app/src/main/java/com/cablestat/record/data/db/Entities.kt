package com.cablestat.record.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 分类常量 */
object Kind {
    const val WIRE = "wire"      // 线缆
    const val BUSBAR = "busbar"  // 铜排
}

/** 配电柜项目（不同柜子分开统计） */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val remark: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

/** 用线/用铜记录：截一根记一根，同一规格自动累加 */
@Entity(
    tableName = "records",
    indices = [Index(value = ["projectId"]), Index(value = ["projectId", "kind", "spec"])]
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    /** Kind.WIRE / Kind.BUSBAR */
    val kind: String,
    /** 线缆："1.5"；铜排："40×5" */
    val spec: String,
    /** 线缆颜色，铜排恒为空串 */
    val color: String = "",
    /** 单根长度（毫米） */
    @ColumnInfo(name = "lengthMm") val lengthMm: Long,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

/** 规格候选池（线缆 mm² / 铜排 宽×厚） */
@Entity(tableName = "specs", indices = [Index(value = ["kind", "label"], unique = true)])
data class SpecEntity(
    @PrimaryKey val id: String,
    /** Kind.WIRE / Kind.BUSBAR */
    val kind: String,
    /** 展示值："1.5" / "40×5" */
    val label: String,
    /** 排序键：线缆=mm²数值；铜排=截面积(宽×厚) */
    val sortKey: Double
)

/** 线缆颜色候选池 */
@Entity(tableName = "colors", indices = [Index(value = ["label"], unique = true)])
data class ColorEntity(
    @PrimaryKey val id: String,
    val label: String,
    val sortKey: Int
)