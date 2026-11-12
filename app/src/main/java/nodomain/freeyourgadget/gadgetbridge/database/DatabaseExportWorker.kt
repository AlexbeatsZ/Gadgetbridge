package nodomain.freeyourgadget.gadgetbridge.database

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.GBDatabaseManager
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

class DatabaseExportWorker(
    private val mContext: Context,
    workerParams: WorkerParameters
) : Worker(mContext, workerParams) {
    override fun doWork(): Result {
        val enabled = GBApplication.getPrefs().getBoolean(GBPrefs.AUTO_EXPORT_DB_ENABLED, false)
        if (!enabled) {
            LOG.warn("DB export started, but is disabled")
            // Should not need i18n, this should never happen
            GB.updateExportFailedNotification("DB export started, but is disabled", mContext)
            return Result.failure()
        }

        val prefs = GBApplication.getPrefs()
        val dst = prefs.getString(GBPrefs.AUTO_EXPORT_DB_LOCATION, "") ?: ""
        val upload = prefs.getBoolean(DatabaseUploader.PREF_ENABLED, false)

        LOG.info("Starting DB export, dst={}, upload={}", dst, upload)

        if (dst.isEmpty() && !upload) {
            LOG.warn("Unable to export DB, export location not set")
            GB.updateExportFailedNotification(mContext.getString(R.string.notif_export_location_not_set), mContext)
            broadcastSuccess(false)
            return Result.failure()
        }

        // When uploading, export once to a private snapshot and copy it to the SAF location,
        // so the upload does not need read access to the user-picked document.
        val snapshot = File(mContext.cacheDir, "auto_export_upload.db")
        try {
            if (upload) {
                GBDatabaseManager.exportDB(snapshot)
                if (dst.isNotEmpty()) {
                    FileUtils.copyFileToURI(mContext, snapshot, dst.toUri())
                }
            } else {
                mContext.contentResolver.openOutputStream(dst.toUri()).use { out ->
                    GBDatabaseManager.exportDB(out)
                }
            }

            prefs.preferences.edit {
                putLong(GBPrefs.AUTO_EXPORT_DB_LAST_EXECUTION, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            snapshot.delete()
            GB.updateExportFailedNotification(mContext.getString(R.string.notif_export_failed_title), mContext)
            LOG.error("Exception while exporting DB", e)
            broadcastSuccess(false)
            return Result.failure()
        }

        LOG.info("DB export completed")

        if (upload) {
            try {
                val url = prefs.getString(DatabaseUploader.PREF_URL, DatabaseUploader.DEFAULT_URL)
                    ?.takeIf { it.isNotBlank() } ?: DatabaseUploader.DEFAULT_URL
                val receipt = DatabaseUploader.upload(
                    snapshot,
                    url,
                    prefs.getString(DatabaseUploader.PREF_TOKEN, "") ?: ""
                )
                LOG.info("DB upload completed: {}", receipt)
                prefs.preferences.edit {
                    putLong(DatabaseUploader.PREF_LAST_SUCCESS, System.currentTimeMillis())
                    remove(DatabaseUploader.PREF_LAST_ERROR)
                }
            } catch (e: Exception) {
                LOG.error("DB upload failed (attempt {})", runAttemptCount, e)
                prefs.preferences.edit {
                    putString(
                        DatabaseUploader.PREF_LAST_ERROR,
                        "${System.currentTimeMillis()}|${e.message ?: e.javaClass.simpleName}"
                    )
                }
                broadcastSuccess(false)
                // Network may be temporarily unavailable (VPN restarting, no tailnet): let
                // WorkManager retry with backoff a few times before waiting for the next period.
                if (runAttemptCount < MAX_UPLOAD_RETRIES) {
                    return Result.retry()
                }
                GB.updateExportFailedNotification(mContext.getString(R.string.notif_export_upload_failed_title), mContext)
                return Result.failure()
            } finally {
                snapshot.delete()
            }
        }

        broadcastSuccess(true)

        return Result.success()
    }

    private fun broadcastSuccess(success: Boolean) {
        if (!GBApplication.getPrefs().getBoolean(GBPrefs.INTENT_API_BROADCAST_EXPORT_DB, false)) {
            return
        }

        LOG.info("Broadcasting database export success={}", success)

        val action: String = if (success) ACTION_DATABASE_EXPORT_SUCCESS else ACTION_DATABASE_EXPORT_FAIL
        val exportedNotifyIntent = Intent(action)
        mContext.sendBroadcast(exportedNotifyIntent)
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(DatabaseExportWorker::class.java)

        private const val MAX_UPLOAD_RETRIES = 4

        const val ACTION_DATABASE_EXPORT_SUCCESS: String =
            "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_SUCCESS"
        const val ACTION_DATABASE_EXPORT_FAIL: String =
            "nodomain.freeyourgadget.gadgetbridge.action.DATABASE_EXPORT_FAIL"
    }
}
