package dev.ad.tv;

enum FeedKind {
    BOTH(R.string.kind_both), MOVIE(R.string.kind_movies), TV(R.string.kind_series);

    final int label;

    FeedKind(int label) {
        this.label = label;
    }
}
