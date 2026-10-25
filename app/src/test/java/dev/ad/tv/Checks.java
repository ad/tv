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
        System.out.println("tv checks ok");
    }

    private static FeedItem item(String key, String title, String year, boolean tv, Long tmdb) {
        return new FeedItem(key, title, year, tv, null, null, tmdb, null);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
