package dev.ad.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WeatherRepo {
    static final long REFRESH_MS = 30 * 60_000L;

    private WeatherRepo() {}

    private static File cacheFile(Context context) {
        return new File(context.getCacheDir(), "weather.json");
    }

    static Weather cached(Context context, double lat, double lon) {
        try {
            File f = cacheFile(context);
            if (!f.exists()) return null;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (o.getDouble("lat") != lat || o.getDouble("lon") != lon) return null;
            return parse(o.getJSONObject("forecast"), o.getLong("at"));
        } catch (Exception e) {
            return null;
        }
    }

    static Weather fetch(Context context, double lat, double lon) throws Exception {
        String url = String.format(
            Locale.US,
            "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon
        );
        JSONObject forecast = new JSONObject(Http.get(url));
        long now = System.currentTimeMillis();
        String json = new JSONObject().put("lat", lat).put("lon", lon).put("at", now).put("forecast", forecast).toString();
        Files.write(cacheFile(context).toPath(), json.getBytes(StandardCharsets.UTF_8));
        return parse(forecast, now);
    }

    private static final class Point {
        final Instant time;
        final double temp;
        final double wind;
        final double humidity;
        final String symbol1h;
        final String symbol6h;
        final double precip;

        Point(Instant time, double temp, double wind, double humidity, String symbol1h, String symbol6h, double precip) {
            this.time = time;
            this.temp = temp;
            this.wind = wind;
            this.humidity = humidity;
            this.symbol1h = symbol1h;
            this.symbol6h = symbol6h;
            this.precip = precip;
        }
    }

    private static Weather parse(JSONObject forecast, long fetchedAt) throws Exception {
        JSONArray series = forecast.getJSONObject("properties").getJSONArray("timeseries");
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < series.length(); i++) {
            JSONObject o = series.getJSONObject(i);
            JSONObject data = o.getJSONObject("data");
            JSONObject d = data.getJSONObject("instant").getJSONObject("details");
            JSONObject n1 = data.optJSONObject("next_1_hours");
            JSONObject n6 = data.optJSONObject("next_6_hours");
            JSONObject s1 = n1 != null ? n1.optJSONObject("summary") : null;
            JSONObject s6 = n6 != null ? n6.optJSONObject("summary") : null;
            JSONObject d1 = n1 != null ? n1.optJSONObject("details") : null;
            JSONObject d6 = n6 != null ? n6.optJSONObject("details") : null;
            // Hourly steps carry 1h precipitation; later 6-hourly steps only carry 6h totals.
            double precip = d1 != null ? d1.optDouble("precipitation_amount", 0.0)
                : d6 != null ? d6.optDouble("precipitation_amount", 0.0) : 0.0;
            points.add(new Point(
                Instant.parse(o.getString("time")),
                d.getDouble("air_temperature"),
                d.optDouble("wind_speed", 0.0),
                d.optDouble("relative_humidity", 50.0),
                s1 != null ? s1.optString("symbol_code") : null,
                s6 != null ? s6.optString("symbol_code") : null,
                precip
            ));
        }
        Instant nowInstant = Instant.now();
        Point current = points.get(0);
        for (Point p : points) if (!p.time.isAfter(nowInstant)) current = p;
        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, List<Point>> byDay = new LinkedHashMap<>();
        for (Point p : points) byDay.computeIfAbsent(p.time.atZone(zone).toLocalDate(), k -> new ArrayList<>()).add(p);
        LocalDate today = LocalDate.now(zone);

        WeatherDay todayDay = day(byDay, zone, today);
        List<WeatherDay> next = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            WeatherDay wd = day(byDay, zone, today.plusDays(i));
            if (wd != null) next.add(wd);
        }
        int temp = (int) Math.round(current.temp);
        String symbol = current.symbol1h != null ? current.symbol1h : current.symbol6h != null ? current.symbol6h : "cloudy";
        return new Weather(
            temp,
            (int) Math.round(apparent(current.temp, current.humidity, current.wind)),
            (int) Math.round(current.wind),
            symbol,
            todayDay != null ? todayDay : new WeatherDay(today, temp, temp, 0.0, "cloudy"),
            next,
            fetchedAt
        );
    }

    private static WeatherDay day(Map<LocalDate, List<Point>> byDay, ZoneId zone, LocalDate date) {
        List<Point> list = byDay.get(date);
        if (list == null) return null;
        Point noon = list.get(0);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double precip = 0.0;
        for (Point p : list) {
            if (Math.abs(p.time.atZone(zone).getHour() - 12) < Math.abs(noon.time.atZone(zone).getHour() - 12)) noon = p;
            min = Math.min(min, p.temp);
            max = Math.max(max, p.temp);
            precip += p.precip;
        }
        String symbol = noon.symbol6h != null ? noon.symbol6h : noon.symbol1h != null ? noon.symbol1h : "cloudy";
        return new WeatherDay(date, (int) Math.round(min), (int) Math.round(max), precip, symbol);
    }

    // Australian BoM apparent temperature, without the solar term.
    private static double apparent(double t, double rh, double wind) {
        double e = rh / 100 * 6.105 * Math.exp(17.27 * t / (237.7 + t));
        return t + 0.33 * e - 0.70 * wind - 4.0;
    }

    static String emoji(String symbol) {
        boolean night = symbol.endsWith("_night");
        String s = symbol.split("_", 2)[0];
        if (s.contains("thunder")) return "⛈️";
        if (s.contains("snow")) return "🌨️";
        if (s.contains("sleet")) return "🌨️";
        if (s.contains("rainshowers")) return "🌦️";
        if (s.contains("rain")) return "🌧️";
        if (s.equals("fog")) return "🌫️";
        if (s.equals("cloudy")) return "☁️";
        if (s.equals("partlycloudy")) return night ? "☁️" : "⛅";
        if (s.equals("fair")) return night ? "🌙" : "🌤️";
        if (s.equals("clearsky")) return night ? "🌙" : "☀️";
        return "☁️";
    }
}
