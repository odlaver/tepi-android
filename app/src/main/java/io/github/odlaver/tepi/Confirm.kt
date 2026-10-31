package io.github.odlaver.tepi

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal

// Fingerprint or screen lock before an Allow goes out
class Confirm : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(ID, -1)
        val what = intent.getStringExtra(WHAT).orEmpty()
        val pc = Listener.instance?.pc
        if (id < 0 || pc == null) { finish(); return }
        val b = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.allow_title))
            .setSubtitle(what.take(120))
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                pc.decide(id, "allow")
                finish()
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence?) = finish()
        })
    }

    companion object {
        const val ID = "id"
        const val WHAT = "what"
    }
}
