package com.landiprint.app

import android.os.Bundle
import android.util.Log
// OmniDriver SDK - xsuite-omnidriver-api AAR
import com.sdksuite.omnidriver.OmniDriver
import com.sdksuite.omnidriver.api.OmniDriverException
import com.sdksuite.omnidriver.api.Printer
import com.sdksuite.omnidriver.api.PrinterException

/**
 * Helper class to manage Landi C20 Pro internal printer via OmniDriver SDK.
 * Uses addText + startPrint (Printer API), not printText.
 */
class PrinterHelper(private val omniDriver: OmniDriver) {

    private var printer: Printer? = null

    fun openPrinter(): Boolean {
        return try {
            printer = omniDriver.getPrinter(Bundle())
            printer?.openDevice(0)
            true
        } catch (e: OmniDriverException) {
            Log.e(TAG, "OmniDriver error", e)
            false
        } catch (e: PrinterException) {
            Log.e(TAG, "Printer error", e)
            false
        }
    }

    fun printText(text: String): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "printText called with empty content")
            return false
        }
        if (printer == null && !openPrinter()) return false
        return try {
            printer?.addText(text, 0, 0)
            printer?.startPrint(object : com.sdksuite.omnidriver.api.OnPrintListener {
                override fun onSuccess() {}
                override fun onFail(error: Int) {
                    Log.e(TAG, "Print failed with code: $error")
                }
            })
            true
        } catch (e: PrinterException) {
            Log.e(TAG, "Print error", e)
            false
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
