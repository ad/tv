package dev.ad.tv;

import java.time.LocalDate;

final class WeatherDay {
    final LocalDate date;
    final int min;
    final int max;
    final double precipMm;
    final String symbol;

    WeatherDay(LocalDate date, int min, int max, double precipMm, String symbol) {
        this.date = date;
        this.min = min;
        this.max = max;
        this.precipMm = precipMm;
        this.symbol = symbol;
    }
}
