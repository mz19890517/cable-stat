package com.cablestat.record.ui

import android.content.Context
import android.graphics.Color
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.cablestat.record.data.Repository
import com.cablestat.record.data.db.ColorEntity
import com.cablestat.record.data.db.Kind
import com.cablestat.record.data.db.RecordEntity
import com.cablestat.record.data.db.SpecEntity
import com.cablestat.record.databinding.DialogRecordBinding
import com.cablestat.record.util.Fmt
import com.cablestat.record.util.WireColor
import com.google.android.material.chip.Chip

/**
 * 快速记一笔：分类→规格→（线缆才需要）颜色→长度。
 * 长度框每行一根（可多行），每一根各记一条明细；同一 规格×颜色 自动累加，
 * 弹窗底部实时显示已有累计与本次（多根合计）后累计。
 */
object QuickRecordDialog {

    fun show(
        context: Context,
        projectId: String,
        records: List<RecordEntity>,
        wireSpecs: List<SpecEntity>,
        busbarSpecs: List<SpecEntity>,
        colors: List<ColorEntity>,
        presetKind: String = Kind.WIRE,
        presetSpec: String? = null,
        presetColor: String? = null,
        onSaved: (kind: String, spec: String, color: String, lengths: List<Long>, note: String) -> Unit
    ) {
        val vb = DialogRecordBinding.inflate(LayoutInflater.from(context))
        var kind = if (presetKind == Kind.BUSBAR) Kind.BUSBAR else Kind.WIRE

        // ---------- 辅助：当前选中的规格/颜色/累计 ----------

        fun specOf(): String {
            val custom = vb.etSpecCustom.text.toString().trim()
            if (custom.isNotEmpty()) return custom
            val sel = vb.cgSpec.checkedChipId
            if (sel == -1) return ""
            val text = vb.cgSpec.findViewById<Chip>(sel)?.text?.toString() ?: return ""
            return if (kind == Kind.WIRE) text.removeSuffix("mm²").trim() else text.trim()
        }

        fun colorOf(): String {
            if (kind == Kind.BUSBAR) return ""
            val sel = vb.cgColor.checkedChipId
            if (sel == -1) return ""
            return vb.cgColor.findViewById<Chip>(sel)?.text?.toString() ?: ""
        }

        fun refreshTotals() {
            val showColor = kind == Kind.WIRE
            vb.tvColorLabel.visibility = if (showColor) View.VISIBLE else View.GONE
            vb.cgColor.visibility = vb.tvColorLabel.visibility

            val spec = specOf()
            val color = colorOf()
            val existing = records
                .filter { it.kind == kind && it.spec == spec && it.color == color }
                .sumOf { it.lengthMm }
            if (spec.isNotEmpty() && (kind == Kind.BUSBAR || color.isNotEmpty())) {
                val head = when (kind) {
                    Kind.WIRE -> "规格 ${specLabel(spec)} · $color"
                    else -> "规格 $spec"
                }
                vb.tvExistingTotal.text = "$head 已有累计 ${Fmt.length(existing)}"
                vb.tvExistingTotal.visibility = View.VISIBLE
            } else {
                vb.tvExistingTotal.visibility = View.GONE
            }

            val parsed = Repository.parseLengths(vb.etLength.text.toString())
            val lengths = parsed.ok
            val sum = lengths.sum()
            if (sum > 0) {
                vb.tvLengthPreview.text = "共 ${lengths.size} 根 · 本次计入 ${Fmt.length(sum)}，本次后累计 ${Fmt.length(existing + sum)}"
            } else {
                vb.tvLengthPreview.text = "单位：毫米(mm)，1000mm = 1米。每行填一根，可一次填多根"
            }
        }

        fun rebuildSpecChips() {
            vb.cgSpec.removeAllViews()
            val list = if (kind == Kind.BUSBAR) busbarSpecs else wireSpecs
            list.forEach { s ->
                val chip = Chip(context).apply {
                    text = if (kind == Kind.BUSBAR) s.label else specLabel(s.label)
                    isCheckable = true
                    isCheckedIconVisible = false
                    textSize = 13f
                }
                chip.setOnClickListener { refreshTotals() }
                if (s.label == presetSpec) chip.isChecked = true
                vb.cgSpec.addView(chip)
            }
            if (vb.cgSpec.checkedChipId == -1 && vb.cgSpec.childCount > 0) {
                (vb.cgSpec.getChildAt(0) as Chip).isChecked = true
            }
            vb.etSpecCustom.text?.clear()
            refreshTotals()
        }

        fun rebuildColorChips() {
            vb.cgColor.removeAllViews()
            colors.forEach { c ->
                val chip = Chip(context).apply {
                    text = c.label
                    isCheckable = true
                    isCheckedIconVisible = false
                    textSize = 13f
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(WireColor.of(c.label))
                    setTextColor(
                        android.content.res.ColorStateList.valueOf(
                            if (WireColor.of(c.label) == Color.WHITE) Color.parseColor("#37474F") else Color.WHITE
                        )
                    )
                }
                chip.setOnClickListener { refreshTotals() }
                if (c.label == presetColor) chip.isChecked = true
                vb.cgColor.addView(chip)
            }
            if (vb.cgColor.checkedChipId == -1 && vb.cgColor.childCount > 0) {
                (vb.cgColor.getChildAt(0) as Chip).isChecked = true
            }
            refreshTotals()
        }

        // ---------- 初始状态 ----------

        vb.chipKindWire.isChecked = kind == Kind.WIRE
        vb.chipKindBusbar.isChecked = kind == Kind.BUSBAR
        vb.tvSpecLabel.text = if (kind == Kind.BUSBAR) "规格（宽×厚，单位 mm）" else "规格（截面 mm²）"

        vb.cgKind.setOnCheckedStateChangeListener { _, _ ->
            kind = if (vb.chipKindBusbar.isChecked) Kind.BUSBAR else Kind.WIRE
            vb.tvSpecLabel.text = if (kind == Kind.BUSBAR) "规格（宽×厚，单位 mm）" else "规格（截面 mm²）"
            rebuildSpecChips()
            rebuildColorChips()
        }
        vb.etSpecCustom.addTextChangedListener(textWatcher { refreshTotals() })
        vb.etLength.addTextChangedListener(textWatcher { refreshTotals() })

        rebuildSpecChips()
        rebuildColorChips()

        // ---------- 弹窗 ----------

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (presetSpec == null) "记一笔 · 截一根记一根" else "追加长度 · 记一笔")
            .setView(vb.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val spec = specOf()
            val color = colorOf()
            val parsed = Repository.parseLengths(vb.etLength.text.toString())
            val invalid = when {
                spec.isEmpty() -> "请选择或输入规格"
                kind == Kind.WIRE && color.isEmpty() -> "请选择线缆颜色"
                kind == Kind.WIRE && Repository.wireKey(spec) == Double.MAX_VALUE -> "线缆规格需为数字，如 1.5"
                kind == Kind.BUSBAR && !Repository.isBusbarLike(spec) -> "铜排规格需含 ×，如 40×5"
                parsed.ok.isEmpty() -> "请填写每根长度（毫米，每行一根），需大于 0"
                parsed.invalid.isNotEmpty() -> "以下内容无法识别为长度：${parsed.invalid.joinToString("、")}"
                else -> null
            }
            if (invalid != null) {
                Toast.makeText(context, invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSaved(kind, spec.trim(), if (kind == Kind.WIRE) color else "", parsed.ok, vb.etNote.text.toString().trim())
            dialog.dismiss()
        }
    }

    /** 线缆规格展示为 "1.5mm²"（保持原始写法，便于与候选池精确匹配） */
    private fun specLabel(spec: String): String = "${spec.trim()}mm²"

    private fun textWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange()
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
}