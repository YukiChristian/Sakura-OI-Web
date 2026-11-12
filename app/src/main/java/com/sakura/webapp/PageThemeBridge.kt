package com.sakura.webapp

import android.webkit.JavascriptInterface

/**
 * Reads Open WebUI's declared theme color so Android system bars follow the selected
 * WebUI theme. It intentionally does not inspect rendered screen colors: dialogs and
 * image content can contain dark areas while the selected theme is still light.
 */
class PageThemeBridge(
    /** True only while the WebView shows the trusted Open WebUI origin. */
    private val isTrustedPage: () -> Boolean,
    /** Receives a theme color as 0xRRGGBB, or -1 when unavailable. */
    private val onThemeColor: (Int) -> Unit,
) {
    @JavascriptInterface
    fun setThemeColor(color: Int) {
        if (!isTrustedPage()) return
        onThemeColor(color.coerceIn(-1, 0xFFFFFF))
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
            })();
        """.trimIndent()
    }
}
