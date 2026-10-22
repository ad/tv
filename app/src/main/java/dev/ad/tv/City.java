package dev.ad.tv;

final class City {
    final String name;
    final String details;
    final double lat;
    final double lon;

    City(String name, String details, double lat, double lon) {
        this.name = name;
        this.details = details;
        this.lat = lat;
        this.lon = lon;
    }
}
