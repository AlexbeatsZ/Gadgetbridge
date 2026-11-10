package nodomain.freeyourgadget.gadgetbridge.devices.xiaomi

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.R
import java.io.IOException

internal object MiFitnessAuthKeyImporter {
    private val bluetoothAddress = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
    private val hexKey = Regex("[0-9a-f]{32}")

    sealed interface Result {
        class Found(val key: String) : Result

        enum class Failure(val message: Int) : Result {
            ROOT(R.string.auth_key_mi_fitness_root_required),
            SQLITE(R.string.auth_key_mi_fitness_sqlite_missing),
            NO_DATABASE(R.string.auth_key_mi_fitness_no_database),
            READ(R.string.auth_key_mi_fitness_read_error),
            NO_MATCH(R.string.auth_key_mi_fitness_no_match),
            AMBIGUOUS(R.string.auth_key_mi_fitness_ambiguous),
        }
    }

    suspend fun read(address: String): Result {
        if (!bluetoothAddress.matches(address)) return Result.Failure.NO_MATCH
        val mac = address.replace(":", "").lowercase()
        // Android encodes the profile ID in the high digits of every app UID.
        val userId = Process.myUid() / 100_000
        return withTimeoutOrNull(45_000) {
            runInterruptible(Dispatchers.IO) { readDatabase(userId, mac) }
        } ?: Result.Failure.ROOT
    }

    private fun readDatabase(userId: Int, mac: String): Result {
        val process = try {
            ProcessBuilder("su", "-c", command(userId, mac)).start()
        } catch (_: IOException) {
            return Result.Failure.ROOT
        }
        try {
            return when (process.waitFor()) {
                0 -> {
                    val keys = process.inputStream.bufferedReader().useLines { lines ->
                        lines.filter(hexKey::matches).distinct().take(2).toList()
                    }
                    when (keys.size) {
                        0 -> Result.Failure.NO_MATCH
                        1 -> Result.Found("0x" + keys.single())
                        else -> Result.Failure.AMBIGUOUS
                    }
                }
                11 -> Result.Failure.SQLITE
                12 -> Result.Failure.NO_DATABASE
                13 -> Result.Failure.READ
                else -> Result.Failure.ROOT
            }
        } catch (_: IOException) {
            return Result.Failure.READ
        } finally {
            process.destroy()
        }
    }

    private fun command(userId: Int, mac: String): String {
        // The validated MAC and numeric user ID are the only interpolated inputs.
        // Query on-device, so only matching keys cross the root process boundary.
        val query = """
            WITH records AS (
                SELECT CASE WHEN json_valid(detail) THEN detail END AS details
                FROM device
            ), matching AS (
                SELECT lower(trim(json_extract(details, '$.token'))) AS token,
                       lower(trim(json_extract(details, '$.encrypt_key'))) AS encrypt_key
                FROM records
                WHERE lower(replace(replace(json_extract(details, '$.mac'), ':', ''), '-', '')) = '$mac'
            ), normalized AS (
                SELECT CASE WHEN substr(token, 1, 2) = '0x' THEN substr(token, 3) ELSE token END AS token,
                       CASE WHEN substr(encrypt_key, 1, 2) = '0x' THEN substr(encrypt_key, 3)
                            ELSE encrypt_key END AS encrypt_key
                FROM matching
            ), keys AS (
                SELECT CASE
                    WHEN length(token) = 32 AND token NOT GLOB '*[^0-9a-f]*' THEN token
                    WHEN length(encrypt_key) = 32 AND encrypt_key NOT GLOB '*[^0-9a-f]*' THEN encrypt_key
                END AS key
                FROM normalized
            )
            SELECT DISTINCT key FROM keys WHERE key IS NOT NULL LIMIT 2;
        """.trimIndent()
        return $$"""
            [ "$(/system/bin/id -u)" = 0 ] || exit 1
            sqlite=/system/bin/sqlite3
            [ -x "$sqlite" ] || exit 11
            found=0
            read_ok=0
            for package in com.mi.health com.xiaomi.wearable; do
                db="/data/user/$$userId/$package/databases/device_db"
                [ -f "$db" ] || continue
                found=1
                if "$sqlite" -batch -noheader -list -readonly -init /dev/null "$db" "$$query" 2>/dev/null; then
                    read_ok=1
                fi
            done
            [ "$found" = 1 ] || exit 12
            [ "$read_ok" = 1 ] || exit 13
        """.trimIndent()
    }
}
