(function () {
    'use strict';

    if (window.tv_home_favorites) return;
    window.tv_home_favorites = true;

    function param(name, value) {
        return value ? '&' + name + '=' + encodeURIComponent(value) : '';
    }

    function isTv(card) {
        if (card.method === 'tv' || card.method === 'movie') return card.method === 'tv';
        return !!card.name;
    }

    function add(card) {
        // Cards from sources other plugins add carry their own ids, not TMDB ones.
        if (card.source && card.source !== 'tmdb' && card.source !== 'cub') {
            Lampa.Noty.show('TV Home: only TMDB and CUB cards');
            return;
        }
        if (!window.AndroidJS || !window.AndroidJS.openBrowser) {
            Lampa.Noty.show('TV Home: needs the Lampa Android app');
            return;
        }
        var date = card.release_date || card.first_air_date || '';
        window.AndroidJS.openBrowser('adtv://fav/add?tmdb=' + encodeURIComponent(card.id)
            + '&media=' + (isTv(card) ? 'tv' : 'movie')
            + param('title', card.title || card.name)
            + param('year', String(date).slice(0, 4))
            + param('poster', card.poster_path)
            + param('backdrop', card.backdrop_path));
    }

    Lampa.Manifest.plugins = {
        type: 'video',
        name: 'TV Home: в избранное',
        description: 'Добавить на главный экран лаунчера',
        onContextMenu: function () {
            return {};
        },
        onContextLauch: add
    };
})();
