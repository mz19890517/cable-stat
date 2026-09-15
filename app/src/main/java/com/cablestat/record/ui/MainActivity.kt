package com.cablestat.record.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cablestat.record.App
import com.cablestat.record.core.XlsxWriter
import com.cablestat.record.data.Export
import com.cablestat.record.data.ProjectCard
import com.cablestat.record.data.Repository
import com.cablestat.record.databinding.ActivityMainBinding
import com.cablestat.record.databinding.DialogProjectBinding
import com.cablestat.record.databinding.ItemProjectBinding
import com.cablestat.record.util.DT
import com.cablestat.record.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repo: Repository by lazy { (application as App).repository }
    private val adapter = ProjectAdapter()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
            if (uri != null) doExportAll(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvProjects.layoutManager = LinearLayoutManager(this)
        binding.rvProjects.adapter = adapter

        binding.fabAddProject.setOnClickListener { showProjectDialog(null) }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, CandidateManagerActivity::class.java))
        }
        binding.btnExport.setOnClickListener {
            exportLauncher.launch("线缆统计_${DT.date(System.currentTimeMillis())}.xlsx")
        }

        repo.observeProjectCards().observe(this) { cards ->
            adapter.submit(cards)
            binding.tvEmpty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // 首次启动写入候选池（幂等）
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.seedIfEmpty() }
        }
    }

    private fun showProjectDialog(existing: ProjectCard?) {
        val vb = DialogProjectBinding.inflate(layoutInflater)
        if (existing != null) {
            vb.etProjName.setText(existing.project.name)
            vb.etProjRemark.setText(existing.project.remark)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新建配电柜项目" else "编辑项目")
            .setView(vb.root)
            .setPositiveButton("确定") { _, _ ->
                val name = vb.etProjName.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        if (existing == null) repo.addProject(name, vb.etProjRemark.text.toString())
                        else repo.renameProject(existing.project, name, vb.etProjRemark.text.toString())
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteProject(p: com.cablestat.record.data.db.ProjectEntity) {
        AlertDialog.Builder(this)
            .setTitle("删除项目")
            .setMessage("确定删除「${p.name}」？该项目的全部用线记录将一并删除，且无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deleteProject(p.id) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExportAll(uri: android.net.Uri) {
        lifecycleScope.launch {
            val (projects, records) = withContext(Dispatchers.IO) {
                repo.listAllProjects() to repo.allRecords()
            }
            val sheets = Export.build(projects, records)
            // 不需要网络/权限，直接经 SAF 写文件
            contentResolver.openOutputStream(uri)?.use { XlsxWriter.write(it, sheets) }
            toast("已导出 ${sheets.size} 张工作表")
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---------- 项目卡片适配器 ----------

    inner class ProjectAdapter : RecyclerView.Adapter<ProjectAdapter.VH>() {
        private var items: List<ProjectCard> = emptyList()

        fun submit(list: List<ProjectCard>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vb = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(vb)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class VH(private val vb: ItemProjectBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(card: ProjectCard) {
                val p = card.project
                val s = card.stats
                vb.tvProjName.text = p.name
                vb.tvProjRemark.text = p.remark
                vb.tvProjRemark.visibility = if (p.remark.isEmpty()) View.GONE else View.VISIBLE
                vb.tvProjStats.text = "线缆 ${Fmt.meterText(s.wireMm)} 米 · 铜排 ${Fmt.meterText(s.busbarMm)} 米"
                vb.tvProjTotal.text = "总 ${Fmt.meterText(s.totalMm)}m"
                vb.tvProjMeta.text = "创建于 ${DT.date(p.createdAt)} · 共 ${s.recordCount} 条记录"
                vb.root.setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, ProjectDetailActivity::class.java)
                            .putExtra(ProjectDetailActivity.EXTRA_PROJECT_ID, p.id)
                            .putExtra(ProjectDetailActivity.EXTRA_PROJECT_NAME, p.name)
                    )
                }
                vb.root.setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(p.name)
                        .setItems(arrayOf("编辑项目", "删除项目")) { _, which ->
                            if (which == 0) showProjectDialog(card) else confirmDeleteProject(p)
                        }
                        .show()
                    true
                }
            }
        }
    }
}