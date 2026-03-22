package com.landiprint.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
            Log.e(TAG, "OmniDriver error (service not bound? Reconnect to service)", e)
            false
        } catch (e: PrinterException) {
            Log.e(TAG, "openDevice error: ${e.message}", e)
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
            // Single addText with full content - OmniDriver buffers and prints all
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
     * Convert to thermal-ready format: scale to receipt width, grayscale, high contrast.
     * Thermal printers often need monochrome-friendly images.
     */
    fun prepareForThermal(source: Bitmap, receiptWidth: Int = 384): Bitmap {
        val scale = receiptWidth.toFloat() / source.width
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, receiptWidth, targetH, true)
        if (scaled == source) return scaled
        val gray = Bitmap.createBitmap(receiptWidth, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0f)
                setScale(1.2f, 1.2f, 1.2f, 1f)
            })
        }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled != source) scaled.recycle()
        return gray
    }

    /**
     * Print bitmap - exact bill as shown. Uses addImage if supported.
     */
    fun printBitmap(bitmap: Bitmap, onResult: (success: Boolean, errorCode: Int?) -> Unit) {
        if (printer == null && !openPrinter()) {
            onResult(false, null)
            return
        }
        val p = printer ?: run { onResult(false, null); return }
        try {
            val thermal = prepareForThermal(bitmap)
            try {
                p.javaClass.getMethod("addImage", Bitmap::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(p, thermal, 0, 0)
            } catch (e: NoSuchMethodException) {
                try {
                    val bitmapWrapperClass = Class.forName("com.sdksuite.omnidriver.aidl.type.BitmapWrapper")
                    val bw = bitmapWrapperClass.getConstructor(Bitmap::class.java).newInstance(thermal)
                    p.javaClass.getMethod("addImage", bitmapWrapperClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(p, bw, 0, 0)
                } catch (e2: Exception) {
                    Log.e(TAG, "addImage not found", e2)
                    onResult(false, null)
                    return
                }
            }
            if (thermal != bitmap) thermal.recycle()
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
