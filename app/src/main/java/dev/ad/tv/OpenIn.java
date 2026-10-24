package dev.ad.tv;

enum OpenIn {
    LAMPA("top.rootu.lampa"),
    KINOPOISK("ru.kinopoisk.tv"),
    NUM("ru.yourok.num"),
    SOAP4ALL("com.filimons.soap4all");

    final String pkg;

    OpenIn(String pkg) {
        this.pkg = pkg;
    }
}
