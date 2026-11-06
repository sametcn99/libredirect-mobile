// Reader mode (4.4): a self-contained, app-packaged article extractor. No
// network, no third-party code. First invocation extracts the main content
// and re-renders it in a clean, theme-aware view; a second invocation
// restores the stashed original DOM.
(function () {
    if (window.__lrReaderActive) {
        document.documentElement.innerHTML = window.__lrReaderOriginal;
        window.__lrReaderActive = false;
        return;
    }
    var doc = document.cloneNode(true);
    var noise = doc.querySelectorAll('script,style,noscript,nav,aside,footer,header,form,iframe,svg,button');
    Array.prototype.forEach.call(noise, function (n) { n.remove(); });

    // Score containers by how much direct text they carry relative to their
    // structural bulk; the top scorer is treated as the article body.
    var candidates = [];
    Array.prototype.forEach.call(doc.querySelectorAll('article,main,section,div'), function (el) {
        var text = el.innerText || '';
        if (text.length > 200) {
            var bulk = 1 + el.querySelectorAll('div,section,article').length;
            candidates.push({ el: el, score: text.length / bulk });
        }
    });
    if (!candidates.length) return;
    candidates.sort(function (a, b) { return b.score - a.score; });
    var best = candidates[0].el;

    var heading = doc.querySelector('h1');
    var title = heading ? heading.innerText : (document.title || '');

    window.__lrReaderOriginal = document.documentElement.innerHTML;
    window.__lrReaderActive = true;

    var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    var wrap = document.createElement('div');
    wrap.style.cssText = 'max-width:42em;margin:0 auto;padding:1.2em 1em 4em;';

    var h = document.createElement('h1');
    h.innerText = title;
    h.style.cssText = 'font-size:1.6em;line-height:1.25;margin:0.6em 0;';
    wrap.appendChild(h);

    Array.prototype.slice.call(best.children).forEach(function (child) {
        if ((child.innerText || '').trim().length < 2 && child.tagName !== 'IMG') return;
        var tag = child.tagName.toLowerCase();
        if (tag === 'div' && child.children.length === 0) tag = 'p';
        var node = document.createElement(tag === 'h2' || tag === 'h3' ? tag : 'p');
        if (node.tagName === 'P') node.style.cssText = 'font-size:1.05em;line-height:1.65;margin:0 0 1em;';
        node.innerHTML = child.innerHTML;
        wrap.appendChild(node);
    });

    document.documentElement.innerHTML = '';
    var root = document.createElement('div');
    root.appendChild(wrap);
    document.body.appendChild(root);

    document.body.style.cssText = 'margin:0;' +
        (dark ? 'background:#1c1b1f;color:#e6e1e5;' : 'background:#fdfcfa;color:#1c1b1f;');
    Array.prototype.forEach.call(document.images, function (img) {
        img.style.cssText = 'max-width:100%;height:auto;';
    });
})()
