package com.cablestat.record.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cablestat.record.App
import com.cablestat.record.R
import com.cablestat.record.core.XlsxWriter
import com.cablestat.record.data.ComboAgg
import com.cablestat.record.data.Export
import com.cablestat.record.data.Repository
import com.cablestat.record.data.SpecAgg
import com.cablestat.record.data.db.ColorEntity
import com.cablestat.record.data.db.Kind
import com.cablestat.record.data.db.ProjectEntity
import com.cablestat.record.data.db.RecordEntity
import com.cablestat.record.data.db.SpecEntity
import com.cablestat.record.databinding.ActivityProjectDetailBinding
import com.cablestat.record.databinding.ItemComboBinding
import com.cablestat.record.databinding.ItemSpecGroupBinding
import com.cablestat.record.util.DT
import com.cablestat.record.util.Fmt
import com.cablestat.record.util.WireColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_PROJECT_NAME = "project_name"
    }

    private lateinit var binding: ActivityProjectDetailBinding
    private val repo: Repository by lazy { (application as App).repository }
    private val projectId: String by lazy { intent.getStringExtra(EXTRA_PROJECT_ID)!! }
    private val projectName: String by lazy { intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "" }

    private var wireSpecs: List<SpecEntity> = emptyList()
    private var busbarSpecs: List<SpecEntity> = emptyList()
    private var colors: List<ColorEntity> = emptyList()
    private var records: List<RecordEntity> = emptyList()

    private var currentKind = Kind.WIRE
    private val expandedSpecs = mutableSetOf<String>()
    private var agg: Pair<List<SpecAgg>, List<SpecAgg>> = emptyList<SpecAgg>() to emptyList()

    private val adapter = AggAdapter()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
            if (uri != null) doExport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTitle.text = projectName
        binding.btnBack.setOnClickListener { finish() }
        binding.btnHistAll.setOnClickListener { openHistory(null, null, "") }
        binding.btnExportOne.setOnClickListener {
            exportLauncher.launch("线缆统计_${projectName}_${DT.date(System.currentTimeMillis())}.xlsx")
        }
        binding.fabAddRecord.setOnClickListener { quickRecord(null, null, null) }

        binding.rvAgg.layoutManager = LinearLayoutManager(this)
        binding.rvAgg.adapter = adapter

        binding.tabWire.isSelected = true
        binding.tabWire.setOnClickListener { switchKind(Kind.WIRE) }
        binding.tabBusbar.setOnClickListener { switchKind(Kind.BUSBAR) }

        loadCandidates()
        repo.observeProject(projectId).observe(this) { p ->
            if (p != null) {
                binding.tvTitle.text = p.name
            }
        }
        repo.observeRecords(projectId).observe(this) { list ->
            records = list
            lifecycleScope.launch {
                val a = withContext(Dispatchers.IO) { repo.aggregate(projectId) }
                agg = a
                renderAgg()
                updateHeaderTotal()
            }
        }
    }

    private fun loadCandidates() {
        lifecycleScope.launch {
            val (w, b, c) = withContext(Dispatchers.IO) {
                repo.seedIfEmpty()
                Triple(repo.wireSpecs(), repo.busbarSpecs(), repo.colors())
            }
            wireSpecs = w
            busbarSpecs = b
            colors = c
        }
    }

    private fun switchKind(kind: String) {
        currentKind = kind
        binding.tabWire.isSelected = kind == Kind.WIRE
        binding.tabBusbar.isSelected = kind == Kind.BUSBAR
        renderAgg()
    }

    private fun renderAgg() {
        val list = if (currentKind == Kind.WIRE) agg.first else agg.second
        adapter.submit(list, currentKind, expandedSpecs)
        binding.tvAggEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateHeaderTotal() {
        val wire = records.filter { it.kind == Kind.WIRE }.sumOf { it.lengthMm }
        val bus = records.filter { it.kind == Kind.BUSBAR }.sumOf { it.lengthMm }
        binding.tvProjTotalDetail.text =
            "线缆 ${Fmt.meterText(wire)} 米 · 铜排 ${Fmt.meterText(bus)} 米 · 共 ${records.size} 条"
    }

    private fun quickRecord(presetKind: String?, presetSpec: String?, presetColor: String?) {
        QuickRecordDialog.show(
            context = this,
            projectId = projectId,
            records = records,
            wireSpecs = wireSpecs,
            busbarSpecs = busbarSpecs,
            colors = colors,
            presetKind = presetKind ?: currentKind,
            presetSpec = presetSpec,
            presetColor = presetColor
        ) { kind, spec, color, lengthMm, note ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { repo.addRecord(projectId, kind, spec, color, lengthMm, note) }
            }
        }
    }

    private fun openHistory(kind: String?, spec: String?, color: String) {
        startActivity(
            Intent(this, HistoryActivity::class.java)
                .putExtra(HistoryActivity.EXTRA_PROJECT_ID, projectId)
                .putExtra(HistoryActivity.EXTRA_PROJECT_NAME, projectName)
                .putExtra(HistoryActivity.EXTRA_KIND, kind ?: "")
                .putExtra(HistoryActivity.EXTRA_SPEC, spec ?: "")
                .putExtra(HistoryActivity.EXTRA_COLOR, color)
        )
    }

    private fun doExport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val project = withContext(Dispatchers.IO) {
                repo.listAllProjects().find { it.id == projectId }
            }
            if (project == null) return@launch
            val rs = records
            val sheets = Export.build(listOf(project), rs)
            contentResolver.openOutputStream(uri)?.use { XlsxWriter.write(it, sheets) }
            Toast.makeText(this@ProjectDetailActivity, "已导出 ${sheets.size} 张工作表", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 汇总列表适配器 ----------

    sealed class Item {
        class Group(val agg: SpecAgg, val expanded: Boolean, val isWire: Boolean) : Item()
        class Combo(val agg2: ComboAgg) : Item()
    }

    inner class AggAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()
        private var isWire = true

        fun submit(list: List<SpecAgg>, kind: String, expanded: MutableSet<String>) {
            isWire = kind == Kind.WIRE
            items = list.flatMap { g ->
                val base = mutableListOf<Item>(Item.Group(g, expanded.contains(g.spec), isWire))
                if (isWire && expanded.contains(g.spec)) {
                    base.addAll(g.combos.map { Item.Combo(it) })
                }
                base
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) =
            if (items[position] is Item.Group) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) GroupVH(ItemSpecGroupBinding.inflate(inflater, parent, false))
            else ComboVH(ItemComboBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when {
                item is Item.Group && holder is GroupVH -> holder.bind(item)
                item is Item.Combo && holder is ComboVH -> holder.bind(item)
            }
        }

        override fun getItemCount() = items.size

        inner class GroupVH(private val vb: ItemSpecGroupBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(item: Item.Group) {
                val g = item.agg
                vb.tvKindBadge.text = if (g.kind == Kind.WIRE) "线" else "铜"
                vb.tvKindBadge.background = if (g.kind == Kind.WIRE)
                    getDrawable(R.drawable.pill_wire) else getDrawable(R.drawable.pill_busbar)
                vb.tvGroupSpec.text = if (g.kind == Kind.WIRE) "${wSpec(g.spec)}" else g.spec
                vb.tvGroupTotal.text = Fmt.length(g.totalMm)
                vb.tvGroupCount.text = "共 ${g.count} 根/条"
                vb.ivGroupExpand.visibility = if (g.kind == Kind.WIRE) View.VISIBLE else View.INVISIBLE
                vb.ivGroupExpand.setRotation(if (item.expanded) 180f else 0f)
                vb.root.setOnClickListener {
                    if (g.kind == Kind.WIRE) {
                        if (item.expanded) expandedSpecs.remove(g.spec) else expandedSpecs.add(g.spec)
                        renderAgg()
                    } else {
                        openHistory(g.kind, g.spec, "")
                    }
                }
                vb.btnGroupAdd.setOnClickListener {
                    quickRecord(g.kind, g.spec, null)
                }
            }
        }

        inner class ComboVH(private val vb: ItemComboBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(item: Item.Combo) {
                val c = item.agg2
                vb.tvComboColor.text = c.color
                vb.tvComboTotal.text = Fmt.length(c.totalMm)
                vb.tvComboCount.text = "×${c.count}"
                vb.vColorDot.setBackgroundColor(WireColor.of(c.color))
                vb.root.setOnClickListener { openHistory(c.kind, c.spec, c.color) }
                vb.btnComboAdd.setOnClickListener {
                    quickRecord(c.kind, c.spec, c.color)
                }
            }
        }
    }

    private fun wSpec(spec: String): String {
        val v = spec.toDoubleOrNull()
        return if (v != null) {
            val n = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
            "${n}mm²"
        } else spec
    }
}