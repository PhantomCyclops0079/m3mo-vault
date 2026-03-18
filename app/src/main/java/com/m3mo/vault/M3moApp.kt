package com.m3mo.vault

import android.app.Application
import androidx.work.Configuration
import com.m3mo.vault.di.ServiceLocator

class M3moApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ServiceLocator.startSubscribers()
        ServiceLocator.scheduleWorkers()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(ServiceLocator.workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}