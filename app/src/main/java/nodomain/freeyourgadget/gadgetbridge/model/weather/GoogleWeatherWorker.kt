package nodomain.freeyourgadget.gadgetbridge.model.weather

import android.content.Context
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class GoogleWeatherWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!GBApplication.getPrefs().getBoolean(GoogleWeatherSource.PREF_ENABLED, false)) {
            return Result.success()
        }
        try {
            val spec = GoogleWeatherSource.read()
            // A setting change while root access was pending cancels this result too.
            if (!GBApplication.getPrefs().getBoolean(GoogleWeatherSource.PREF_ENABLED, false)) {
                return Result.success()
            }
            Weather.setWeatherSpec(listOf(spec))
            if (DeviceCommunicationService.isRunning(applicationContext)) {
                GBApplication.deviceService().onSendWeather()
            }
            LOG.info("Imported Google Weather cache, timestamp={}, daily={}, hourly={}",
                spec.timestamp, spec.forecasts.size, spec.hourly.size)
            if (inputData.getBoolean("manual", false)) {
                GB.toast(applicationContext.getString(R.string.weather_google_root_success), Toast.LENGTH_LONG, GB.INFO)
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Google Weather cache import failed", e)
            if (inputData.getBoolean("manual", false)) {
                GB.toast(applicationContext.getString(R.string.weather_google_root_failure), Toast.LENGTH_LONG, GB.ERROR)
            }
            return Result.failure()
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(GoogleWeatherWorker::class.java)
        private const val PERIODIC_WORK = "google_weather_cache_periodic"
        private const val MANUAL_WORK = "google_weather_cache_manual"

        @JvmStatic
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (enabled) {
                manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<GoogleWeatherWorker>(30, TimeUnit.MINUTES).build())
            } else {
                manager.cancelUniqueWork(PERIODIC_WORK)
                manager.cancelUniqueWork(MANUAL_WORK)
            }
        }

        @JvmStatic
        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GoogleWeatherWorker>()
                    .setInputData(workDataOf("manual" to true)).build())
        }
    }
}
