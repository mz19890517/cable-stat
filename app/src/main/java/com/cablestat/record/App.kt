package com.cablestat.record

import android.app.Application
import com.cablestat.record.data.Repository
import com.cablestat.record.data.db.AppDatabase
import com.cablestat.record.util.CrashLog

class App : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: Repository by lazy { Repository(database) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}