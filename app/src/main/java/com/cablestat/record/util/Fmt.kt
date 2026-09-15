package com.cablestat.record.util

/** 长度单位换算与展示 */
object Fmt {
    /** 毫米 → 米，如 1850 → "1.850" */
    fun meters(mm: Long): Double = mm / 1000.0

    /** 长度展示：主单位毫米 + 括号米，如 "1850mm（1.85m）" */
    fun length(mm: Long): String {
        return "${mm}mm（${meterText(mm)}m）"
    }

    /** 米文本：整米不带小数，否则保留3位并去尾零 */
    fun meterText(mm: Long): String {
        val m = mm / 1000.0
        return if (m == Math.floor(m)) m.toLong().toString() else trimZero(m)
    }

    private fun trimZero(v: Double): String {
        var s = String.format("%.3f", v)
        while (s.endsWith("0")) s = s.dropLast(1)
        if (s.endsWith(".")) s = s.dropLast(1)
        return s
    }
}