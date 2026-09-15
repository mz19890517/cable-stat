package com.cablestat.record.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cablestat.record.App
import com.cablestat.record.data.Repository
import com.cablestat.record.data.db.ColorEntity
import com.cablestat.record.data.db.Kind
import com.cablestat.record.data.db.SpecEntity
import com.cablestat.record.databinding.ActivityCandidateManagerBinding
import com.cablestat.record.databinding.ItemCandidateBinding
import com.cablestat.record.databinding.ItemSectionHeaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 候选池管理：线缆规格 / 线缆颜色 / 铜排规格，供录入时快速选择 */
class CandidateManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCandidateManagerBinding
    private val repo: Repository by lazy { (application as App).repository }
    private val adapter = CandAdapter()

    private var wireSpecs: List<SpecEntity> = emptyList()
    private var colors: List<ColorEntity> = emptyList()
    private var busbarSpecs: List<SpecEntity> = emptyList()

    private enum class Section { WIRE_SPEC, COLOR, BUSBAR_SPEC }

    sealed class Item {
        class Header(val section: Section) : Item()
        class SpecRow(val kind: String, val s: SpecEntity) : Item()
        class ColorRow(val c: ColorEntity) : Item()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCandidateManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddSpec.setOnClickListener {
            val sections = arrayOf("线缆规格", "线缆颜色", "铜排规格")
            AlertDialog.Builder(this)
                .setTitle("新增到哪个候选池")
                .setItems(sections) { _, which ->
                    when (which) {
                        0 -> addDialog(Section.WIRE_SPEC)
                        1 -> addDialog(Section.COLOR)
                        2 -> addDialog(Section.BUSBAR_SPEC)
                    }
                }
                .show()
        }
        binding.rvCandidates.layoutManager = LinearLayoutManager(this)
        binding.rvCandidates.adapter = adapter

        repo.observeWireSpecs().observe(this) { wireSpecs = it; rebuild() }
        repo.observeColors().observe(this) { colors = it; rebuild() }
        repo.observeBusbarSpecs().observe(this) { busbarSpecs = it; rebuild() }
    }

    private fun rebuild() {
        val items = mutableListOf<Item>()
        if (wireSpecs.isNotEmpty()) {
            items.add(Item.Header(Section.WIRE_SPEC))
            wireSpecs.forEach { items.add(Item.SpecRow(Kind.WIRE, it)) }
        }
        if (colors.isNotEmpty()) {
            items.add(Item.Header(Section.COLOR))
            colors.forEach { items.add(Item.ColorRow(it)) }
        }
        if (busbarSpecs.isNotEmpty()) {
            items.add(Item.Header(Section.BUSBAR_SPEC))
            busbarSpecs.forEach { items.add(Item.SpecRow(Kind.BUSBAR, it)) }
        }
        adapter.submit(items)
    }

    private fun addDialog(section: Section) {
        val input = EditText(this).apply {
            hint = when (section) {
                Section.WIRE_SPEC -> "数字，如 120"
                Section.COLOR -> "颜色名，如 紫"
                Section.BUSBAR_SPEC -> "宽×厚，如 40×5"
            }
            setPadding(24, 8, 24, 8)
        }
        AlertDialog.Builder(this)
            .setTitle(when (section) {
                Section.WIRE_SPEC -> "新增线缆规格"
                Section.COLOR -> "新增线缆颜色"
                Section.BUSBAR_SPEC -> "新增铜排规格"
            })
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val label = input.text.toString().trim()
                val invalid = when (section) {
                    Section.COLOR -> if (label.isEmpty()) "颜色名不能为空" else null
                    Section.WIRE_SPEC -> when {
                        label.isEmpty() -> "请输入规格"
                        Repository.wireKey(label) == Double.MAX_VALUE -> "线缆规格需为数字，如 1.5"
                        else -> null
                    }
                    Section.BUSBAR_SPEC -> when {
                        label.isEmpty() -> "请输入规格"
                        !Repository.isBusbarLike(label) -> "铜排规格需含 ×，如 40×5"
                        else -> null
                    }
                }
                if (invalid != null) {
                    Toast.makeText(this, invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        when (section) {
                            Section.COLOR -> repo.addColor(label)
                            else -> repo.addSpec(
                                if (section == Section.WIRE_SPEC) Kind.WIRE else Kind.BUSBAR,
                                label
                            )
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(title: String, block: suspend () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("删除候选")
            .setMessage("确定从候选池删除「$title」？已录入的记录不受影响。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch { withContext(Dispatchers.IO) { block() } }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class CandAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Item> = emptyList()

        fun submit(list: List<Item>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            val item = items[position]
            return when {
                item is Item.Header -> 0
                item is Item.SpecRow || item is Item.ColorRow -> 1
                else -> 1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            } else {
                RowVH(ItemCandidateBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder as HeaderVH).bind(item)
                is Item.SpecRow -> (holder as RowVH).bind(item)
                is Item.ColorRow -> (holder as RowVH).bind(item)
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderVH(private val vb: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(item: Item.Header) {
                vb.root.text = when (item.section) {
                    Section.WIRE_SPEC -> "线缆规格（mm²）"
                    Section.COLOR -> "线缆颜色"
                    Section.BUSBAR_SPEC -> "铜排规格（宽×厚）"
                }
            }
        }

        inner class RowVH(private val vb: ItemCandidateBinding) : RecyclerView.ViewHolder(vb.root) {
            fun bind(item: Item.SpecRow) {
                vb.tvCandLabel.text = item.s.label
                vb.btnCandDelete.setOnClickListener {
                    confirmDelete(item.s.label) { repo.deleteSpec(item.s.id) }
                }
            }

            fun bind(item: Item.ColorRow) {
                vb.tvCandLabel.text = item.c.label
                vb.btnCandDelete.setOnClickListener {
                    confirmDelete(item.c.label) { repo.deleteColor(item.c.id) }
                }
            }
        }
    }
}