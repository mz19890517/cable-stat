package com.cablestat.record.util

import android.content.Context
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File

/** 全局未捕获异常黑匣子：崩溃写本地文件，App.onCreate 安装 */
object CrashLog {
    private const val TAG = "CableStatCrash"
    private const val FILE_NAME = "crash.log"

    fun install(context: Context) {
        val dir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                append(dir, Log.getStackTraceString(throwable))
            } catch (_: Throwable) {
            }
            try {
                if (Looper.getMainLooper().thread === Thread.currentThread()) {
                    Toast.makeText(context, "发生异常：" + (throwable.message ?: "未知错误"), Toast.LENGTH_LONG).show()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun append(dir: String, stack: String) {
        File(dir, FILE_NAME).appendText("\n--- ${DT.dateTimeSec(System.currentTimeMillis())} ---\n$stack\n")
    }

    fun load(context: Context): String {
        val file = File(context.getExternalFilesDir(null)?.absolutePath ?: "", FILE_NAME)
        return if (file.exists()) file.readText() else "（暂无崩溃记录）"
    }

    fun clear(context: Context) {
        File(context.getExternalFilesDir(null)?.absolutePath ?: "", FILE_NAME).delete()
    }
}