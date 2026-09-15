package com.cablestat.record.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cablestat.record.App
import com.cablestat.record.core.XlsxWriter
import com.cablestat.record.data.Export
import com.cablestat.record.data.Repository
import com.cablestat.record.data.db.Kind
import com.cablestat.record.data.db.RecordEntity
import com.cablestat.record.databinding.ActivityHistoryBinding
import com.cablestat.record.databinding.DialogEditRecordBinding
import com.cablestat.record.databinding.ItemHistoryBinding
import com.cablestat.record.util.DT
import com.cablestat.record.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记录明细：某一 规格×颜色 的台账，或整个项目的全部明细 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SPEC = "spec"
        const val EXTRA_COLOR = "color"
    }

    private lateinit var binding: ActivityHistoryBinding
    private val repo: Repository by lazy { (application as App).repository }
    private val projectId: String by lazy { intent.getStringExtra(EXTRA_PROJECT_ID)!! }
    private val projectName: String by lazy { intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "" }
    private val filterKind: String by lazy { intent.getStringExtra(EXTRA_KIND) ?: "" }
    private val filterSpec: String by lazy { intent.getStringExtra(EXTRA_SPEC) ?: "" }
    private val filterColor: String by lazy { intent.getStringExtra(EXTRA_COLOR) ?: "" }

    private val adapter = HistAdapter()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
            if (uri != null) doExport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = when {
            filterSpec.isEmpty() -> "$projectName · 全部明细"
            filterKind == Kind.WIRE -> "${wSpec(filterSpec)} ${filterColor} · 明细"
            else -> "$filterSpec 铜排 · 明细"
        }
        binding.tvTitle.text = title
        binding.btnBack.setOnClickListener { finish() }
        binding.btnExportHist.setOnClickListener {
            exportLauncher.launch("线缆统计明细_${projectName}_${DT.date(System.currentTimeMillis())}.xlsx")
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        repo.observeRecords(projectId).observe(this) { list ->
            val filtered = list.filter { r ->
                (filterSpec.isEmpty() || r.spec == filterSpec) &&
                    (filterKind.isEmpty() || r.kind == filterKind) &&
                    (filterColor.isEmpty() || r.color == filterColor)
            }.sortedByDescending { it.createdAt }
            adapter.submit(filtered)
            val total = filtered.sumOf { it.lengthMm }
            binding.tvHistoryTotal.text = "共 ${filtered.size} 条 · 累计 ${Fmt.length(total)}"
            binding.tvHistoryEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun editRecord(r: RecordEntity) {
        val vb = DialogEditRecordBinding.inflate(layoutInflater)
        vb.etEditLen.setText(r.lengthMm.toString())
        vb.etEditNote.setText(r.note)
        AlertDialog.Builder(this)
            .setTitle(if (r.kind == Kind.WIRE) "${wSpec(r.spec)} $r.color · 修改" else "$r.spec 铜排 · 修改")
            .setView(vb.root)
            .setPositiveButton("保存") { _, _ ->
                val len = vb.etEditLen.text.toString().toLongOrNull() ?: 0L
                if (len <= 0) {
                    Toast.makeText(this, "长度需为大于 0 的数字（毫米）", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        repo.updateRecord(r.id, len, vb.etEditNote.text.toString())
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val (project, rs) = withContext(Dispatchers.IO) {
                repo.listAllProjects().find { it.id == projectId } to repo.listComboFiltered(projectId, filterKind, filterSpec, filterColor)
            }
            if (project == null) return@launch
            val sheets = Export.build(listOf(project), rs)
            contentResolver.openOutputStream(uri)?.use { XlsxWriter.write(it, sheets) }
            Toast.makeText(this@HistoryActivity, "已导出 ${sheets.size} 张工作表", Toast.LENGTH_SHORT).show()
        }
    }

    inner class HistAdapter : RecyclerView.Adapter<HistAdapter.VH>() {
        private var items: List<RecordEntity> = emptyList()

        fun submit(list: List<RecordEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(vb)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class VH(private val vb: ItemHistoryBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(r: RecordEntity) {
                vb.tvHistTime.text = DT.dateTimeSec(r.createdAt)
                vb.tvHistSpec.text = if (r.kind == Kind.WIRE) wSpec(r.spec) + (if (r.color.isNotEmpty()) " $r.color" else "") else r.spec
                vb.tvHistLen.text = Fmt.length(r.lengthMm)
                vb.tvHistNote.text = r.note
                vb.tvHistNote.visibility = if (r.note.isEmpty()) View.GONE else View.VISIBLE
                vb.btnHistEdit.setOnClickListener { editRecord(r) }
                vb.btnHistDelete.setOnClickListener {
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle("删除该条记录")
                        .setMessage("${DT.dateTimeSec(r.createdAt)} 截取 ${Fmt.length(r.lengthMm)} 确认删除？")
                        .setPositiveButton("删除") { _, _ ->
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { repo.deleteRecord(r.id) }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
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