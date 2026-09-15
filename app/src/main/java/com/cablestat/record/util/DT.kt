package com.cablestat.record.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日期时间格式化工具（线程安全） */
object DT {
    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val ymdHm = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val ymdHms = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun date(t: Long): String = ymd.format(Date(t))
    fun dateTime(t: Long): String = ymdHm.format(Date(t))
    fun dateTimeSec(t: Long): String = ymdHms.format(Date(t))
}