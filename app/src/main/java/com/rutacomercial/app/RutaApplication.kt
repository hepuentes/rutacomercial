package com.rutacomercial.app

import android.app.Application
import com.rutacomercial.app.data.AppDatabase
import com.rutacomercial.app.data.Repository
import com.rutacomercial.app.maps.MapBootstrap
import com.rutacomercial.app.tracking.TrackingDependencies

class RutaApplication : Application(), TrackingDependencies {
    internal val cloud by lazy { com.rutacomercial.app.ui.CloudSync(this) }
    override val repository: Repository by lazy { Repository(AppDatabase.create(this)) }
    override fun onCreate() {
        super.onCreate()
        MapBootstrap.initialize(this)
        cloud.start()
        if (!BuildConfig.WORKER_EDITION && cloud.enabled) com.rutacomercial.app.ui.DaySummaryReceiver.schedule(this)
    }
}
