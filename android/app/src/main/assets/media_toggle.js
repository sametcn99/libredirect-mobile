// Background audio (4.6): notification play/pause action — toggles the
// page's media elements. If any element is playing, pause all; otherwise
// resume all.
(function () {
    var media = document.querySelectorAll('video,audio');
    var anyPlaying = Array.prototype.some.call(media, function (e) { return !e.paused; });
    Array.prototype.forEach.call(media, function (e) {
        if (anyPlaying) { e.pause(); } else { e.play(); }
    });
})()
