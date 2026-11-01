package com.sakura.webapp

import android.webkit.JavascriptInterface

/**
 * Reports the colours Open WebUI paints at the top and bottom of the screen so the
 * activity can colour the areas behind the status and navigation bars to match.
 * Without this, switching Open WebUI to a dark theme leaves both bars white.
 * Exposed to the page as `window.SakuraTheme`; [WATCH_JS] feeds it.
 */
class PageThemeBridge(
    /** True only while the WebView shows the trusted Open WebUI origin. */
    private val isTrustedPage: () -> Boolean,
    /**
     * Receives the top and bottom colours as `0xRRGGBB`, or -1 when the page has no
     * opaque background there; called on a WebView thread.
     */
    private val onColors: (top: Int, bottom: Int) -> Unit,
) {
    @JavascriptInterface
    fun setBarColors(top: Int, bottom: Int) {
        if (!isTrustedPage()) return
        onColors(top.coerceIn(-1, 0xFFFFFF), bottom.coerceIn(-1, 0xFFFFFF))
    }

    companion object {
        const val JS_NAME = "SakuraTheme"

        /**
         * Samples the first opaque background under the top and bottom edges of the page
         * and reports it whenever it changes: on theme class or style changes, on system
         * theme changes and, as a fallback for in-page navigation, once per second.
         */
        val WATCH_JS = """
            (function() {
                if (window.__sakuraThemeWatch || !window.$JS_NAME) return;
                window.__sakuraThemeWatch = true;
                function parse(color) {
                    const m = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?/.exec(color || '');
                    if (!m || (m[4] !== undefined && parseFloat(m[4]) < 0.5)) return -1;
                    return (+m[1] << 16) | (+m[2] << 8) | +m[3];
                }
                const root = document.documentElement;
                function covers(el, x, y) {
                    const r = el.getBoundingClientRect();
                    return x >= r.left && x < r.right && y >= r.top && y < r.bottom;
                }
                // Full-screen backdrops (such as the sign-in page's) often ignore pointer
                // events, so hit testing misses them; look for one near the top of the tree.
                function backdropAt(x, y) {
                    let found = -1;
                    (function walk(el, depth) {
                        for (const child of el.children) {
                            const r = child.getBoundingClientRect();
                            if (r.width === 0 || r.height === 0) {
                                // Wrappers such as `display: contents` have no box of their own.
                                if (depth < 8) walk(child, depth + 1);
                                continue;
                            }
                            if (!covers(child, x, y)) continue;
                            const c = parse(getComputedStyle(child).backgroundColor);
                            if (c !== -1) found = c;
                            if (depth < 8) walk(child, depth + 1);
                        }
                    })(document.body, 1);
                    return found;
                }
                function backgroundAt(y) {
                    const x = window.innerWidth / 2;
                    let el = document.elementFromPoint(x, y);
                    while (el && el !== document.body && el !== root) {
                        const c = parse(getComputedStyle(el).backgroundColor);
                        if (c !== -1) return c;
                        el = el.parentElement;
                    }
                    const backdrop = backdropAt(x, y);
                    if (backdrop !== -1) return backdrop;
                    const body = parse(getComputedStyle(document.body).backgroundColor);
                    return body !== -1 ? body : parse(getComputedStyle(root).backgroundColor);
                }
                let last = '';
                function report() {
                    if (!document.body) return;
                    const top = backgroundAt(1);
                    const bottom = backgroundAt(window.innerHeight - 2);
                    const key = top + ':' + bottom;
                    if (key === last) return;
                    last = key;
                    window.$JS_NAME.setBarColors(top, bottom);
                }
                const schedule = function() { requestAnimationFrame(report); };
                new MutationObserver(schedule).observe(root, {
                    attributes: true, attributeFilter: ['class', 'style', 'data-theme']
                });
                if (window.matchMedia) {
                    const media = window.matchMedia('(prefers-color-scheme: dark)');
                    if (media.addEventListener) media.addEventListener('change', schedule);
                }
                window.addEventListener('resize', schedule);
                setInterval(report, 1000);
                schedule();
            })();
        """.trimIndent()
    }
}
