package dev.ad.tv;

final class BgPreset {
    final int name;
    final int[] colors;

    BgPreset(int name, int... colors) {
        this.name = name;
        this.colors = colors;
    }
}
