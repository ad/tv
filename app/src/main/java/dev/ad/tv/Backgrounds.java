package dev.ad.tv;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Backgrounds {
    private Backgrounds() {}

    static final List<BgPreset> PRESETS = List.of(
            new BgPreset(R.string.bg_midnight, 0xFF0B1026, 0xFF1B2A4A, 0xFF0B1026),
            new BgPreset(R.string.bg_ocean, 0xFF02293A, 0xFF0B5A6E),
            new BgPreset(R.string.bg_aurora, 0xFF0A1F1A, 0xFF134E4A, 0xFF3B1E54),
            new BgPreset(R.string.bg_sunset, 0xFF2A0F2E, 0xFF7A2E3A, 0xFFB5523B),
            new BgPreset(R.string.bg_forest, 0xFF0E1A12, 0xFF1F3B2A),
            new BgPreset(R.string.bg_graphite, 0xFF151618, 0xFF2B2D31),
            new BgPreset(R.string.bg_black, 0xFF000000),
            new BgPreset(R.string.bg_navy, 0xFF0D1B3D),
            new BgPreset(R.string.bg_plum, 0xFF2B1236),
            new BgPreset(R.string.bg_coffee, 0xFF2A1E17, 0xFF4A3426));

    static GradientDrawable drawable(int index) {
        BgPreset p = index >= 0 && index < PRESETS.size() ? PRESETS.get(index) : PRESETS.get(0);
        int[] colors = p.colors.length == 1 ? new int[] {p.colors[0], p.colors[0]} : p.colors;
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
    }

    static List<String> bundledVideos(Context context) {
        List<String> out = new ArrayList<>();
        String[] names;
        try {
            names = context.getAssets().list("");
        } catch (IOException e) {
            return out;
        }
        if (names == null) return out;
        for (String name : names) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm")) out.add("asset:///" + Uri.encode(name));
        }
        return out;
    }
}
