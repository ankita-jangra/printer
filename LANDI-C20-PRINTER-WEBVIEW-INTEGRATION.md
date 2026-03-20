# Landi C20 Pro – Add Internal Printer for Web/Chrome Printing

This guide explains how to add the Landi C20 Pro **internal (built-in) printer** so it can be used from a web app running in Chrome. The internal printer is only accessible via **OmniDriver SDK** in a native Android app, so you need a small wrapper app that loads your web content and bridges print requests to OmniDriver.

## Implementation Status

The **LandiPrintApp** project in this folder implements this integration:

- `PrinterHelper.kt` – OmniDriver printer logic
- `MainActivity.kt` – WebView with `AndroidBridge.printReceipt()` JavaScript bridge
- `activity_main.xml` – WebView layout
- `assets/index.html` – Test page with a Print Receipt button

**Before building**: Place the OmniDriver AAR in `LandiPrintApp/app/libs/` (see `libs/README.txt`).

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  Your Web App (Chrome / WebView)                             │
│  - Calls: window.AndroidBridge.printReceipt(htmlOrText)      │
└──────────────────────────┬──────────────────────────────────┘
                           │ JavaScript Bridge
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Native Android App (WebView + OmniDriver)                   │
│  - Receives print request                                    │
│  - Uses OmniDriver: getPrinter() → openDevice() → print      │
└──────────────────────────┬──────────────────────────────────┘
                           │ OmniDriver SDK
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Landi C20 Pro Internal Printer (built-in)                  │
└─────────────────────────────────────────────────────────────┘
```

---

## Step 1: Get OmniDriver SDK from Landi

1. Go to **Landi Document Center**: https://www.landiglobal.com/super/doc_center  
2. Or contact **Development Technical Support**: https://www.landiglobal.com/support#Development  
3. Request the **OmniDriver SDK** for C20 Pro (AAR/JAR and documentation)  
4. Landi also has sample code at: `git.landiglobal.com/sample-code/android/omnidriver` (requires access)

---

## Step 2: Create Android Project

Create a new Android project (or use existing) with:

- **Minimum SDK**: 21 or higher  
- **Target SDK**: 33+ (match your C20 Pro Android version)

### Add OmniDriver dependency

In `app/build.gradle`:

```gradle
dependencies {
    // Add OmniDriver - use the AAR/JAR path Landi provides
    implementation files('libs/omnidriver.aar')  // or .jar
    // OR if they publish to Maven:
    // implementation 'com.landi:omnidriver:x.x.x'
    
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'androidx.webkit:webkit:1.8.0'
}
```

Place the OmniDriver AAR/JAR in `app/libs/` if using local files.

---

## Step 3: Implement OmniDriver Printer Logic

Based on the Landi documentation. **Note**: Some C20 devices use SDK Suite OmniDriver (`com.sdksuite.omnidriver.api`) instead of `com.landi.omnidriver` – adjust imports to match your SDK.

```kotlin
// PrinterHelper.kt
package com.landiprint.app

import android.os.Bundle
import com.sdksuite.omnidriver.api.OmniDriver
import com.sdksuite.omnidriver.api.OmniDriverException
import com.sdksuite.omnidriver.api.Printer
import com.sdksuite.omnidriver.api.PrinterException

class PrinterHelper(private val omniDriver: OmniDriver) {

    private var printer: Printer? = null

    fun openPrinter(): Boolean {
        return try {
            printer = omniDriver.getPrinter(Bundle())
            printer?.openDevice()
            true
        } catch (e: OmniDriverException) {
            e.printStackTrace()
            // Service not bound or disconnected - reconnect
            false
        } catch (e: PrinterException) {
            e.printStackTrace()
            false
        }
    }

    fun printText(text: String): Boolean {
        if (printer == null && !openPrinter()) return false
        return try {
            printer?.printText(text)
            true
        } catch (e: PrinterException) {
            e.printStackTrace()
            false
        }
    }

    fun closePrinter() {
        try {
            printer?.closeDevice()
        } catch (e: PrinterException) {
            e.printStackTrace()
        }
        printer = null
    }
}
```

> **Note**: Actual method names (`printText`, etc.) depend on Landi’s OmniDriver API. Check their docs for the correct calls (e.g. `printRawData`, `printBitmap`, etc.).

---

## Step 4: Create WebView with JavaScript Bridge

```kotlin
// MainActivity.kt
package com.landiprint.app

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdksuite.omnidriver.api.OmniDriver

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var omniDriver: OmniDriver
    private lateinit var printerHelper: PrinterHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize OmniDriver (bind to Landi/SDK Suite service)
        omniDriver = OmniDriver(this)
        printerHelper = PrinterHelper(omniDriver)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()

        // Add JavaScript bridge - web page can call AndroidBridge.printReceipt(...)
        webView.addJavascriptInterface(PrintBridge(), "AndroidBridge")

        // Load your web app - file:///android_asset/index.html or your URL
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        printerHelper.closePrinter()
        super.onDestroy()
    }

    inner class PrintBridge {

        @JavascriptInterface
        fun printReceipt(htmlOrText: String) {
            runOnUiThread {
                val success = printerHelper.openPrinter()
                if (success) {
                    // Convert HTML to plain text or use Landi's print API for formatted output
                    printerHelper.printText(htmlOrText)
                    printerHelper.closePrinter()
                    Toast.makeText(this@MainActivity, "Printed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Printer error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
```

---

## Step 5: Layout

```xml
<!-- res/layout/activity_main.xml -->
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <WebView
        android:id="@+id/webView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</FrameLayout>
```

---

## Step 6: Call from Your Web App

In your web page (the one loaded in the WebView):

```javascript
function printOnLandiPrinter() {
    // Check if we're inside the Landi app (WebView with bridge)
    if (typeof window.AndroidBridge !== 'undefined') {
        const receiptContent = `
            ================================
            YOUR STORE NAME
            ================================
            Order #12345
            Item 1        $10.00
            Item 2        $5.00
            --------------------------------
            Total         $15.00
            ================================
            Thank you!
        `;
        window.AndroidBridge.printReceipt(receiptContent);
    } else {
        // Fallback: use browser print (won't reach internal printer)
        window.print();
    }
}
```

---

## Step 7: Bind OmniDriver Service

OmniDriver usually requires binding to a Landi system service. In `AndroidManifest.xml`:

```xml
<!-- Add if Landi requires it -->
<uses-permission android:name="android.permission.INTERNET" />
```

You may need to bind the OmniDriver service in `onCreate` – refer to Landi’s sample app for the exact binding code.

---

## Summary Checklist

| Step | Action |
|------|--------|
| 1 | Get OmniDriver SDK from Landi (Document Center or support) |
| 2 | Create Android app, add OmniDriver AAR/JAR |
| 3 | Implement `PrinterHelper` with `getPrinter()`, `openDevice()`, `closeDevice()` |
| 4 | Add WebView + `addJavascriptInterface` for `AndroidBridge` |
| 5 | Load your web app URL in WebView |
| 6 | In web app, call `window.AndroidBridge.printReceipt(text)` when printing |

---

## Important Notes

- **Package names**: This project uses `com.landiprint.app`. Some OmniDriver SDKs use `com.sdksuite.omnidriver.api`, others use `com.landi.omnidriver` – adjust imports to match your SDK.
- **OmniDriver API**: Use Landi’s docs for exact method names (`printText`, `printRawData`, `printBitmap`, etc.).
- **Service binding**: If OmniDriver needs explicit service binding, follow their sample code.
- **Testing**: Install this app on the C20 Pro instead of using Chrome directly; your web app runs inside the WebView and can trigger the internal printer.
