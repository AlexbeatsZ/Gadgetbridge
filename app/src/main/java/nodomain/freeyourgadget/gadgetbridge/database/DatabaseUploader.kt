/*  This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.database

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads an exported database snapshot to a personal server after the periodic DB export.
 *
 * Wire format: `POST <url>`, `Authorization: Bearer <token>`, `Content-Encoding: gzip`,
 * body = gzip of the SQLite file, `X-Gadgetbridge-Db-Bytes` = uncompressed size.
 * The server must answer 2xx with `{"ok": true, ...}` only after the file is stored.
 */
object DatabaseUploader {
    const val PREF_ENABLED = "auto_export_upload_enabled"
    const val PREF_URL = "auto_export_upload_url"
    const val PREF_TOKEN = "auto_export_upload_token"
    const val PREF_LAST_SUCCESS = "auto_export_upload_last_success"
    const val PREF_LAST_ERROR = "auto_export_upload_last_error"
    const val PREF_STATUS = "auto_export_upload_status"

    /** ROG over Tailscale (tailnet-only `tailscale serve` port forwarding to the webhook receiver). */
    const val DEFAULT_URL = "http://100.106.169.46:8789/gadgetbridge"

    private val SQLITE: MediaType = "application/x-sqlite3".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Throws(IOException::class)
    fun upload(file: File, url: String, token: String): String {
        if (token.isBlank()) throw IOException("Upload token not set")
        val request = Request.Builder()
            .url(url.trim())
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Content-Encoding", "gzip")
            .header("X-Gadgetbridge-Db-Bytes", file.length().toString())
            .post(GzipFileBody(file))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string().take(4096)
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $body")
            }
            val ok = try { JSONObject(body).optBoolean("ok", false) } catch (_: Exception) { false }
            if (!ok) throw IOException("Unexpected receipt: $body")
            return body
        }
    }

    private class GzipFileBody(private val file: File) : RequestBody() {
        override fun contentType(): MediaType = SQLITE
        override fun contentLength(): Long = -1L
        override fun writeTo(sink: BufferedSink) {
            val gzip = GzipSink(sink).buffer()
            file.source().use { gzip.writeAll(it) }
            gzip.close()
        }
    }
}
