package dev.ad.tv;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.List;
import java.util.Locale;

final class Lang {
    static final List<String> TAGS = List.of("", "en", "ru");

    private Lang() {}

    // Per-app LocaleManager needs API 33; minSdk is 31.
    static Context wrap(Context base) {
        String tag = Prefs.get(base).getLanguage();
        Locale locale = tag.isEmpty() ? Resources.getSystem().getConfiguration().getLocales().get(0) : Locale.forLanguageTag(tag);
        Locale.setDefault(locale);
        if (tag.isEmpty()) return base;
        Configuration config = new Configuration();
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }
}
