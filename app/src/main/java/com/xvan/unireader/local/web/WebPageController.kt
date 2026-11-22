package com.xvan.unireader.local.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibWebDoc
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.shared.Ui
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网页标签的整页（模式1，`../docs/WEB-DOC-PLAN.md §9`，同 Mac `WebDocView`）：顶上一条（后退 / 前进 · 回到首页 · 标题 · 重新载入）+ `WebView`。
 *
 * - 文件经 `shouldInterceptRequest` 从工作区 `Web/` 里读，**不联网、不开端口**：地址 `https://<网页 id 小写>.unireader-web.invalid/<路径>`，
 *   每份网页的 id 当主机名 → 各份网页是不同的源（`localStorage` 互不串）；`.invalid` 保证真去解析也解析不到别处。支持 Range（视频）。
 * - `window.unireader`（同 Mac `WebBridgeScript`）：html 响应里在 `<head>` 开头塞一段脚本，经 `addJavascriptInterface` 的 `__unireaderNative`
 *   同步调到库队列（`WebKV`）。🔴 JS 接口对页面里**所有**框架可见：每次装载一个随机口令写进注入的脚本，不带口令的调用一律拒——
 *   页面里嵌的外部网站拿不到口令，碰不到数据。
 */
class WebPageController(private val a: Activity) {

    companion object {
        const val TAG = "UniReader/Web"
        const val HOST_SUFFIX = ".unireader-web.invalid"
        private const val PREFS = "webPages"
        /** prefs 里「上次滚到哪」的键前缀（后接大写 id；值 = `WebBridge.sanitizeScroll` 的 JSON），同 Mac `web.scroll.<id>`。 */
        private const val SCROLL_PREFIX = "scroll."
    }

    val view = FrameLayout(a).apply {
        setBackgroundColor(Ui.surface(a))
        visibility = View.GONE
    }

    private val titleView = Ui.title(a, "", 18f).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val backBtn = Ui.iconButton(a, R.drawable.ic_chevron_left, a.getString(R.string.web_back)) { web.goBack() }
    private val fwdBtn = Ui.iconButton(a, R.drawable.ic_chevron_right, a.getString(R.string.web_forward)) { web.goForward() }
    /** 回到首页（同 Mac）：课件的子页面不一定有回首页的链接，重新打开又停在上次那一页（后退历史是空的），没有它就回不去。
     *  走普通导航（进后退历史），返回键 / 后退还能回到刚才那页。 */
    private val homeBtn = Ui.iconButton(a, R.drawable.ic_home, a.getString(R.string.web_home)) { goHome() }
    private val reloadBtn = Ui.textButton(a, a.getString(R.string.web_reload)) { reload() }
    private val notice = Ui.body(a, "").apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
        val p = Ui.dp(a, 12)
        setPadding(p, p / 2, p, p / 2)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private val web = WebView(a).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) = updateTitle()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var queue: StoreQueue? = null
    private var ws: File? = null

    /** 当前开着的那份（主线程读写）与它的文件表（拦截线程只读：整份换掉、不改里面）。 */
    var current: LibWebDoc? = null
        private set
    @Volatile private var files: Map<String, WebAssets.Entry> = emptyMap()
    @Volatile private var folder: File? = null
    @Volatile private var hostId = ""
    @Volatile private var token = ""

    /** 这次装载的口令 → 哪份网页 + 下一次页面来问时要不要滚回去（JS 桥线程上读；装载时整份换掉，口令和网页 id 不会错配）。 */
    private class ScrollSlot(val token: String, val id: String) {
        private val armed = AtomicBoolean(true)
        /** 重新载入前置上（同 Mac：WebView 自己恢复时内容还没撑高会被截短，连着刷新一路往上跑；滚动容器里的位置不恢复）。 */
        fun arm() = armed.set(true)
        /** 页面加载好后来问；置上时只答一次（之后同一份里点来点去不再往回滚）。 */
        fun take(): Boolean = armed.getAndSet(false)
    }
    @Volatile private var scrollSlot: ScrollSlot? = null

    /** 改名 / 删除 / 重新导入之后宿主要刷新列表（抽屉、标签标题）。 */
    var onChanged: () -> Unit = {}

    init {
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 4))
            addView(backBtn)
            addView(fwdBtn)
            addView(homeBtn)
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.dp(a, 8) })
            addView(reloadBtn)
        }
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(notice, LinearLayout.LayoutParams(-1, -2))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        view.addView(column, FrameLayout.LayoutParams(-1, -1))
        web.addJavascriptInterface(Bridge(), "__unireaderNative")
        web.webViewClient = Client()
    }

    fun bind(q: StoreQueue?, dir: File?) {
        queue = q
        ws = dir
        if (q == null) close()
    }

    /** 打开一份网页（同一份再开 = 只刷新标题；清单变了重新载入）。 */
    fun open(id: String, path: String? = null) {
        val q = queue ?: return
        view.visibility = View.VISIBLE
        q.submit("读网页 $id", { s ->
            val w = s.webDoc(id)
            if (w != null && path == null) s.touchWebDocOpened(id)
            w
        }) { w ->
            if (w == null) {
                current = null
                showNotice(a.getString(R.string.web_gone))
                return@submit
            }
            val sameDoc = current?.id == w.id && current?.manifest == w.manifest && hostId.isNotEmpty()
            current = w
            updateTitle()
            if (sameDoc && path == null) { refreshNotice(); return@submit }
            load(w, path)
        }
    }

    private fun load(w: LibWebDoc, path: String?) {
        val dir = ws ?: return
        folder = dir
        files = w.entries.associateBy { it.p }
        hostId = w.id.lowercase(Locale.ROOT)
        token = UUID.randomUUID().toString()
        // 连同上次滚到的位置（记的不是这一页时页面自己认出来不滚，同 Mac）
        val idKey = w.id.uppercase(Locale.ROOT)
        scrollSlot = ScrollSlot(token, idKey)
        refreshNotice()
        val last = path ?: prefs().getString(w.id.uppercase(Locale.ROOT), null)?.takeIf { it in files }
        web.loadUrl(urlOf(hostId, last ?: w.entry))
    }

    private fun refreshNotice() {
        val dir = folder ?: return
        val missing = files.values.map { it.h }.toSet().count { !WebAssets.exists(dir, it) }
        if (missing == 0) notice.visibility = View.GONE else showNotice(a.getString(R.string.web_missing, missing))
    }

    private fun showNotice(s: String) {
        notice.text = s
        notice.visibility = View.VISIBLE
    }

    /** 同步拉到了新版（行变了 / 文件下完了）：清单变了重新载入，文件补齐了把提示收掉并重载。 */
    fun refreshFromRemote() {
        val w = current ?: return
        val hadMissing = notice.visibility == View.VISIBLE
        open(w.id)
        if (hadMissing) main.postDelayed({ if (notice.visibility == View.GONE) reload() }, 300)
    }

    /** 重新载入，并由脚本滚回刚才的位置。 */
    private fun reload() {
        scrollSlot?.arm()
        web.reload()
    }

    /** 别处改了页面存的数据：告诉页面（`unireader:change`）。 */
    fun dataChangedRemotely() {
        if (current != null) web.evaluateJavascript("try{window.dispatchEvent(new CustomEvent('unireader:change'))}catch(e){}", null)
    }

    fun close() {
        current?.let { w -> web.url?.let { u -> pathOf(Uri.parse(u))?.let { prefs().edit().putString(w.id.uppercase(Locale.ROOT), it).apply() } } }
        view.visibility = View.GONE
    }

    /** 宿主的返回键：网页里能退就退。 */
    fun goBack(): Boolean {
        if (view.visibility != View.VISIBLE || !web.canGoBack()) return false
        web.goBack()
        return true
    }

    private fun updateTitle() {
        val name = current?.title ?: ""
        val page = web.title ?: ""
        titleView.text = if (page.isEmpty() || page == name || page.startsWith("https://")) name else "$name — $page"
        backBtn.isEnabled = web.canGoBack()
        fwdBtn.isEnabled = web.canGoForward()
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f
        fwdBtn.alpha = if (web.canGoForward()) 1f else 0.35f
        val canHome = !onStartPage()
        homeBtn.isEnabled = canHome
        homeBtn.alpha = if (canHome) 1f else 0.35f
    }

    private fun goHome() {
        val w = current ?: return
        if (hostId != w.id.lowercase(Locale.ROOT)) return   // 还没装上这一份
        web.loadUrl(urlOf(hostId, w.entry))
    }

    /** 眼下是不是就在首页（同一个文件；页内 `#锚点` 不算离开；`/`、`chapter/` 这种目录地址按它的 index 认，同 Mac）。 */
    private fun onStartPage(): Boolean {
        val w = current ?: return false
        val p = web.url?.let { pathOf(Uri.parse(it)) } ?: return false
        return WebAssets.resolve(p, mapOf(w.entry to WebAssets.Entry(w.entry, "", 0))) != null
    }

    private fun prefs() = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)

    private fun urlOf(host: String, path: String): String =
        "https://$host$HOST_SUFFIX/" + path.split('/').joinToString("/") { Uri.encode(it) }

    /** 本网页的地址 → 相对路径（不是本网页的 = null）。 */
    private fun pathOf(u: Uri): String? {
        val host = u.host ?: return null
        if (host != hostId + HOST_SUFFIX) return null
        return (u.path ?: "/").trimStart('/')
    }

    // ---------- 拦截：从 Web/ 读文件 ----------

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            val host = u.host ?: return null
            if (!host.endsWith(HOST_SUFFIX)) return null   // 外面的网站（CDN 之类）照常走网络
            if (host != hostId + HOST_SUFFIX) return notFound("This page belongs to another web page.")
            return serve((u.path ?: "/").trimStart('/'), request.requestHeaders?.entries?.firstOrNull {
                it.key.equals("Range", ignoreCase = true)
            }?.value)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.host?.endsWith(HOST_SUFFIX) == true) return false
            val scheme = u.scheme?.lowercase() ?: return false
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
            // 主框架点到外面：交给系统浏览器；子框架照常
            if (!request.isForMainFrame) return false
            runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, u)) }
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            updateTitle()
            val w = current ?: return
            url?.let { pathOf(Uri.parse(it)) }?.let { prefs().edit().putString(w.id.uppercase(Locale.ROOT), it).apply() }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = updateTitle()
    }

    private fun notFound(msg: String) = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(msg.toByteArray()),
    )

    /** 在拦截线程上跑（不碰界面、不碰库）。 */
    private fun serve(rawPath: String, range: String?): WebResourceResponse {
        val dir = folder ?: return notFound("No workspace")
        val e = WebAssets.resolve(rawPath, files) ?: return notFound("Not found: $rawPath")
        val f = WebAssets.file(dir, e.h)
        if (!f.isFile) return notFound("This file has not been downloaded to this device yet: ${e.p}")
        val mime = WebAssets.mimeType(e.p)
        val enc = if (WebAssets.isText(mime)) "utf-8" else null
        val size = f.length()
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-cache")
        val r = WebAssets.byteRange(range, size)
        if (r != null) {
            headers["Content-Range"] = "bytes ${r.first}-${r.last}/$size"
            headers["Content-Length"] = (r.last - r.first + 1).toString()
            return WebResourceResponse(mime, enc, 206, "Partial Content", headers, rangeStream(f, r))
        }
        if (WebAssets.isHtml(e.p)) {
            val html = f.readBytes()
            val out = WebBridge.inject(WebBridge.source(current?.id ?: hostId, current?.title ?: "", token) + "\n" +
                WebBridge.scrollSource(token), html)
            headers["Content-Length"] = out.size.toString()
            return WebResourceResponse(mime, "utf-8", 200, "OK", headers, ByteArrayInputStream(out))
        }
        headers["Content-Length"] = size.toString()
        return WebResourceResponse(mime, enc, 200, "OK", headers, FileInputStream(f))
    }

    private fun rangeStream(f: File, r: LongRange): InputStream {
        val input = FileInputStream(f)
        input.skip(r.first)
        var left = r.last - r.first + 1
        return object : InputStream() {
            override fun read(): Int {
                if (left <= 0) return -1
                val b = input.read()
                if (b >= 0) left--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = input.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() = input.close()
        }
    }

    // ---------- window.unireader ----------

    private inner class Bridge {
        /** 页面经注入的脚本同步调过来（在 WebView 的 JS 桥线程上，不是主线程）：排进库队列、等它做完。 */
        @JavascriptInterface
        fun call(tok: String?, json: String?): String {
            if (tok == null || tok != token) return err("unireader is only available to the web page itself")
            val q = queue ?: return err("No workspace is open.")
            val id = current?.id ?: return err("No web page is open.")
            val o = runCatching { JSONObject(json ?: "") }.getOrNull() ?: return err("bad request")
            val op = o.optString("op")
            val key = if (o.has("key")) o.optString("key") else null
            val value = if (o.has("value")) o.optString("value") else null
            val latch = CountDownLatch(1)
            var out: String = err("timed out")
            q.submit("网页数据 $op") { s ->
                out = try {
                    JSONObject().put("v", WebKV.handle(s, id, op, key, value)).toString()
                } catch (e: Exception) {
                    err(e.message ?: "failed")
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(10, TimeUnit.SECONDS)) Log.w(TAG, "网页数据 $op 等库队列超时")
            return out
        }

        private fun err(m: String) = JSONObject().put("e", m).toString()

        /** 滚动位置脚本问「这次要不要滚回去」：口令对得上、打开 / 重新载入后第一次问才给；没有 = 空串。
         *  到这时才读记忆：旧页面卸下时（`pagehide`）补报的最后那一下排在新页面的询问之前。 */
        @JavascriptInterface
        fun scrollTarget(tok: String?): String {
            val s = scrollSlot ?: return ""
            if (tok == null || tok != s.token || !s.take()) return ""
            return prefs().getString(SCROLL_PREFIX + s.id, null) ?: ""
        }

        /** 滚动位置脚本停手报一次位置：清洗后记进本机 prefs（不同步）。 */
        @JavascriptInterface
        fun scrollSave(tok: String?, json: String?) {
            val s = scrollSlot ?: return
            if (tok == null || tok != s.token) return
            val clean = WebBridge.sanitizeScroll(json) ?: return
            prefs().edit().putString(SCROLL_PREFIX + s.id, clean).apply()
        }
    }
}

/** 注入页面的 `window.unireader`（同 Mac `WebBridgeScript`，传输换成 `__unireaderNative.call(口令, JSON)`）。 */
object WebBridge {
    fun source(webId: String, title: String, token: String): String {
        val info = JSONObject().put("id", webId).put("title", title).toString().replace("</", "<\\/")
        val tok = JSONObject.quote(token)
        return """
        (function () {
          if (window.unireader) return;
          var call = function (m) {
            return new Promise(function (resolve, reject) {
              try {
                var r = JSON.parse(window.__unireaderNative.call($tok, JSON.stringify(m)));
                if (r.e !== undefined) reject(new Error(r.e)); else resolve(r.v);
              } catch (e) { reject(e); }
            });
          };
          function req(op, key, value) {
            var m = { op: op };
            if (key !== undefined) m.key = String(key);
            if (value !== undefined) m.value = value;
            return call(m).then(function (t) { return JSON.parse(t); });
          }
          var api = {
            version: 2,
            page: $info,
            get: function (key, fallback) {
              return req('get', key).then(function (v) { return (v === null && fallback !== undefined) ? fallback : v; });
            },
            set: function (key, value) {
              if (value === undefined) return api.remove(key);
              var s = JSON.stringify(value);
              if (s === undefined) return Promise.reject(new Error('unireader.set: the value cannot be stored as JSON'));
              return req('set', key, s).then(function () {});
            },
            remove: function (key) { return req('remove', key).then(function () {}); },
            keys: function () { return req('keys'); },
            getAll: function () { return req('getAll'); },
            clear: function () { return req('clear').then(function () {}); },
            query: function (name, args) { return req('query', name, args === undefined ? '{}' : JSON.stringify(args)); },
            documents: function () { return api.query('documents'); },
            exercises: function (opts) { return api.query('exercises', opts || {}); },
            exercise: function (id) { return api.query('exercise', { id: id }); },
            exerciseStats: function (opts) { return api.query('exerciseStats', opts || {}); },
            attempts: function (opts) { return api.query('attempts', opts || {}); }
          };
          Object.freeze(api);
          Object.defineProperty(window, 'unireader', { value: api, enumerable: true });
          try { window.dispatchEvent(new CustomEvent('unireader:ready')); } catch (e) {}
        })();
        """.trimIndent()
    }

    /**
     * 记住滚动位置、重新打开时滚回去（同 Mac `WebBridgeScript.scrollSource`，**JS 逐字相同，只差两行传输**；行为说明在 Mac 那份，
     * 测试 `../spike/web-scroll-test.swift`）。只在主框架跑；经 `__unireaderNative.scrollSave` / `scrollTarget` 带口令调过来。
     */
    fun scrollSource(token: String): String {
        val tok = JSONObject.quote(token)
        return """
        (function () {
          if (window.top !== window || window.__unireaderScroll) return;
          try { Object.defineProperty(window, '__unireaderScroll', { value: true }); } catch (e) { return; }
          var post = function (o) { window.__unireaderNative.scrollSave($tok, JSON.stringify(o)); },
              ask = function () { var t = window.__unireaderNative.scrollTarget($tok); return t ? JSON.parse(t) : null; };
          var busy = true, timer = 0, main = '';
          var EV = ['wheel', 'touchstart', 'pointerdown', 'keydown'];
          function sel(el) {
            var parts = [];
            while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
              if (el.id) {
                parts.unshift('#' + (window.CSS && CSS.escape ? CSS.escape(el.id) : el.id));
                return parts.join('>');
              }
              var i = 1, s = el;
              while ((s = s.previousElementSibling)) i++;
              parts.unshift(el.tagName.toLowerCase() + ':nth-child(' + i + ')');
              el = el.parentElement;
            }
            parts.unshift('body');
            return parts.join('>');
          }
          function find(s) { try { return s ? document.querySelector(s) : null; } catch (e) { return null; } }
          function flush() {
            timer = 0;
            if (busy) return;
            var o = { p: location.pathname, x: Math.round(window.scrollX), y: Math.round(window.scrollY) };
            var el = find(main);
            if (el) { o.s = main; o.sx = Math.round(el.scrollLeft); o.sy = Math.round(el.scrollTop); }
            try { post(o); } catch (e) {}
          }
          document.addEventListener('scroll', function (e) {
            if (busy) return;
            var t = e.target;
            if (t && t.nodeType === 1 && t.clientHeight >= window.innerHeight * 0.5) main = sel(t);
            if (!timer) timer = setTimeout(flush, 300);
          }, { capture: true, passive: true });
          window.addEventListener('pagehide', function () { if (timer) { clearTimeout(timer); flush(); } });
          function near(a, b) { return Math.abs(a - (b || 0)) < 2; }
          function at(t) {
            var ok = true;
            if (!near(window.scrollX, t.x) || !near(window.scrollY, t.y)) { window.scrollTo(t.x || 0, t.y || 0); ok = false; }
            if (t.s) {
              var el = find(t.s);
              if (!el) return false;
              main = t.s;
              if (!near(el.scrollLeft, t.sx) || !near(el.scrollTop, t.sy)) { el.scrollLeft = t.sx || 0; el.scrollTop = t.sy || 0; ok = false; }
            }
            return ok;
          }
          function done() {
            busy = false;
            EV.forEach(function (n) { window.removeEventListener(n, done, true); });
          }
          function restore(t) {
            if (!t || typeof t !== 'object' || t.p !== location.pathname || !(t.x || t.y || t.sx || t.sy)) { done(); return; }
            EV.forEach(function (n) { window.addEventListener(n, done, true); });
            var end = Date.now() + 6000, calm = 0;
            (function step() {
              if (!busy) return;
              if (at(t) && document.readyState === 'complete') calm++; else calm = 0;
              if (calm >= 5 || Date.now() > end) { done(); return; }
              setTimeout(step, 100);
            })();
          }
          function start() {
            var p;
            try { p = Promise.resolve(ask()); } catch (e) { p = Promise.resolve(null); }
            p.then(restore, done);
          }
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
          else start();
        })();
        """.trimIndent()
    }

    /** 页面报上来的位置 → 能存的 JSON（只留认识的键、数字夹在合理范围；`p` 不对就不要），同 Mac `WebBridgeScript.sanitizeScroll`。 */
    fun sanitizeScroll(json: String?): String? {
        if (json == null) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val p = o.opt("p") as? String ?: return null
        if (p.isEmpty() || p.toByteArray().size > 4096) return null
        val out = JSONObject().put("p", p)
        for (k in arrayOf("x", "y", "sx", "sy")) {
            val n = (o.opt(k) as? Number)?.toDouble() ?: continue
            if (n.isFinite()) out.put(k, Math.round(n).coerceIn(0L, 10_000_000L))
        }
        val s = o.opt("s") as? String
        if (!s.isNullOrEmpty() && s.toByteArray().size <= 1024) out.put("s", s)
        return out.toString()
    }

    /** 塞进 `<head>` 开头（不分大小写），没有 head 就放最前面（同 Mac `WebBridgeScript.inject`）。 */
    fun inject(script: String, html: ByteArray): ByteArray {
        val tag = "<script>$script</script>".toByteArray()
        val window = minOf(html.size, 64 * 1024)
        val head = String(html, 0, window, Charsets.ISO_8859_1).lowercase(Locale.ROOT)   // 单字节解码：下标 = 字节下标
        val at = head.indexOf("<head")
        if (at >= 0) {
            val gt = head.indexOf('>', at)
            if (gt >= 0) {
                return html.copyOfRange(0, gt + 1) + tag + html.copyOfRange(gt + 1, html.size)
            }
        }
        return tag + html
    }
}
