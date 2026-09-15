package com.cablestat.record.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cablestat.record.databinding.ActivitySettingsBinding
import com.cablestat.record.util.LengthUnit

/** 设置：默认长度单位、候选池入口 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowCandidate.setOnClickListener {
            startActivity(Intent(this, CandidateManagerActivity::class.java))
        }

        val current = LengthUnit.current(this)
        when (current) {
            LengthUnit.MM -> binding.rbMm.isChecked = true
            LengthUnit.CM -> binding.rbCm.isChecked = true
            LengthUnit.M -> binding.rbM.isChecked = true
        }
        binding.rgUnit.setOnCheckedChangeListener { _, checkedId ->
            val unit = when (checkedId) {
                binding.rbCm.id -> LengthUnit.CM
                binding.rbM.id -> LengthUnit.M
                else -> LengthUnit.MM
            }
            if (unit != current) LengthUnit.save(this, unit)
        }
    }
}