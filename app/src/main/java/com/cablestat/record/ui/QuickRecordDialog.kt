package com.cablestat.record.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.cablestat.record.R
import com.cablestat.record.data.Repository
import com.cablestat.record.data.db.ColorEntity
import com.cablestat.record.data.db.Kind
import com.cablestat.record.data.db.RecordEntity
import com.cablestat.record.data.db.SpecEntity
import com.cablestat.record.databinding.DialogRecordBinding
import com.cablestat.record.databinding.ItemStagedBinding
import com.cablestat.record.util.LengthUnit
import com.cablestat.record.util.WireColor
import com.google.android.material.chip.Chip

/**
 * 记一笔：分类→规格→（线缆才需要）颜色→长度。
 * 支持批量：点“添加到批量列表”把当前一笔放入临时列表，可编辑/删除，
 * 全部输入完成后点“全部入库”一次写入项目。
 * 追加模式：从已有列表点“＋”进入，规格（与颜色）已确定，只输长度。
 */
object QuickRecordDialog {

    /** 批量暂存的一笔（长度已换算为毫米；根数=lengths.size） */
    data class Staged(
        val kind: String,
        val spec: String,
        val color: String,
        val lengths: List<Long>,
        val note: String
    )

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
        val unit = LengthUnit.current(context)
        var kind = if (presetKind == Kind.BUSBAR) Kind.BUSBAR else Kind.WIRE
        // 规格+颜色（铜排颜色恒空）都已确定 → 追加模式，只输长度与备注
        val appendMode = presetSpec != null && (kind == Kind.BUSBAR || presetColor != null)
        val targetSpec = presetSpec ?: ""
        val targetColor = presetColor ?: ""
        val staged = mutableListOf<Staged>()

        // ---------- 辅助：当前选中的规格/颜色/校验 ----------

        fun specOf(): String {
            if (appendMode) return targetSpec
            val custom = vb.etSpecCustom.text.toString().trim()
            if (custom.isNotEmpty()) return custom
            val sel = vb.cgSpec.checkedChipId
            if (sel == -1) return ""
            val text = vb.cgSpec.findViewById<Chip>(sel)?.text?.toString() ?: return ""
            return if (kind == Kind.WIRE) text.removeSuffix("mm²").trim() else text.trim()
        }

        fun colorOf(): String {
            if (appendMode) return targetColor
            if (kind == Kind.BUSBAR) return ""
            val sel = vb.cgColor.checkedChipId
            if (sel == -1) return ""
            return vb.cgColor.findViewById<Chip>(sel)?.text?.toString() ?: ""
        }

        /** 毫米 → 当前单位文本，如 1850mm + 厘米 → "185cm" */
        fun unitText(mm: Long): String {
            val v = mm.toDouble() / unit.toMm
            val txt = if (v == Math.floor(v)) v.toLong().toString()
            else {
                var s = String.format("%.2f", v)
                while (s.endsWith("0")) s = s.dropLast(1)
                if (s.endsWith(".")) s = s.dropLast(1)
                s
            }
            return "$txt${unit.code}"
        }

        fun currentStaged(): Staged? {
            val spec = specOf()
            val color = colorOf()
            val parsed = Repository.parseLengths(vb.etLength.text.toString())
            val invalid = when {
                appendMode -> null
                spec.isEmpty() -> "请选择或输入规格"
                kind == Kind.WIRE && color.isEmpty() -> "请选择线缆颜色"
                kind == Kind.WIRE && Repository.wireKey(spec) == Double.MAX_VALUE -> "线缆规格需为数字，如 1.5"
                kind == Kind.BUSBAR && !Repository.isBusbarLike(spec) -> "铜排规格需含 ×，如 40×5"
                parsed.ok.isEmpty() -> "请填写每根长度（单位 ${unit.label}，每行一根），需大于 0"
                parsed.invalid.isNotEmpty() -> "以下内容无法识别为长度：${parsed.invalid.joinToString("、")}"
                else -> null
            }
            if (invalid != null) {
                Toast.makeText(context, invalid, Toast.LENGTH_SHORT).show()
                return null
            }
            return Staged(
                kind = kind,
                spec = spec.trim(),
                color = if (kind == Kind.WIRE) color else "",
                lengths = parsed.ok.map { it * unit.toMm },
                note = vb.etNote.text.toString().trim()
            )
        }

        fun refreshTotals() {
            val showColor = kind == Kind.WIRE && !appendMode
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
                vb.tvExistingTotal.text = "$head 已有累计 ${unitText(existing)}"
                vb.tvExistingTotal.visibility = View.VISIBLE
            } else {
                vb.tvExistingTotal.visibility = View.GONE
            }

            val parsed = Repository.parseLengths(vb.etLength.text.toString())
            val lengths = parsed.ok
            val sum = lengths.sum()
            if (sum > 0) {
                vb.tvLengthPreview.text =
                    "共 ${lengths.size} 根 · 本次 ${unitText(sum * unit.toMm)}，本次后累计 ${unitText(existing + sum * unit.toMm)}"
            } else {
                vb.tvLengthPreview.text =
                    "单位：${unit.label}。每行一根，可一次多行；同一长度多根写 85×5（5根各85）"
            }
        }

        /** 选中态描边：选中显示高亮环，未选中透明 */
        fun selectedStroke(checkedColor: Int): ColorStateList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(checkedColor, Color.TRANSPARENT)
        )

        fun rebuildSpecChips(selectSpec: String? = presetSpec) {
            vb.cgSpec.removeAllViews()
            val list = if (kind == Kind.BUSBAR) busbarSpecs else wireSpecs
            list.forEach { s ->
                val chip = Chip(context).apply {
                    text = if (kind == Kind.BUSBAR) s.label else specLabel(s.label)
                    isCheckable = true
                    isCheckedIconVisible = false
                    textSize = 13f
                    chipStrokeWidth = 2f
                    chipStrokeColor = selectedStroke(context.getColor(R.color.primary))
                    maxLines = 1
                }
                chip.setOnClickListener { refreshTotals() }
                chip.setOnCheckedChangeListener { c, checked ->
                    c.typeface = if (checked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                if (s.label == selectSpec) chip.isChecked = true
                vb.cgSpec.addView(chip)
            }
            if (selectSpec == null && vb.cgSpec.checkedChipId == -1 && vb.cgSpec.childCount > 0) {
                (vb.cgSpec.getChildAt(0) as Chip).isChecked = true
            }
            vb.etSpecCustom.text?.clear()
            if (selectSpec != null) {
                val inList = list.any { it.label == selectSpec }
                if (!inList) vb.etSpecCustom.setText(selectSpec.removeSuffix("mm²"))
            }
            refreshTotals()
        }

        fun rebuildColorChips(selectColor: String? = presetColor) {
            vb.cgColor.removeAllViews()
            colors.forEach { c ->
                val chip = Chip(context).apply {
                    text = c.label
                    isCheckable = true
                    isCheckedIconVisible = false
                    textSize = 13f
                    chipBackgroundColor = ColorStateList.valueOf(WireColor.of(c.label))
                    setTextColor(
                        ColorStateList.valueOf(
                            if (WireColor.of(c.label) == Color.WHITE) Color.parseColor("#37474F") else Color.WHITE
                        )
                    )
                    chipStrokeWidth = 3f
                    chipStrokeColor = selectedStroke(
                        if (WireColor.of(c.label) == Color.WHITE) context.getColor(R.color.primary)
                        else Color.WHITE
                    )
                }
                chip.setOnClickListener { refreshTotals() }
                chip.setOnCheckedChangeListener { cc, checked ->
                    cc.typeface = if (checked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                if (c.label == selectColor) chip.isChecked = true
                vb.cgColor.addView(chip)
            }
            if (selectColor == null && vb.cgColor.checkedChipId == -1 && vb.cgColor.childCount > 0) {
                (vb.cgColor.getChildAt(0) as Chip).isChecked = true
            }
            refreshTotals()
        }

        /** 回填表单用于修改已暂存的一笔 */
        fun fillForm(s: Staged) {
            kind = s.kind
            if (!appendMode) {
                vb.chipKindWire.isChecked = s.kind == Kind.WIRE
                vb.chipKindBusbar.isChecked = s.kind == Kind.BUSBAR
                rebuildSpecChips(s.spec)
                rebuildColorChips(s.color)
            }
            vb.etLength.setText(s.lengths.joinToString("\n") { (it / unit.toMm).toString() })
            vb.etNote.setText(s.note)
            refreshTotals()
        }

        fun renderStaging() {
            vb.llStaging.removeAllViews()
            staged.forEachIndexed { idx, s ->
                val row = ItemStagedBinding.inflate(LayoutInflater.from(context), vb.llStaging, false)
                row.tvStagedTitle.text = if (s.kind == Kind.WIRE) "${specLabel(s.spec)} ${s.color}" else "${s.spec} 铜排"
                val notePart = if (s.note.isEmpty()) "" else " · ${s.note}"
                row.tvStagedSub.text = "${s.lengths.size}根 · 共 ${unitText(s.lengths.sum())}$notePart"
                row.btnStagedEdit.setOnClickListener {
                    staged.removeAt(idx)
                    renderStaging()
                    fillForm(s)
                }
                row.btnStagedDelete.setOnClickListener {
                    staged.removeAt(idx)
                    renderStaging()
                }
                vb.llStaging.addView(row.root)
            }
            val hasItems = staged.isNotEmpty()
            vb.tvStagingLabel.visibility = if (hasItems) View.VISIBLE else View.GONE
            vb.llStaging.visibility = if (hasItems) View.VISIBLE else View.GONE
            vb.btnStageSubmit.text = if (hasItems) "全部入库（${staged.size} 项）" else "全部入库"
        }

        // ---------- 追加模式：隐藏规格/颜色选择 ----------

        if (appendMode) {
            vb.tvAppendSummary.visibility = View.VISIBLE
            vb.tvAppendSummary.text = if (kind == Kind.WIRE)
                "追加「${specLabel(targetSpec)} $targetColor」"
            else "追加「$targetSpec 铜排」"
            vb.tvKindLabel.visibility = View.GONE
            vb.cgKind.visibility = View.GONE
            vb.tvSpecLabel.visibility = View.GONE
            vb.cgSpec.visibility = View.GONE
            vb.etSpecCustom.visibility = View.GONE
            vb.tvColorLabel.visibility = View.GONE
            vb.cgColor.visibility = View.GONE
        } else {
            vb.chipKindWire.isChecked = kind == Kind.WIRE
            vb.chipKindBusbar.isChecked = kind == Kind.BUSBAR
            vb.tvSpecLabel.text = if (kind == Kind.BUSBAR) "规格（宽×厚，单位 mm）" else "规格（截面 mm²）"
        }

        vb.tvLenLabel.text = "每根长度（${unit.label} ${unit.code}，每行一根，可用 85×5 表示 5 根）"
        vb.etLength.hint = "每行填一根（单位 ${unit.label}，可一次多行），例如：\n85\n32×5（=5根32）"

        vb.cgKind.setOnCheckedStateChangeListener { _, _ ->
            kind = if (vb.chipKindBusbar.isChecked) Kind.BUSBAR else Kind.WIRE
            vb.tvSpecLabel.text = if (kind == Kind.BUSBAR) "规格（宽×厚，单位 mm）" else "规格（截面 mm²）"
            rebuildSpecChips()
            rebuildColorChips()
        }
        vb.etSpecCustom.addTextChangedListener(textWatcher { refreshTotals() })
        vb.etLength.addTextChangedListener(textWatcher { refreshTotals() })

        if (!appendMode) {
            rebuildSpecChips()
            rebuildColorChips()
        }
        refreshTotals()

        // ---------- 弹窗 ----------

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (appendMode) "追加长度" else "记一笔 · 截一根记一根")
            .setView(vb.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .show()

        // 保存 = 直接提交当前表单（等价“入库”当前这一笔）
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val s = currentStaged()
            if (s != null) {
                onSaved(s.kind, s.spec, s.color, s.lengths, s.note)
                dialog.dismiss()
            }
        }

        // 添加到批量列表
        vb.tvStageAddBtn.setOnClickListener {
            val s = currentStaged()
            if (s != null) {
                staged.add(s)
                renderStaging()
                vb.etLength.text?.clear()
                vb.etNote.text?.clear()
                refreshTotals()
            }
        }
        vb.btnStageSubmit.setOnClickListener {
            if (staged.isEmpty()) {
                // 未使用批量时，直接提交当前表单
                val s = currentStaged()
                if (s != null) {
                    onSaved(s.kind, s.spec, s.color, s.lengths, s.note)
                    dialog.dismiss()
                }
                return@setOnClickListener
            }
            staged.forEach { s ->
                onSaved(s.kind, s.spec, s.color, s.lengths, s.note)
            }
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