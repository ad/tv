package dev.ad.tv;

import java.util.List;

final class Weather {
    final int temp;
    final int feels;
    final int wind;
    final String symbol;
    final WeatherDay today;
    final List<WeatherDay> next;
    final long fetchedAt;

    Weather(int temp, int feels, int wind, String symbol, WeatherDay today, List<WeatherDay> next, long fetchedAt) {
        this.temp = temp;
        this.feels = feels;
        this.wind = wind;
        this.symbol = symbol;
        this.today = today;
        this.next = next;
        this.fetchedAt = fetchedAt;
    }
}
