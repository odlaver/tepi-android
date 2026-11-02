package io.github.odlaver.tepi

import android.app.Activity
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

// Google code scanner: Play services draws it, so no camera permission
object Scan {
    fun available(a: Activity): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(a) == ConnectionResult.SUCCESS
    } catch (_: Exception) { false }

    fun qr(a: Activity, done: (String?) -> Unit) {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(a, opts).startScan()
            .addOnSuccessListener { done(it.rawValue) }
            .addOnFailureListener { Toast.makeText(a, a.getString(R.string.qr_unavailable), Toast.LENGTH_SHORT).show() }
    }
}
