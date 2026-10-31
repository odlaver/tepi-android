package io.github.odlaver.tepi

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

// On-device translation; models download once, text never leaves
object Translator {
    fun target(): String {
        val lang = Locale.getDefault().language.let { if (it == "in") "id" else it }
        return TranslateLanguage.fromLanguageTag(lang) ?: TranslateLanguage.ENGLISH
    }

    fun run(context: Context, text: String, askDownload: (Long, () -> Unit) -> Unit, done: (String?, String?) -> Unit) {
        LanguageIdentification.getClient().identifyLanguage(text)
            .addOnSuccessListener { tag ->
                val src = TranslateLanguage.fromLanguageTag(if (tag == "und") "en" else tag) ?: TranslateLanguage.ENGLISH
                var dst = target()
                if (src == dst) dst = if (dst == TranslateLanguage.ENGLISH) TranslateLanguage.INDONESIAN else TranslateLanguage.ENGLISH
                val mm = RemoteModelManager.getInstance()
                val need = listOf(src, dst).filter { it != TranslateLanguage.ENGLISH }.map { TranslateRemoteModel.Builder(it).build() }
                mm.getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener { have ->
                    val missing = need.filter { m -> have.none { it.language == m.language } }
                    val go = {
                        val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
                        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                            .addOnSuccessListener {
                                tr.translate(text).addOnSuccessListener { out -> done(out, "$src → $dst"); tr.close() }
                                    .addOnFailureListener { done(null, null); tr.close() }
                            }
                            .addOnFailureListener { done(null, null); tr.close() }
                        Unit
                    }
                    if (missing.isEmpty()) go() else askDownload(missing.size * 30L, go)
                }.addOnFailureListener { done(null, null) }
            }
            .addOnFailureListener { done(null, null) }
    }
}

// Text selection: Translate (Tepi)
class TranslateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: run { finish(); return }
        Translator.run(this, text.take(5000), askDownload = { mb, go ->
            AlertDialog.Builder(this)
                .setTitle(R.string.tr_download)
                .setMessage(getString(R.string.tr_download_sub, mb))
                .setPositiveButton(R.string.tr_download_ok) { _, _ -> go() }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }) { out, pair ->
            if (out == null) {
                android.widget.Toast.makeText(this, R.string.tr_failed, android.widget.Toast.LENGTH_SHORT).show()
            } else if (!Listener.translated(text, out, pair ?: "")) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tepi", out))
                android.widget.Toast.makeText(this, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
