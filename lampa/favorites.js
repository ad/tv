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
            + param('backdrop', card.backdrop_path)
            + param('quality', card.release_quality));
    }

    // Lampa collects the page's focusable buttons right after 'complite', so the button gets focus with the rest.
    Lampa.Listener.follow('full', function (e) {
        if (e.type !== 'complite' || !e.data || !e.data.movie) return;
        var options = e.body.find('.full-start-new__buttons .button--options');
        if (!options.length || e.body.find('.button--tv-home').length) return;
        var button = $('<div class="full-start__button selector button--tv-home">'
            + '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">'
            + '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 8v5M9.5 10.5h5"/></svg>'
            + '<span>В избранное ТВ</span></div>');
        button.on('hover:enter', function () {
            add(e.data.movie);
        });
        options.before(button);
    });

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
