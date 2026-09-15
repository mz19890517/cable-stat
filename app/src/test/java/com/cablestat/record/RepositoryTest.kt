package com.cablestat.record

import androidx.room.Room
import com.cablestat.record.data.Repository
import com.cablestat.record.data.db.AppDatabase
import com.cablestat.record.data.db.ColorEntity
import com.cablestat.record.data.db.Kind
import com.cablestat.record.data.db.RecordEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SeedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = Repository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun seedsWireSpecsColorsBusbarSpecs() = runTest {
        repo.seedIfEmpty()
        val wire = repo.wireSpecs()
        assertEquals(16, wire.size)
        assertEquals("1.0", wire.first().label)
        assertTrue(wire.any { it.label == "240.0" })
        assertTrue(wire.zipWithNext().all { (a, b) -> a.sortKey <= b.sortKey })

        val bus = repo.busbarSpecs()
        assertTrue(bus.any { it.label == "40×5" })
        assertTrue(bus.zipWithNext().all { (a, b) -> a.sortKey <= b.sortKey })

        val colors = repo.colors()
        assertTrue(colors.map { it.label }.containsAll(listOf("红", "黄", "绿", "蓝", "黄绿", "黑")))
        assertTrue(colors.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
    }

    @Test
    fun seedIsIdempotent() = runTest {
        repo.seedIfEmpty()
        repo.seedIfEmpty()
        assertEquals(16, repo.wireSpecs().size)
        assertEquals(9, repo.colors().size)
    }
}

@RunWith(RobolectricTestRunner::class)
class AggregateTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private var projectId = ""

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = Repository(db)
        projectId = UUID.randomUUID().toString()
        runBlocking {
            db.projectDao().insert(
                com.cablestat.record.data.db.ProjectEntity(
                    id = projectId, name = "测试柜", remark = "", createdAt = 1L, updatedAt = 1L
                )
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun rec(spec: String, color: String, mm: Long, kind: String = Kind.WIRE) =
        RecordEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            kind = kind,
            spec = spec,
            color = color,
            lengthMm = mm,
            note = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

    @Test
    fun sameSpecColorAutoAccumulates() = runTest {
        db.recordDao().insert(rec("1.5", "红", 3000))
        db.recordDao().insert(rec("1.5", "红", 4200))
        db.recordDao().insert(rec("1.5", "蓝", 1850))
        db.recordDao().insert(rec("2.5", "红", 1000))
        db.recordDao().insert(rec("40×5", "", 600, Kind.BUSBAR))

        val (wire, bus) = repo.aggregate(projectId)

        // 线缆：1.5 红 累加成 7200，1.5 蓝 1850
        assertEquals(2, wire.size)
        val g15 = wire.find { it.spec == "1.5" }!!
        assertEquals(5050L, g15.totalMm)
        assertEquals(3L, g15.count)
        assertEquals(2, g15.combos.size)
        assertEquals(7200L, g15.combos.find { it.color == "红" }!!.totalMm)
        assertEquals(2L, g15.combos.find { it.color == "红" }!!.count)

        // 铜排
        assertEquals(1, bus.size)
        assertEquals("40×5", bus.first().spec)
        assertEquals(600L, bus.first().totalMm)
    }

    @Test
    fun wireSizedByNumberBusbarSizedByArea() = runTest {
        val records = listOf(
            rec("10", "红", 1),
            rec("1.5", "红", 1),
            rec("2.5", "红", 1),
            rec("4", "红", 1),
            rec("120", "红", 1),
            rec("100×10", "", 1, Kind.BUSBAR),
            rec("20×3", "", 1, Kind.BUSBAR)
        )
        val colorOrder = listOf("红", "黄", "绿")
        val wireOrder = mapOf("1.5" to 1.5, "2.5" to 2.5, "4" to 4.0, "10" to 10.0, "120" to 120.0)
        val busOrder = mapOf("20×3" to 60.0, "100×10" to 1000.0)
        val (wire, bus) = repo.buildAggregation(records, colorOrder, wireOrder, busOrder)

        assertEquals(listOf("1.5", "2.5", "4", "10", "120"), wire.map { it.spec })
        assertEquals(listOf("20×3", "100×10"), bus.map { it.spec })
    }

    @Test
    fun projectStatsTotals() = runTest {
        db.recordDao().insert(rec("1.5", "红", 3000))
        db.recordDao().insert(rec("40×5", "", 600, Kind.BUSBAR))
        val s = repo.projectStats(projectId)
        assertEquals(3600L, s.totalMm)
        assertEquals(3000L, s.wireMm)
        assertEquals(600L, s.busbarMm)
        assertEquals(2L, s.recordCount)
    }
}

@RunWith(RobolectricTestRunner::class)
class SpecParseTest {

    @Test
    fun parseKeys() {
        assertEquals(200.0, Repository.busbarKey("40×5"), 0.0)
        assertEquals(200.0, Repository.busbarKey("40x5"), 0.0)
        assertEquals(200.0, Repository.busbarKey("40*5"), 0.0)
        assertEquals(Double.MAX_VALUE, Repository.busbarKey("40"), 0.0)
        assertEquals(1.5, Repository.wireKey("1.5"), 0.0)
        assertEquals(Double.MAX_VALUE, Repository.wireKey("abc"), 0.0)
        assertTrue(Repository.isBusbarLike("40×5"))
        assertTrue(!Repository.isBusbarLike("1.5"))
    }
}

@RunWith(RobolectricTestRunner::class)
class ExportTest {

    @Test
    fun buildExportSheets() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = Repository(db)
        val p = repo.addProject("甲柜", "客户A")
        repo.addRecord(p.id, Kind.WIRE, "1.5", "红", 3000, "进线")
        repo.addRecord(p.id, Kind.WIRE, "1.5", "红", 4200, "")
        repo.addRecord(p.id, Kind.BUSBAR, "40×5", "", 600, "")
        repo.seedIfEmpty()

        val sheets = com.cablestat.record.data.Export.build(repo.listAllProjects(), repo.allRecords())
        assertEquals(5, sheets.size)
        val proj = sheets[0]
        assertEquals("项目", proj.headers[0])
        assertEquals("甲柜", proj.rows[0][0])
        // 线缆汇总里 1.5/红 累加为 7200
        val wireSum = sheets[1]
        val row = wireSum.rows.find { it[1] == "1.5" && it[2] == "红" }!!
        assertEquals("7200", row[4])
        assertEquals("铜排汇总", sheets[2].name)
        assertEquals("40×5", sheets[2].rows[0][1])

        // xlsx 可写出为合法 zip 且有 4 个 sheet xml
        val bytes = java.io.ByteArrayOutputStream()
        com.cablestat.record.core.XlsxWriter.write(bytes, sheets)
        val zip = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes.toByteArray()))
        val names = mutableListOf<String>()
        var e = zip.nextEntry
        while (e != null) {
            names.add(e.name)
            e = zip.nextEntry
        }
        assertEquals(4, names.count { it.startsWith("xl/worksheets/sheet") })
        assertTrue(names.contains("xl/workbook.xml"))
        db.close()
    }
}