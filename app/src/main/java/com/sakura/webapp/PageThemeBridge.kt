package com.sakura.webapp

import android.webkit.JavascriptInterface

/**
 * Lets the Android system bars match the page: the declared theme color (Open WebUI's
 * meta[name=theme-color]) as the base, and the background actually drawn at the page's top
 * and bottom edges, so the status bar and the navigation bar take, for example, the grey of
 * the open sidebar. Only CSS background colors are read, never image pixels, so a dark picture
 * scrolling under an edge does not change the bars.
 */
class PageThemeBridge(
    /** True only while the WebView shows the trusted Open WebUI origin. */
    private val isTrustedPage: () -> Boolean,
    /** Receives a theme color as 0xRRGGBB, or -1 when unavailable. */
    private val onThemeColor: (Int) -> Unit,
    /** Receives the colors at the top and bottom edges as 0xRRGGBB, -1 when unknown. */
    private val onEdgeColors: (top: Int, bottom: Int) -> Unit = { _, _ -> },
    /** Something on the page may have changed what is drawn at its edges: read the pixels there. */
    private val onEdgesChanged: () -> Unit = {},
) {
    @JavascriptInterface
    fun edgesChanged() {
        if (!isTrustedPage()) return
        onEdgesChanged()
    }

    @JavascriptInterface
    fun setThemeColor(color: Int) {
        if (!isTrustedPage()) return
        onThemeColor(color.coerceIn(-1, 0xFFFFFF))
    }

    @JavascriptInterface
    fun setEdgeColors(top: Int, bottom: Int) {
        if (!isTrustedPage()) return
        onEdgeColors(top.coerceIn(-1, 0xFFFFFF), bottom.coerceIn(-1, 0xFFFFFF))
    }

    companion object {
        const val JS_NAME = "SakuraTheme"

        /**
         * Open WebUI 0.12.3 updates meta[name=theme-color] when its theme changes:
         * System resolves against Android's color scheme, Dark and OLED Dark use dark
         * colors, Light uses white, and Her uses its own dark burgundy color. Observing
         * this metadata avoids false theme changes caused by dark dialogs or images.
         */
        val WATCH_JS = """
            (function() {
                if (window.__sakuraThemeWatch || !window.$JS_NAME) return;
                window.__sakuraThemeWatch = true;
                const root = document.documentElement;
                function parseThemeColor(value) {
                    const color = (value || '').trim();
                    let match = /^#([0-9a-f]{3})$/i.exec(color);
                    if (match) {
                        const hex = match[1];
                        return parseInt(hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2], 16);
                    }
                    match = /^#([0-9a-f]{6})$/i.exec(color);
                    if (match) return parseInt(match[1], 16);
                    match = /^#([0-9a-f]{8})$/i.exec(color);
                    if (match) return parseInt(match[1].slice(2), 16);
                    match = /^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/i.exec(color);
                    if (match) return (+match[1] << 16) | (+match[2] << 8) | +match[3];
                    return -1;
                }
                let last = null;
                function report() {
                    const meta = document.querySelector('meta[name="theme-color"]');
                    let color = meta ? parseThemeColor(meta.content) : -1;
                    if (color === -1) {
                        // Fallback for customised pages that omit the metadata.
                        const classes = root.classList;
                        const theme = (localStorage.getItem('theme') || '').toLowerCase();
                        const dark = theme === 'light' ? false :
                            theme === 'dark' || theme === 'oled-dark' ? true :
                            theme === 'her' ? true :
                            classes.contains('dark') ||
                            (!classes.contains('light') &&
                                window.matchMedia('(prefers-color-scheme: dark)').matches);
                        color = theme === 'her' ? 0x983724 :
                            theme === 'oled-dark' ? 0x000000 :
                            dark ? 0x171717 : 0xffffff;
                    }
                    if (color === last) return;
                    last = color;
                    window.$JS_NAME.setThemeColor(color);
                }
                const schedule = function() { requestAnimationFrame(report); };
                const observer = new MutationObserver(schedule);
                observer.observe(root, { attributes: true, attributeFilter: ['class', 'style'] });
                if (document.head) {
                    observer.observe(document.head, { subtree: true, childList: true, attributes: true, attributeFilter: ['content', 'name'] });
                }
                if (window.matchMedia) {
                    const media = window.matchMedia('(prefers-color-scheme: dark)');
                    if (media.addEventListener) media.addEventListener('change', schedule);
                    else if (media.addListener) media.addListener(schedule);
                }
                window.addEventListener('storage', schedule);
                setInterval(report, 1500);
                schedule();

                // ── Colors at the page's top and bottom edges (for the status and navigation bars) ──
                // Any CSS color format (Open WebUI uses oklch) becomes RGB through a 1×1 canvas.
                const swatch = document.createElement('canvas');
                swatch.width = swatch.height = 1;
                const pen = swatch.getContext('2d', { willReadFrequently: true });
                const known = new Map();
                function rgba(value) {
                    if (!value || value === 'transparent') return null;
                    if (known.has(value)) return known.get(value);
                    let out = null;
                    const m = /^rgba?\(([^)]+)\)$/.exec(value);
                    if (m) {
                        const p = m[1].split(/[\s,\/]+/).filter(Boolean).map(parseFloat);
                        out = { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
                    } else if (pen) {
                        pen.clearRect(0, 0, 1, 1);
                        pen.fillStyle = '#000';
                        pen.fillStyle = value;
                        pen.fillRect(0, 0, 1, 1);
                        const d = pen.getImageData(0, 0, 1, 1).data;
                        out = { r: d[0], g: d[1], b: d[2], a: d[3] / 255 };
                    }
                    if (known.size > 200) known.clear();
                    known.set(value, out);
                    return out;
                }
                // Background colors of everything under the point, front to back; nearly solid panels
                // (a translucent header, a dimmed backdrop) count as solid, so scrolling content seen
                // through them does not keep changing the bar.
                function edge(y) {
                    if (!document.elementsFromPoint) return -1;
                    const x = Math.min(12, window.innerWidth / 2);
                    let r = 0, g = 0, b = 0, a = 0;
                    const stack = document.elementsFromPoint(x, y);
                    for (let i = 0; i < stack.length && a < 0.98; i++) {
                        const c = rgba(getComputedStyle(stack[i]).backgroundColor);
                        if (!c || c.a <= 0.01) continue;
                        const w = (1 - a) * (c.a >= 0.5 ? 1 : c.a);
                        r += c.r * w; g += c.g * w; b += c.b * w; a += w;
                    }
                    if (a < 0.5) return -1;
                    return (Math.round(r / a) << 16) | (Math.round(g / a) << 8) | Math.round(b / a);
                }
                let lastEdges = '';
                function reportEdges() {
                    // The app reads the actual pixels next to each bar (gradients and blur included).
                    if (window.$JS_NAME.edgesChanged) window.$JS_NAME.edgesChanged();
                    if (!window.$JS_NAME.setEdgeColors) return;
                    const top = edge(0);
                    const bottom = edge(Math.max(0, window.innerHeight - 1));
                    const key = top + ',' + bottom;
                    if (key === lastEdges) return;
                    lastEdges = key;
                    window.$JS_NAME.setEdgeColors(top, bottom);
                }
                let edgeTimer = 0;
                const scheduleEdges = function() {
                    if (edgeTimer) return;
                    edgeTimer = setTimeout(function() { edgeTimer = 0; reportEdges(); }, 120);
                };
                if (document.body) {
                    new MutationObserver(scheduleEdges).observe(document.body, {
                        subtree: true, childList: true, attributes: true,
                        attributeFilter: ['class', 'style', 'open', 'data-state', 'aria-hidden']
                    });
                }
                ['transitionend', 'animationend', 'scroll', 'resize'].forEach(function(name) {
                    window.addEventListener(name, scheduleEdges, { capture: true, passive: true });
                });
                setInterval(reportEdges, 1500);
                scheduleEdges();
            })();
        """.trimIndent()
    }
}
