package dev.ad.tv;

public class Checks {
    public static void main(String[] args) {
        FeedItem feed = item("tmdb:tv:1", "Пришелец", "2021", true, 1L);
        check(MovieFavorites.same(feed, item("soap:tv:9", "пришелец!", "2021", true, null)), "title and year merge");
        check(MovieFavorites.same(feed, item("soap:tv:9", "Пришёлец", "2021", true, null)), "ё merges with е");
        check(!MovieFavorites.same(feed, item("soap:tv:9", "Пришелец", "2022", true, null)), "different year");
        check(!MovieFavorites.same(feed, item("soap:movie:9", "Пришелец", "2021", false, null)), "different type");
        check(!MovieFavorites.same(item("a", "X", "", true, null), item("b", "X", "", true, null)), "empty year never merges");
        check(MovieFavorites.same(item("soap:tv:9", "A", "", true, null), item("soap:tv:9", "B", "", true, null)), "same key");
        check(!MovieFavorites.same(feed, item("tmdb:tv:2", "Пришелец", "2021", true, 2L)), "tmdb id decides first");
        check(OpenIn.parse("SOAP4U") == OpenIn.SOAP_TV, "old soap4u setting maps to Soap TV");
        check(OpenIn.parse("bogus") == OpenIn.LAMPA && OpenIn.parse(null) == OpenIn.LAMPA, "unknown setting falls back to Lampa");
        check(Update.build("v1.2.26") == 26 && Update.build("v1") == -1 && Update.build("") == -1, "release tag build number");
        check("WEBDL".equals(FeedItem.quality(" webdl ")) && "4K".equals(FeedItem.quality("4K")), "quality is upper-cased");
        check(FeedItem.quality(null) == null && FeedItem.quality("") == null && FeedItem.quality("<b>4K</b>") == null
            && FeedItem.quality("WEBDL 1080p HDR") == null, "odd quality is dropped");
        float[] wave = new float[64], moved = new float[64];
        for (int i = 0; i < 64; i++) {
            wave[i] = (float) Math.sin(i * 0.3) * 50 + i;
            moved[i] = (float) Math.sin((i - 3) * 0.3) * 50 + (i - 3) + 20;
        }
        check(Math.abs(Shake.shift(wave, moved, 8) - 3) < 0.3f, "shift is found despite brightness change");
        check(Math.abs(Shake.shift(moved, wave, 8) + 3) < 0.3f, "negative shift");
        float[] half = new float[64];
        for (int i = 0; i < 64; i++) half[i] = (float) Math.sin((i - 2.5) * 0.3) * 50 + (i - 2.5f);
        check(Math.abs(Shake.shift(wave, half, 8) - 2.5f) < 0.3f, "sub-pixel shift");
        check(Shake.shift(new float[64], new float[64], 8) == 0, "flat profile does not move");
        check(Shake.clamp(500, 2, 1000) == 500 && Shake.clamp(600, 2, 1000) == 500 && Shake.clamp(-600, 2, 1000) == -500
            && Shake.clamp(30, 1, 1000) == 0, "pan stays over the picture");
        Shake shake = new Shake();
        shake.next(frame(0));
        float[] fix = shake.next(frame(3));
        check(fix[0] < -2 && fix[0] > -3 && Math.abs(fix[1]) < 0.3f, "frame moved right is pushed back left");
        System.out.println("tv checks ok");
    }

    private static FeedItem item(String key, String title, String year, boolean tv, Long tmdb) {
        return new FeedItem(key, title, year, tv, null, null, tmdb, null);
    }

    private static int[] frame(int dx) {
        int[] argb = new int[Shake.W * Shake.H];
        for (int y = 0; y < Shake.H; y++) {
            for (int x = 0; x < Shake.W; x++) {
                int v = (int) (128 + 60 * Math.sin((x - dx) * 0.2) + 40 * Math.sin(y * 0.15));
                argb[y * Shake.W + x] = 0xFF000000 | v << 16 | v << 8 | v;
            }
        }
        return argb;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
