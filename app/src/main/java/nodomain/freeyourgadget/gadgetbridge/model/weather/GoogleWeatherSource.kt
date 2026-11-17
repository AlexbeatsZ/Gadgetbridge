package nodomain.freeyourgadget.gadgetbridge.model.weather

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec
import nodomain.freeyourgadget.gadgetbridge.proto.googleweather.GoogleWeatherCache
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Reads the installed Google Weather app's cache. All database access is read-only. */
internal object GoogleWeatherSource {
    const val PREF_ENABLED = "weather_google_root_enabled"
    private const val MAX_AGE_SECONDS = 6 * 60 * 60L

    suspend fun read(): WeatherSpec = try {
        withTimeout(45_000) {
            val userId = Process.myUid() / 100_000
            val first = query("-c", command(userId))
            val output = if (first.first == 12) {
                query("--mount-master", "-c", command(userId))
            } else first
            check(output.first == 0) { "Google Weather cache unavailable (root exit ${output.first})" }
            parse(output.second, Instant.now().epochSecond)
        }
    } catch (e: TimeoutCancellationException) {
        throw IOException("Google Weather root access timed out", e)
    }

    private suspend fun query(vararg arguments: String): Pair<Int, String> = coroutineScope {
        val process = ProcessBuilder("su", *arguments).redirectErrorStream(true).start()
        val output = async(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val result = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(result.length + count <= 8 * 1024 * 1024) { "Weather cache exceeds size limit" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        }
        try {
            val exitCode = runInterruptible(Dispatchers.IO) { process.waitFor() }
            exitCode to output.await()
        } finally {
            process.destroy()
            output.cancel()
        }
    }

    internal fun command(userId: Int): String = $$"""
        [ "$(/system/bin/id -u)" = 0 ] || exit 1
        sqlite=/system/bin/sqlite3
        [ -x "$sqlite" ] || exit 11
        cache=/data/user/$$userId/com.google.android.apps.weather/cache
        weather="$cache/SqliteKeyValueCache:NoAccountEssentialsWeatherCache:Singleton.db"
        [ -f "$weather" ] || exit 12
        "$sqlite" -batch -noheader -readonly -init /dev/null "$weather" \
          "SELECT 'W|' || hex(response_data) FROM cache_table WHERE invalid_flag=0 AND length(response_data)<524288 ORDER BY write_ms DESC LIMIT 12;" || exit 13
        address="$cache/SqliteKeyValueCache:LocalAddressCache:Singleton.db"
        if [ -f "$address" ]; then
            "$sqlite" -batch -noheader -readonly -init /dev/null "$address" \
              "SELECT 'A|' || hex(request_data) || '|' || hex(response_data) FROM cache_table WHERE invalid_flag=0 AND length(response_data)<4096 ORDER BY write_ms DESC LIMIT 40;"
        fi
        exit 0
    """.trimIndent()

    internal fun parse(output: String, now: Long): WeatherSpec {
        val responses = mutableListOf<GoogleWeatherCache.Response>()
        val addresses = mutableListOf<Pair<GoogleWeatherCache.Coordinates, GoogleWeatherCache.Address>>()
        output.lineSequence().forEach { line ->
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "W" -> if (parts.size == 2) {
                    runCatching { GoogleWeatherCache.Response.parseFrom(hexBytes(parts[1])) }
                        .getOrNull()?.let(responses::add)
                }
                "A" -> if (parts.size == 3) {
                    runCatching {
                        GoogleWeatherCache.AddressRequest.parseFrom(hexBytes(parts[1])).coordinates to
                            GoogleWeatherCache.Address.parseFrom(hexBytes(parts[2]))
                    }.getOrNull()?.let(addresses::add)
                }
            }
        }
        val fresh = responses.filter {
            it.hasCurrent() && it.current.hasTemperature() && it.current.hasTimestamp() &&
                it.current.hasCondition() && it.current.condition.code > 0 &&
                now - it.current.timestamp.seconds in -300..MAX_AGE_SECONDS
        }
        val response = fresh.firstOrNull { it.location.semanticType == 1 }
            ?: throw IOException("Open Google Weather to refresh its current-location weather data")
        val location = response.location
        val coordinates = location.coordinates
        val address = addresses.firstOrNull {
            abs(it.first.latitude - coordinates.latitude) < 0.03 &&
                abs(it.first.longitude - coordinates.longitude) < 0.03
        }?.second
        val name = listOf(location.city, location.district, location.name).filter(String::isNotBlank)
            .distinct().joinToString(" ").ifBlank {
                listOfNotNull(address?.city, address?.district).filter(String::isNotBlank)
                    .distinct().joinToString(" ")
            }.ifBlank { String.format(Locale.ROOT, "%.2f, %.2f", coordinates.latitude, coordinates.longitude) }
        return toWeatherSpec(response, name, now)
    }

    internal fun toWeatherSpec(response: GoogleWeatherCache.Response, name: String, now: Long): WeatherSpec {
        val current = response.current
        val timezone = ZoneId.of(response.location.timezone)
        val today = Instant.ofEpochSecond(now).atZone(timezone).toLocalDate()
        val days = response.daily.entryList.filter {
            it.hasDailyTemperature() && it.dailyTemperature.hasRange() && it.hasTimestamp()
        }.sortedBy { it.timestamp.seconds }
        val todayForecast = days.firstOrNull {
            Instant.ofEpochSecond(it.timestamp.seconds).atZone(timezone).toLocalDate() == today
        } ?: throw IOException("Google Weather daily forecast is unavailable")
        return WeatherSpec().apply {
            timestamp = current.timestamp.seconds.toInt()
            location = name
            latitude = response.location.coordinates.latitude
            longitude = response.location.coordinates.longitude
            isCurrentLocation = if (response.location.semanticType == 1) 1 else 0
            currentTemp = kelvin(current.temperature)
            feelsLikeTemp = if (current.hasFeelsLike()) kelvin(current.feelsLike) else currentTemp
            currentConditionCode = conditionCode(current.condition.code)
            currentCondition = current.condition.description
            currentHumidity = current.humidity
            windSpeed = windKph(current.wind.speed)
            windDirection = current.wind.direction
            uvIndex = current.uv.value
            pressure = when (current.pressure.unit) {
                1 -> current.pressure.value * 33.86389f
                2, 3 -> current.pressure.value
                4 -> current.pressure.value * 1.333224f
                else -> 0f
            }
            if (current.hasDewPoint()) dewPoint = kelvin(current.dewPoint)
            precipProbability = current.precipitation.probability
            todayMaxTemp = kelvin(todayForecast.dailyTemperature.range.high)
            todayMinTemp = kelvin(todayForecast.dailyTemperature.range.low)
            sunRise = solarTime(todayForecast.solar, 1)
            sunSet = solarTime(todayForecast.solar, 2)
            forecasts = ArrayList(days.filter {
                Instant.ofEpochSecond(it.timestamp.seconds).atZone(timezone).toLocalDate().isAfter(today)
            }.take(6).map { entry ->
                WeatherSpec.Daily().apply {
                    minTemp = kelvin(entry.dailyTemperature.range.low)
                    maxTemp = kelvin(entry.dailyTemperature.range.high)
                    conditionCode = conditionCode(entry.condition.code)
                    humidity = entry.humidity
                    windSpeed = windKph(entry.wind.speed)
                    windDirection = entry.wind.direction
                    uvIndex = entry.uv.value
                    precipProbability = entry.precipitation.probability
                    sunRise = solarTime(entry.solar, 1)
                    sunSet = solarTime(entry.solar, 2)
                }
            })
            hourly = ArrayList(response.hourly.entryList.filter {
                it.hasHourlyTemperature() && it.timestamp.seconds >= current.timestamp.seconds - 3600
            }.sortedBy { it.timestamp.seconds }.take(24).map { entry ->
                WeatherSpec.Hourly().apply {
                    timestamp = entry.timestamp.seconds.toInt()
                    temp = kelvin(entry.hourlyTemperature.temperature)
                    conditionCode = conditionCode(entry.condition.code)
                    humidity = entry.humidity
                    windSpeed = windKph(entry.wind.speed)
                    windDirection = entry.wind.direction
                    uvIndex = entry.uv.value
                    precipProbability = entry.precipitation.probability
                }
            })
        }
    }

    private fun solarTime(events: GoogleWeatherCache.SolarEvents, type: Int): Int =
        events.eventList.firstOrNull { it.type == type }?.timestamp?.seconds?.toInt() ?: 0

    internal fun kelvin(temperature: GoogleWeatherCache.Temperature): Int {
        val value = temperature.value
        require(value.isFinite()) { "Invalid temperature" }
        val kelvin = when (temperature.unit) {
            1 -> (value - 32) * 5 / 9 + 273.15f
            2 -> value + 273.15f
            3 -> value
            else -> throw IOException("Unknown Google Weather temperature unit")
        }
        require(kelvin in 150f..350f) { "Invalid temperature range" }
        return kelvin.roundToInt()
    }

    private fun windKph(speed: GoogleWeatherCache.Measurement): Float = when (speed.unit) {
        1 -> speed.value * 3.6f
        2 -> speed.value
        3 -> speed.value * 1.609344f
        4 -> speed.value * 1.852f
        else -> 0f
    }

    internal fun conditionCode(code: Int): Int = when (code) {
        140 -> 903
        141 -> 904
        182 -> 781
        190, 201, 226, 261, 263, 268 -> 741
        195, 228 -> 721
        200, 234 -> 701
        217, 253, 286 -> 762
        267 -> 802
        9, 137, 139, 144, 163, 211, 212, 213, 214, 215, 216, 247, 248, 249, 250,
        251, 252, 281, 282, 283, 284, 285, 292, 295 -> 200
        176, 188, 224 -> 300
        162 -> 500
        5, 6, 94, 98, 99, 100, 101, 102, 103, 105, 106, 109, 113, 116, 146,
        153, 178, 204, 205, 210, 238, 240, 241, 246, 269, 271, 272, 278, 288, 293 -> 501
        87, 147, 154, 155 -> 502
        3, 84, 148 -> 600
        2, 7, 8, 117, 121, 124, 125, 127, 128, 129, 130, 149, 156, 186, 199,
        206, 222, 232, 242, 256, 273, 294 -> 601
        1, 88, 89, 150, 157, 158 -> 602
        4, 90, 112, 122, 135, 145, 151, 179, 180, 181, 184, 191, 192, 193, 196,
        197, 219, 220, 229, 230, 239, 260, 264, 265, 270, 290 -> 611
        95, 96, 97, 104, 107, 108, 110, 111, 114, 115, 118, 119, 120, 123, 126,
        131, 132, 133, 134, 136, 209, 245, 277, 289 -> 616
        258 -> 701
        208, 243, 275 -> 711
        86 -> 721
        85 -> 741
        160, 161, 165, 185, 198, 202, 203, 221, 231, 236, 237, 255 -> 751
        152, 159, 173, 177, 187, 189, 218, 223, 225, 233, 254, 257, 259, 287 -> 761
        10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
        25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39,
        40, 41, 42, 43, 44, 45, 46, 138 -> 800
        92 -> 801
        93, 262 -> 802
        91 -> 803
        47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61,
        62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76,
        77, 78, 79, 80, 81, 82, 83, 142, 143, 174 -> 804
        279, 291 -> 905
        183, 194, 207, 227, 274 -> 906
        else -> 0
    }

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.length <= 1024 * 1024)
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
