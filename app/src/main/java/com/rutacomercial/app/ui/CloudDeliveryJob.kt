package com.rutacomercial.app.ui

import android.app.job.JobParameters
import android.app.job.JobService
import com.rutacomercial.app.RutaApplication
import kotlinx.coroutines.*

class CloudDeliveryJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var delivery: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        delivery = scope.launch {
            val retry = try {
                withContext(Dispatchers.IO) { (application as RutaApplication).cloud.flushPending() }
                false
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { true }
            jobFinished(params,retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        delivery?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
