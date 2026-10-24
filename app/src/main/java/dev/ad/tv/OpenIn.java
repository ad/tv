package dev.ad.tv;

enum OpenIn {
    LAMPA("top.rootu.lampa"),
    KINOPOISK("ru.kinopoisk.tv"),
    NUM("ru.yourok.num"),
    SOAP4U("com.soap4u.android");

    final String pkg;

    OpenIn(String pkg) {
        this.pkg = pkg;
    }
}
