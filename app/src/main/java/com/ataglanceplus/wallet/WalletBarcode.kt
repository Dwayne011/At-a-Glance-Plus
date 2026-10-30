package com.ataglanceplus.wallet

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

/** Renders a pass's code to a black-on-white bitmap with ZXing, for the pop-up. */
object WalletBarcode {

    /** True for the square, two-dimensional codes (shown as a large square). */
    fun isSquare(format: WalletPass.Format): Boolean = when (format) {
        WalletPass.Format.QR, WalletPass.Format.AZTEC, WalletPass.Format.DATA_MATRIX -> true
        else -> false
    }

    /** Codes whose payload isn't meant to be read by people (shown as the code only). */
    fun isTwoDimensional(format: WalletPass.Format): Boolean =
        isSquare(format) || format == WalletPass.Format.PDF_417

    /** Null for text-only or unknown codes (the pop-up shows the value as text instead). */
    fun render(barcode: WalletPass.Barcode, size: Int): Bitmap? {
        val format = zxing(barcode.format) ?: return null
        val square = isSquare(barcode.format)
        val width = size
        val height = if (square) size else (size * 0.42f).toInt()
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to if (square) 1 else 10)
            val matrix: BitMatrix = MultiFormatWriter().encode(barcode.value, format, width, height, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        } catch (e: Exception) {
            null
        }
    }

    private fun zxing(format: WalletPass.Format): BarcodeFormat? = when (format) {
        WalletPass.Format.AZTEC -> BarcodeFormat.AZTEC
        WalletPass.Format.CODE_39 -> BarcodeFormat.CODE_39
        WalletPass.Format.CODE_128 -> BarcodeFormat.CODE_128
        WalletPass.Format.CODABAR -> BarcodeFormat.CODABAR
        WalletPass.Format.DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
        WalletPass.Format.EAN_8 -> BarcodeFormat.EAN_8
        WalletPass.Format.EAN_13 -> BarcodeFormat.EAN_13
        WalletPass.Format.ITF_14 -> BarcodeFormat.ITF
        WalletPass.Format.PDF_417 -> BarcodeFormat.PDF_417
        WalletPass.Format.QR -> BarcodeFormat.QR_CODE
        WalletPass.Format.UPC_A -> BarcodeFormat.UPC_A
        WalletPass.Format.UPC_E -> BarcodeFormat.UPC_E
        WalletPass.Format.TEXT_ONLY, WalletPass.Format.UNKNOWN -> null
    }
}
