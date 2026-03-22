package com.landiprint.app

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sdksuite.omnidriver.OmniDriver
import com.sdksuite.omnidriver.OmniConnection

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var omniDriver: OmniDriver
    private lateinit var printerHelper: PrinterHelper
    private lateinit var prefs: SharedPreferences
    private var omniConnected = false

    private val defaultUrl = "file:///android_asset/index.html"
    private val prefsKeyUrl = "webview_url"

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
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = false
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectPrintInterceptor()
            }
        }

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
                        var sel = '.modal, .modal-body, .print-area, .receipt, .bill-content, .invoice, [class*="bill"], [class*="invoice"]';
                        var text = (document.body.innerText || document.body.textContent || '').trim();
                        for (var s of sel.split(', ')) {
                            var el = document.querySelector(s.trim());
                            if (el) {
                                var t = (el.innerText || el.textContent || '').trim();
                                if (t.length > 50) { text = t; break; }
                            }
                        }
                        if (text && text.trim().length > 0) {
                            AndroidBridge.printReceipt(text.trim());
                        } else {
                            AndroidBridge.printReceipt(document.body.innerText || document.body.textContent || 'No content');
                        }
                    } else if (origPrint) {
                        origPrint.call(window);
                    }
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SET_URL, 0, "Set URL").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_PRINT_PAGE, 0, "Print current page").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
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
        val script = """
            (function() {
                var selectors = '.modal, .modal-body, [role=dialog], .print-area, .receipt, #receipt, .bill-content, .invoice, [class*="bill"], [class*="Bill"], [class*="invoice"], [class*="Invoice"], [id*="bill"], [id*="invoice"], .voucher, .main-content, #content';
                var parts = selectors.split(', ');
                for (var i = 0; i < parts.length; i++) {
                    try {
                        var el = document.querySelector(parts[i].trim());
                        if (el) {
                            var t = (el.innerText || el.textContent || '').trim();
                            if (t.length > 50) return t;
                        }
                    } catch(e) {}
                }
                return (document.body.innerText || document.body.textContent || '').trim();
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            runOnUiThread {
                val content = result?.trim('"')?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim() ?: ""
                if (content.isNotBlank()) {
                    PrintBridge().printReceipt(content)
                } else {
                    Toast.makeText(this, "No content to print", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

    companion object {
        private const val TAG = "MainActivity"
        private const val MENU_SET_URL = 1
        private const val MENU_PRINT_PAGE = 2
    }

    override fun onDestroy() {
        printerHelper.closePrinter()
        try { omniDriver.destroy() } catch (e: Exception) { Log.e(TAG, "Destroy error", e) }
        super.onDestroy()
    }

    inner class PrintBridge {

        @android.webkit.JavascriptInterface
        fun printReceipt(htmlOrText: String?) {
            runOnUiThread {
                // Check for null or empty content
                if (htmlOrText.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "Nothing to print", Toast.LENGTH_SHORT).show()
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
