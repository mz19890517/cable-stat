package com.cablestat.record.util

import android.content.Context

/** 默认长度单位（仅录入换算用；记录库一律以毫米 lengthMm 存储） */
enum class LengthUnit(val code: String, val label: String, val toMm: Long) {
    MM("mm", "毫米", 1L),
    CM("cm", "公分", 10L),
    M("m", "米", 1000L);

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "length_unit"

        fun current(context: Context): LengthUnit {
            val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, MM.code) ?: MM.code
            return entries.firstOrNull { it.code == code } ?: MM
        }

        fun save(context: Context, unit: LengthUnit) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, unit.code).apply()
        }
    }
}