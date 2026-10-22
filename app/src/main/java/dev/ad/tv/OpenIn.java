package dev.ad.tv;

enum OpenIn {
    LAMPA("top.rootu.lampa"),
    KINOPOISK("ru.kinopoisk.tv"),
    NUM("ru.yourok.num");

    final String pkg;

    OpenIn(String pkg) {
        this.pkg = pkg;
    }
}
