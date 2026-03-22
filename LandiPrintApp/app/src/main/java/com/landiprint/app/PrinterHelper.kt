package com.landiprint.app

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
// OmniDriver SDK - xsuite-omnidriver-api AAR
import com.sdksuite.omnidriver.OmniDriver
import com.sdksuite.omnidriver.api.OmniDriverException
import com.sdksuite.omnidriver.api.Printer
import com.sdksuite.omnidriver.api.PrinterException

/**
 * Helper class to manage Landi C20 Pro internal printer via OmniDriver SDK.
 * Uses addText + startPrint (Printer API). startPrint is async - result via callback.
 */
class PrinterHelper(private val omniDriver: OmniDriver) {

    private var printer: Printer? = null

    fun openPrinter(): Boolean {
        return try {
            printer = omniDriver.getPrinter(Bundle())
            if (printer == null) {
                Log.e(TAG, "getPrinter returned null - OmniDriver service may not be available")
                return false
            }
            printer?.openDevice(0)
            Log.d(TAG, "Printer opened")
            true
        } catch (e: OmniDriverException) {
            Log.e(TAG, "OmniDriver error (service not found? Not a Landi C20?)", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Printer open error", e)
            false
        }
    }

    /**
     * Print text. Result is async - callback invoked from OnPrintListener.
     * Callback: (success, errorCode) - errorCode is non-null when success is false.
     */
    fun printText(text: String, onResult: (success: Boolean, errorCode: Int?) -> Unit) {
        if (text.isBlank()) {
            Log.w(TAG, "printText called with empty content")
            onResult(false, null)
            return
        }
        if (printer == null && !openPrinter()) {
            onResult(false, null)
            return
        }
        val p = printer
        if (p == null) {
            Log.e(TAG, "Printer is null after openPrinter")
            onResult(false, null)
            return
        }
        try {
            // Use default font (0,0) - larger font caused only one line to print
            p.addText(text, 0, 0)
            p.cutPaper()
            p.startPrint(object : com.sdksuite.omnidriver.api.OnPrintListener {
                override fun onSuccess() {
                    Log.d(TAG, "Print onSuccess")
                    onResult(true, null)
                }
                override fun onFail(error: Int) {
                    Log.e(TAG, "Print onFail with code: $error")
                    onResult(false, error)
                }
            })
        } catch (e: PrinterException) {
            Log.e(TAG, "Print exception", e)
            onResult(false, null)
        } catch (e: Exception) {
            Log.e(TAG, "Print error", e)
            onResult(false, null)
        }
    }

    /**
     * Print bitmap image. OmniDriver may support addImage(BitmapWrapper, int, int) or similar.
     * Scales image to receipt width (384px typical) and tries to print.
     */
    fun printBitmap(bitmap: Bitmap, onResult: (success: Boolean, errorCode: Int?) -> Unit) {
        if (printer == null && !openPrinter()) {
            onResult(false, null)
            return
        }
        val p = printer ?: run { onResult(false, null); return }
        try {
            val receiptWidth = 384
            val scale = receiptWidth.toFloat() / bitmap.width
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                receiptWidth,
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            try {
                p.javaClass.getMethod("addImage", Bitmap::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(p, scaled, 0, 0)
            } catch (e: NoSuchMethodException) {
                try {
                    val bitmapWrapperClass = Class.forName("com.sdksuite.omnidriver.aidl.type.BitmapWrapper")
                    val bw = bitmapWrapperClass.getConstructor(Bitmap::class.java).newInstance(scaled)
                    p.javaClass.getMethod("addImage", bitmapWrapperClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(p, bw, 0, 0)
                } catch (e2: Exception) {
                    Log.e(TAG, "addImage not found", e2)
                    onResult(false, null)
                    return
                }
            }
            if (scaled != bitmap) scaled.recycle()
            p.cutPaper()
            p.startPrint(object : com.sdksuite.omnidriver.api.OnPrintListener {
                override fun onSuccess() {
                    Log.d(TAG, "Print image onSuccess")
                    onResult(true, null)
                }
                override fun onFail(error: Int) {
                    Log.e(TAG, "Print image onFail: $error")
                    onResult(false, error)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Print bitmap error", e)
            onResult(false, null)
        }
    }

    fun closePrinter() {
        try {
            printer?.closeDevice()
        } catch (e: PrinterException) {
            Log.e(TAG, "Close error", e)
        }
        printer = null
    }

    companion object {
        private const val TAG = "PrinterHelper"
    }
}
