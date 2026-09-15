package com.cablestat.record.util

import android.graphics.Color

/** 线缆颜色名称 → 显示色值（市面常用国标线色） */
object WireColor {
    private val map = mapOf(
        "红" to Color.parseColor("#E53935"),
        "黄" to Color.parseColor("#FDD835"),
        "绿" to Color.parseColor("#43A047"),
        "蓝" to Color.parseColor("#1E88E5"),
        "黄绿" to Color.parseColor("#82C91E"),
        "黑" to Color.parseColor("#212121"),
        "棕" to Color.parseColor("#795548"),
        "白" to Color.WHITE,
        "灰" to Color.parseColor("#9E9E9E")
    )

    fun of(name: String): Int = map[name] ?: Color.parseColor("#B0BEC5")
}