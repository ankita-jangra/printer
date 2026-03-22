package com.landiprint.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sdksuite.omnidriver.OmniDriver
import java.net.HttpURLConnection
import java.net.URL
import com.sdksuite.omnidriver.OmniConnection

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var omniDriver: OmniDriver
    private lateinit var printerHelper: PrinterHelper
    private lateinit var prefs: SharedPreferences
    private var omniConnected = false

    private val defaultUrl = "file:///android_asset/index.html"
    private val prefsKeyUrl = "webview_url"
    private val keepAliveIntervalMs = 4 * 60 * 1000L // 4 minutes
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            val url = webView.url ?: return
            if (url.startsWith("https://") || url.startsWith("http://")) {
                webView.evaluateJavascript(
                    "fetch(location.origin,{method:'HEAD',credentials:'same-origin'}).catch(function(){})",
                    null
                )
                Log.d(TAG, "Keep-alive ping")
            }
            keepAliveHandler.postDelayed(this, keepAliveIntervalMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize OmniDriver (bind to Landi/SDK Suite service)
        omniDriver = OmniDriver.me(this)
        printerHelper = PrinterHelper(omniDriver)

        // Must call init() before getPrinter - binds to OmniDriver system service
        omniDriver.init(object : OmniConnection {
            override fun onConnected() {
                runOnUiThread {
                    omniConnected = true
                    Log.d(TAG, "OmniDriver connected")
                }
            }
            override fun onDisconnected(errorCode: Int) {
                runOnUiThread {
                    omniConnected = false
                    Log.e(TAG, "OmniDriver disconnected, code: $errorCode")
                }
            }
        })

        webView = findViewById(R.id.webView)
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<Button>(R.id.btnPrint).setOnClickListener {
            Toast.makeText(this, "Printing...", Toast.LENGTH_SHORT).show()
            triggerPrintFromPage()
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            private fun handleTxtUrl(url: String): Boolean {
                if (!url.endsWith(".txt") || !url.startsWith("http")) return false
                Toast.makeText(this@MainActivity, "Printing...", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        conn.connect()
                        val text = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        if (text.length > 50) {
                            runOnUiThread { PrintBridge().printReceipt(text) }
                        }
                    } catch (e: Exception) { Log.e(TAG, "TXT fetch failed", e) }
                }.start()
                return true
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) =
                url != null && handleTxtUrl(url)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleTxtUrl(url)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectPrintInterceptor()
                findViewById<Button>(R.id.btnBack).visibility = if (webView.canGoBack()) View.VISIBLE else View.GONE
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    keepAliveHandler.removeCallbacks(keepAliveRunnable)
                    keepAliveHandler.postDelayed(keepAliveRunnable, keepAliveIntervalMs)
                }
            }
        }

        // Intercept TXT downloads and print; other downloads go to DownloadManager
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if ((mimeType?.contains("text/plain") == true || url.contains(".txt") || (contentDisposition?.contains(".txt") == true))
                && (url.startsWith("http://") || url.startsWith("https://"))
            ) {
                Thread {
                    try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", userAgent)
                        val cookie = CookieManager.getInstance().getCookie(url)
                        if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
                        conn.connect()
                        val text = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        runOnUiThread {
                            if (text.isNotBlank()) PrintBridge().printReceipt(text)
                            else Toast.makeText(this, "Downloaded file is empty", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Fetch failed", e)
                        runOnUiThread { Toast.makeText(this, "Could not get file to print", Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    val fileName = contentDisposition?.substringAfter("filename=")?.trim('"', '\'', ';')?.trim()
                        ?: url.substringAfterLast("/").takeIf { it.contains(".") } ?: "download"
                    val req = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    (getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "DownloadManager failed", e)
                    Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Handle Android back: go back in WebView history when possible
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else finish()
            }
        })

        // Add JavaScript bridge - web page can call AndroidBridge.printReceipt(...)
        webView.addJavascriptInterface(PrintBridge(), "AndroidBridge")

        // Load URL from app settings, or default to local test page
        prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        loadSavedUrl()
    }

    private fun loadSavedUrl() {
        val url = prefs.getString(prefsKeyUrl, defaultUrl) ?: defaultUrl
        webView.loadUrl(url)
    }

    private fun injectPrintInterceptor() {
        val script = """
            (function() {
                if (window._printIntercepted) return;
                window._printIntercepted = true;
                var origPrint = window.print;
                window.print = function() {
                    if (typeof AndroidBridge !== 'undefined' && AndroidBridge.printReceipt) {
                        var bodyText = (document.body.innerText || document.body.textContent || '').trim();
                        var text = bodyText;
                        var sel = '.modal, .modal-body, .print-area, .receipt, .bill-content, .invoice, [class*="bill"], [class*="invoice"]';
                        for (var s of sel.split(', ')) {
                            var nodes = document.querySelectorAll(s.trim());
                            for (var i = 0; i < nodes.length; i++) {
                                var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                                if (t.length > text.length && t.length > 100) text = t;
                            }
                        }
                        AndroidBridge.printReceipt(text || bodyText);
                    } else if (origPrint) origPrint.call(window);
                };
                var origCreateObjectURL = URL.createObjectURL;
                URL.createObjectURL = function(blob) {
                    if (blob && blob.size > 80 && typeof AndroidBridge !== 'undefined' && AndroidBridge.printReceipt) {
                        var isText = !blob.type || blob.type.indexOf('text') >= 0 || blob.type.indexOf('json') >= 0 || blob.type === '';
                        if (isText) {
                            var r = new FileReader();
                            r.onload = function() {
                                var t = (r.result || '').trim();
                                if (t.length > 80) AndroidBridge.printReceipt(t);
                            };
                            r.readAsText(blob);
                        }
                    }
                    return origCreateObjectURL.apply(URL, arguments);
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_BACK, 0, "Back").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_SET_URL, 0, "Set URL").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_PRINT_PAGE, 0, "Print current page").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_BACK -> {
                if (webView.canGoBack()) webView.goBack() else Toast.makeText(this, "No page to go back to", Toast.LENGTH_SHORT).show()
                return true
            }
            MENU_SET_URL -> {
                showSetUrlDialog()
                return true
            }
            MENU_PRINT_PAGE -> {
                triggerPrintFromPage()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun triggerPrintFromPage() {
        runExtractionAndPrint(isRetry = false)
    }

    private fun runExtractionAndPrint(isRetry: Boolean) {
        val script = """
            (function() {
                function getText(el) {
                    if (!el) return '';
                    try {
                        var t = (el.innerText || el.textContent || '').trim();
                        if (t) return t;
                        if (el.shadowRoot) return getText(el.shadowRoot);
                        return '';
                    } catch(e) { return ''; }
                }
                function getDocText(doc) {
                    if (!doc || !doc.body) return '';
                    return getText(doc.body) || getText(doc.documentElement) || '';
                }
                var text = '';
                var doc = document;
                text = getDocText(doc);
                if (!text || text.length < 30) {
                    var iframes = doc.querySelectorAll('iframe');
                    for (var i = 0; i < iframes.length; i++) {
                        try {
                            var fdoc = iframes[i].contentDocument || iframes[i].contentWindow?.document;
                            var ft = getDocText(fdoc);
                            if (ft && ft.length > (text || '').length) text = ft;
                        } catch(e) {}
                    }
                }
                if (!text || text.length < 30) {
                    var sel = '[class*="receipt"],[class*="bill"],[class*="invoice"],[class*="print"],[class*="view"],main,article,#content,.content';
                    var all = doc.querySelectorAll(sel);
                    for (var i = 0; i < all.length; i++) {
                        var t = getText(all[i]);
                        if (t && t.length > 50 && t.length > (text || '').length) text = t;
                    }
                }
                if (!text || text.length < 30) {
                    var walk = function(n) {
                        if (!n || n.nodeType !== 1) return '';
                        var t = getText(n);
                        if (t && t.length > 100) return t;
                        for (var c = n.firstChild; c; c = c.nextSibling) {
                            var ct = walk(c);
                            if (ct && ct.length > (t || '').length) t = ct;
                        }
                        return t || '';
                    };
                    text = walk(doc.body) || text;
                }
                if (typeof AndroidBridge !== 'undefined' && AndroidBridge.reportExtractionResult) {
                    AndroidBridge.reportExtractionResult((text || '').trim(), $isRetry);
                }
            })();
        """.trimIndent().replace("$isRetry", if (isRetry) "true" else "false")
        webView.evaluateJavascript(script, null)
    }

    private fun showSetUrlDialog() {
        val input = EditText(this).apply {
            setText(prefs.getString(prefsKeyUrl, defaultUrl))
            hint = "https://your-app.com or file:///android_asset/index.html"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Set Web URL")
            .setMessage("Enter the URL to load in the app. Leave default for built-in test page.")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    prefs.edit().putString(prefsKeyUrl, url).apply()
                    loadSavedUrl()
                    Toast.makeText(this, "URL set", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset to default") { _, _ ->
                prefs.edit().remove(prefsKeyUrl).apply()
                loadSavedUrl()
                Toast.makeText(this, "Reset to default", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Print exact bill shown: fetches original image if URL is .jpg/.png, else captures WebView.
     * Tries image print first (pixel-perfect), falls back to OCR if printer doesn't support images.
     */
    private fun printExactBill() {
        val url = webView.url ?: ""
        val isImageUrl = url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) ||
            url.endsWith(".png", true) || url.endsWith(".webp", true)

        if (isImageUrl && url.startsWith("http")) {
            Toast.makeText(this, "Printing exact bill...", Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    conn.connect()
                    val bytes = conn.inputStream.readBytes()
                    conn.disconnect()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw Exception("Could not decode image")
                    runOnUiThread { tryPrintExactThenOcr(bitmap) }
                } catch (e: Exception) {
                    Log.e(TAG, "Fetch bill image failed", e)
                    runOnUiThread { captureAndPrintFromView() }
                }
            }.start()
        } else {
            captureAndPrintFromView()
        }
    }

    private fun captureAndPrintFromView() {
        Toast.makeText(this, "Capturing bill...", Toast.LENGTH_SHORT).show()
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) {
            Toast.makeText(this, "Nothing to capture", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        tryPrintExactThenOcr(bitmap)
    }

    private fun tryPrintExactThenOcr(bitmap: Bitmap) {
        if (!omniConnected) {
            bitmap.recycle()
            Toast.makeText(this, "Printer not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val openSuccess = printerHelper.openPrinter()
        if (!openSuccess) {
            bitmap.recycle()
            Toast.makeText(this, "Printer error", Toast.LENGTH_SHORT).show()
            return
        }
        printerHelper.printBitmap(bitmap) { success, _ ->
            runOnUiThread {
                printerHelper.closePrinter()
                if (success) {
                    bitmap.recycle()
                    Toast.makeText(this, "Printed", Toast.LENGTH_SHORT).show()
                } else {
                    runOcrFallback(bitmap)
                }
            }
        }
    }

    private fun runOcrFallback(bitmap: Bitmap) {
        Toast.makeText(this, "Reading bill text...", Toast.LENGTH_SHORT).show()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                runOnUiThread {
                    bitmap.recycle()
                    if (text.length >= 30) {
                        printReceiptFromBridge(text)
                    } else {
                        Toast.makeText(this, "Could not read bill. Use Print from bill list.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .addOnFailureListener {
                runOnUiThread {
                    bitmap.recycle()
                    Toast.makeText(this, "Could not read bill.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun printReceiptFromBridge(text: String) {
        PrintBridge().printReceipt(text)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MENU_BACK = 0
        private const val MENU_SET_URL = 1
        private const val MENU_PRINT_PAGE = 2
    }

    override fun onPause() {
        super.onPause()
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized && (webView.url?.startsWith("http") == true)) {
            keepAliveHandler.postDelayed(keepAliveRunnable, keepAliveIntervalMs)
        }
    }

    override fun onDestroy() {
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        CookieManager.getInstance().flush()
        printerHelper.closePrinter()
        try { omniDriver.destroy() } catch (e: Exception) { Log.e(TAG, "Destroy error", e) }
        super.onDestroy()
    }

    inner class PrintBridge {

        @android.webkit.JavascriptInterface
        fun reportExtractionResult(text: String?, isRetry: Boolean) {
            runOnUiThread {
                if (!text.isNullOrBlank() && text.length >= 50) {
                    printReceipt(text)
                } else if (isRetry) {
                    printExactBill()
                } else {
                    webView.postDelayed({ runExtractionAndPrint(isRetry = true) }, 600)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun printReceipt(htmlOrText: String?) {
            runOnUiThread {
                // Check for null or empty content
                if (htmlOrText.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Nothing to print", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (htmlOrText.length < 50) {
                    Toast.makeText(this@MainActivity, "Content too short (${htmlOrText.length} chars). Ensure bill is visible.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (!omniConnected) {
                    Toast.makeText(this@MainActivity, "Printer not ready - wait a moment", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val openSuccess = printerHelper.openPrinter()
                if (!openSuccess) {
                    Toast.makeText(this@MainActivity, "Printer error - check OmniDriver is installed", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                printerHelper.printText(htmlOrText) { success, errorCode ->
                    runOnUiThread {
                        printerHelper.closePrinter()
                        when {
                            success -> Toast.makeText(this@MainActivity, "Printed", Toast.LENGTH_SHORT).show()
                            errorCode != null -> Toast.makeText(this@MainActivity, "Print failed (code $errorCode)", Toast.LENGTH_SHORT).show()
                            else -> Toast.makeText(this@MainActivity, "Print failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
