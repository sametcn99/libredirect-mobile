// Background audio (4.6): reports page media playback state through the
// AndroidMedia bridge. Idempotent — re-running after navigations just
// re-attaches listeners; a MutationObserver covers players created later.
(function () {
    function hook(el) {
        if (el.__lrHooked) return
        el.__lrHooked = 1;
        ['playing', 'pause', 'ended'].forEach(function (evt) {
            el.addEventListener(evt, function () {
                try { AndroidMedia.onMediaState(evt === 'playing'); } catch (e) {}
            });
        });
    }
    function scan() {
        document.querySelectorAll('video,audio').forEach(hook);
    }
    scan();
    if (window.MutationObserver) {
        new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
    }
})()
