package dev.ad.tv;

enum OpenIn {
    LAMPA("top.rootu.lampa"),
    KINOPOISK("ru.kinopoisk.tv"),
    NUM("ru.yourok.num"),
    SOAP_TV("dev.ad.soap");

    final String pkg;

    OpenIn(String pkg) {
        this.pkg = pkg;
    }

    static OpenIn parse(String name) {
        // Saved before Soap TV replaced soap4u.
        if ("SOAP4U".equals(name)) return SOAP_TV;
        try {
            return valueOf(name);
        } catch (RuntimeException e) {
            return LAMPA;
        }
    }
}
