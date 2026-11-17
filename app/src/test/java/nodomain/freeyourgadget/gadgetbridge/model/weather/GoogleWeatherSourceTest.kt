package nodomain.freeyourgadget.gadgetbridge.model.weather

import nodomain.freeyourgadget.gadgetbridge.proto.googleweather.GoogleWeatherCache as Cache
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GoogleWeatherSourceTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z").epochSecond

    @Test
    fun unitsAreReadFromPayloadIncludingFahrenheitInCelsiusLocale() {
        assertEquals(289, GoogleWeatherSource.kelvin(temperature(61f, 1)))
        assertEquals(289, GoogleWeatherSource.kelvin(temperature(16f, 2)))
        assertEquals(289, GoogleWeatherSource.kelvin(temperature(289f, 3)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTemperatureIsRejected() {
        GoogleWeatherSource.kelvin(temperature(Float.NaN, 2))
    }

    @Test
    fun currentLocationAndDailyHourlyForecastAreConverted() {
        val data = response()
        val output = "W|${hex(data.toByteArray())}"
        val spec = GoogleWeatherSource.parse(output, now)
        assertEquals(289, spec.currentTemp)
        assertEquals(72, spec.currentHumidity)
        assertEquals(802, spec.currentConditionCode)
        assertEquals(293, spec.todayMaxTemp)
        assertEquals(283, spec.todayMinTemp)
        assertEquals(1, spec.forecasts.size)
        assertEquals(1, spec.hourly.size)
        assertEquals(1, spec.isCurrentLocation)
        assertEquals(9.656f, spec.windSpeed, 0.001f)
    }

    @Test
    fun anonymizedInstalledAppCacheUsesVerifiedWireFields() {
        val hex = javaClass.getResource("/google-weather-cache.hex")!!.readText().trim()
        val spec = GoogleWeatherSource.parse("W|$hex", 1794877753)
        assertEquals("Example City", spec.location)
        assertEquals(289, spec.currentTemp)
        assertEquals(291, spec.todayMaxTemp)
        assertEquals(285, spec.todayMinTemp)
        assertEquals(72, spec.currentHumidity)
        assertEquals(802, spec.currentConditionCode)
        assertEquals(1021.72f, spec.pressure, 0.01f)
        assertEquals(6, spec.forecasts.size)
        assertEquals(24, spec.hourly.size)
        assertTrue(spec.sunRise > 0)
        assertTrue(spec.sunSet > spec.sunRise)
    }

    @Test
    fun currentLocationCityNameComesFromNearbyAddressCache() {
        val request = Cache.AddressRequest.newBuilder().setCoordinates(
            Cache.Coordinates.newBuilder().setLatitude(51f).setLongitude(0f)).build()
        val address = Cache.Address.newBuilder().setCity("Example City").setDistrict("Centre").build()
        val output = "W|${hex(response().toByteArray())}\nA|${hex(request.toByteArray())}|${hex(address.toByteArray())}"
        assertEquals("Example City Centre", GoogleWeatherSource.parse(output, now).location)
    }

    @Test(expected = java.io.IOException::class)
    fun staleCacheIsRejected() {
        GoogleWeatherSource.parse("W|${hex(response().toByteArray())}", now + 7 * 3600)
    }

    @Test
    fun conditionCodesCoverCommonWeather() {
        assertEquals(800, GoogleWeatherSource.conditionCode(138))
        assertEquals(804, GoogleWeatherSource.conditionCode(47))
        assertEquals(500, GoogleWeatherSource.conditionCode(162))
        assertEquals(602, GoogleWeatherSource.conditionCode(88))
        assertEquals(200, GoogleWeatherSource.conditionCode(139))
    }

    @Test
    fun rootQueryIsReadOnlyAndScopedToCurrentAndroidUser() {
        val command = GoogleWeatherSource.command(12)
        assertTrue(command.contains("/data/user/12/com.google.android.apps.weather/cache"))
        assertTrue(command.contains("-readonly"))
        assertFalse(command.contains("UPDATE "))
        assertFalse(command.contains("INSERT "))
    }

    private fun response(): Cache.Response {
        val location = Cache.Location.newBuilder().setSemanticType(1).setTimezone("UTC")
            .setCoordinates(Cache.Coordinates.newBuilder().setLatitude(51f).setLongitude(0f))
        val current = Cache.Current.newBuilder().setTimestamp(timestamp(now))
            .setTemperature(temperature(61f, 1)).setHumidity(72)
            .setCondition(Cache.Condition.newBuilder().setCode(93).setDescription("Partly sunny"))
            .setWind(Cache.Wind.newBuilder().setDirection(353).setSpeed(
                Cache.Measurement.newBuilder().setValue(6f).setUnit(3)))
        val today = Cache.ForecastEntry.newBuilder().setTimestamp(timestamp(now))
            .setDailyTemperature(Cache.DailyTemperature.newBuilder().setRange(
                Cache.TemperatureRange.newBuilder().setHigh(temperature(20f, 2)).setLow(temperature(10f, 2))))
        val tomorrow = today.clone().setTimestamp(timestamp(now + 86400))
        val hourly = Cache.ForecastEntry.newBuilder().setTimestamp(timestamp(now + 3600))
            .setHourlyTemperature(Cache.HourlyTemperature.newBuilder().setTemperature(temperature(62f, 1)))
        return Cache.Response.newBuilder().setLocation(location).setCurrent(current)
            .setDaily(Cache.Forecast.newBuilder().addEntry(today).addEntry(tomorrow))
            .setHourly(Cache.Forecast.newBuilder().addEntry(hourly)).build()
    }

    private fun temperature(value: Float, unit: Int) = Cache.Temperature.newBuilder().setValue(value).setUnit(unit).build()
    private fun timestamp(seconds: Long) = Cache.Timestamp.newBuilder().setSeconds(seconds).build()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
