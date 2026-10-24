package dev.ad.tv;

enum TileSize {
    COMPACT(112, 63, 4),
    MEDIUM(136, 77, 5),
    LARGE(160, 90, 6);

    final int width;
    final int height;
    final int panelPadding;

    TileSize(int width, int height, int panelPadding) {
        this.width = width;
        this.height = height;
        this.panelPadding = panelPadding;
    }
}
