package dev.ad.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

data class WeatherDay(val date: LocalDate, val min: Int, val max: Int, val precipMm: Double, val symbol: String)

data class Weather(
    val temp: Int,
    val feels: Int,
    val wind: Int,
    val symbol: String,
    val today: WeatherDay,
    val next: List<WeatherDay>,
    val fetchedAt: Long,
)

/** Forecast from MET Norway (api.met.no), cached on disk so the home screen shows something instantly. */
object WeatherRepo {
    const val REFRESH_MS = 30 * 60_000L

    private fun cacheFile(context: Context) = File(context.cacheDir, "weather.json")

    fun cached(context: Context, lat: Double, lon: Double): Weather? = try {
        val f = cacheFile(context)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            if (o.getDouble("lat") != lat || o.getDouble("lon") != lon) null
            else parse(o.getJSONObject("forecast"), o.getLong("at"))
        }
    } catch (e: Exception) {
        null
    }

    /** Network call; run off the main thread. */
    fun fetch(context: Context, lat: Double, lon: Double): Weather {
        val url = String.format(
            Locale.US,
            "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon,
        )
        val forecast = JSONObject(Http.get(url))
        val now = System.currentTimeMillis()
        cacheFile(context).writeText(
            JSONObject().put("lat", lat).put("lon", lon).put("at", now).put("forecast", forecast).toString(),
        )
        return parse(forecast, now)
    }

    private class Point(val time: Instant, val temp: Double, val wind: Double, val humidity: Double,
                        val symbol1h: String?, val symbol6h: String?, val precip: Double)

    private fun parse(forecast: JSONObject, fetchedAt: Long): Weather {
        val series: JSONArray = forecast.getJSONObject("properties").getJSONArray("timeseries")
        val points = (0 until series.length()).map { i ->
            val o = series.getJSONObject(i)
            val data = o.getJSONObject("data")
            val d = data.getJSONObject("instant").getJSONObject("details")
            val n1 = data.optJSONObject("next_1_hours")
            val n6 = data.optJSONObject("next_6_hours")
            Point(
                time = Instant.parse(o.getString("time")),
                temp = d.getDouble("air_temperature"),
                wind = d.optDouble("wind_speed", 0.0),
                humidity = d.optDouble("relative_humidity", 50.0),
                symbol1h = n1?.optJSONObject("summary")?.optString("symbol_code"),
                symbol6h = n6?.optJSONObject("summary")?.optString("symbol_code"),
                // Hourly steps carry 1h precipitation; later 6-hourly steps only carry 6h totals.
                precip = n1?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0)
                    ?: n6?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0,
            )
        }
        val nowInstant = Instant.now()
        val current = points.lastOrNull { !it.time.isAfter(nowInstant) } ?: points.first()
        val zone = ZoneId.systemDefault()
        val byDay = points.groupBy { it.time.atZone(zone).toLocalDate() }
        val today = LocalDate.now(zone)

        fun day(date: LocalDate): WeatherDay? {
            val list = byDay[date] ?: return null
            val noon = list.minByOrNull { kotlin.math.abs(it.time.atZone(zone).hour - 12) }!!
            return WeatherDay(
                date = date,
                min = list.minOf { it.temp }.roundToInt(),
                max = list.maxOf { it.temp }.roundToInt(),
                precipMm = list.sumOf { it.precip },
                symbol = noon.symbol6h ?: noon.symbol1h ?: "cloudy",
            )
        }

        return Weather(
            temp = current.temp.roundToInt(),
            feels = apparent(current.temp, current.humidity, current.wind).roundToInt(),
            wind = current.wind.roundToInt(),
            symbol = current.symbol1h ?: current.symbol6h ?: "cloudy",
            today = day(today) ?: WeatherDay(today, current.temp.roundToInt(), current.temp.roundToInt(), 0.0, "cloudy"),
            next = (1..3).mapNotNull { day(today.plusDays(it.toLong())) },
            fetchedAt = fetchedAt,
        )
    }

    /** Australian BoM apparent temperature (no solar term). */
    private fun apparent(t: Double, rh: Double, wind: Double): Double {
        val e = rh / 100 * 6.105 * exp(17.27 * t / (237.7 + t))
        return t + 0.33 * e - 0.70 * wind - 4.0
    }

    fun emoji(symbol: String): String {
        val night = symbol.endsWith("_night")
        val s = symbol.substringBefore('_')
        return when {
            "thunder" in s -> "⛈️"
            "snow" in s -> "🌨️"
            "sleet" in s -> "🌨️"
            "rainshowers" in s -> "🌦️"
            "rain" in s -> "🌧️"
            s == "fog" -> "🌫️"
            s == "cloudy" -> "☁️"
            s == "partlycloudy" -> if (night) "☁️" else "⛅"
            s == "fair" -> if (night) "🌙" else "🌤️"
            s == "clearsky" -> if (night) "🌙" else "☀️"
            else -> "☁️"
        }
    }
}

data class City(val name: String, val details: String, val lat: Double, val lon: Double)

/** City search via OpenStreetMap Nominatim. */
object Geocoder {
    fun search(query: String): List<City> {
        val lang = Locale.getDefault().language
        val json = Http.get(
            "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&addressdetails=1" +
                "&featureType=settlement&accept-language=$lang&q=" + Http.enc(query),
        )
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            City(
                name = o.optString("name").ifBlank { display.substringBefore(',') },
                details = display,
                lat = o.getString("lat").toDouble(),
                lon = o.getString("lon").toDouble(),
            )
        }
    }
}
