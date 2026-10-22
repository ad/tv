package dev.ad.tv;

interface CursorHost {
    default void hover(float x, float y) {}

    void click(float x, float y);

    void longClick(float x, float y);

    void scroll(float x, float y, int dx, int dy);
}
