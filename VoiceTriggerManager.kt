package com.joyz.autoscrolljoy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Pemicu scroll lewat kata kunci suara memakai [SpeechRecognizer] bawaan Android.
 *
 * Mendengarkan terus-menerus: sesi pengenalan di-restart otomatis setiap kali selesai/timeout.
 * Hasil sementara (partial) ikut diperiksa supaya respons terasa cepat. Setelah kata kunci
 * cocok, ada jeda [COOLDOWN_MS] agar satu ucapan tidak memicu scroll dua kali.
 *
 * Semua pemanggilan SpeechRecognizer HARUS di main thread; kelas ini memastikannya.
 *
 * Keterbatasan bawaan SpeechRecognizer (bukan bug kode ini):
 *  - Di beberapa perangkat terdengar bunyi "bip" tiap sesi mulai, dan audio aplikasi lain
 *    (video yang sedang ditonton) bisa meredup/jeda sebentar karena audio focus.
 *  - Mikrofon ikut menangkap suara dari speaker ponsel; memakai headset mengurangi salah picu.
 *  - Bila tidak ada paket suara offline, pengenalan memakai layanan online penyedia suara perangkat.
 *
 * @param keywordsProvider daftar kata kunci (huruf kecil); dibaca tiap hasil, jadi perubahan
 *        di Pengaturan langsung berlaku.
 * @param onKeyword dipanggil di main thread dengan kata kunci yang cocok.
 */
class VoiceTriggerManager(
    private val context: Context,
    private val keywordsProvider: () -> List<String>,
    private val onKeyword: (String) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var consecutiveErrors = 0
    private var ignoreUntil = 0L

    fun start() {
        main.post {
            if (running) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "Layanan pengenalan suara tidak tersedia di perangkat ini")
                return@post
            }
            running = true
            consecutiveErrors = 0
            listen()
        }
    }

    fun stop() {
        main.post {
            running = false
            main.removeCallbacksAndMessages(null)
            destroyRecognizer()
        }
    }

    private fun listen() {
        if (!running) return
        try {
            val r = recognizer ?: createRecognizer().also { recognizer = it }
            r.startListening(buildIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai pengenalan suara", e)
            destroyRecognizer()
            scheduleRestart(backoffMs())
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            // Pengenal di perangkat (Android 13+): offline dan lebih hemat.
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(listener)
        return r
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal melepas recognizer", e)
        }
        recognizer = null
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delayMs)
    }

    private fun backoffMs(): Long = (500L * consecutiveErrors).coerceIn(500L, 5000L)

    // ---------------------------------------------------------------------------------------------
    // Pencocokan kata kunci
    // ---------------------------------------------------------------------------------------------

    /** @return true bila ada kata kunci yang cocok dan scroll sudah diminta. */
    private fun handleTexts(texts: List<String>?): Boolean {
        if (texts.isNullOrEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        if (now < ignoreUntil) return false

        val keywords = keywordsProvider().map { normalize(it) }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return false

        for (text in texts) {
            val padded = " ${normalize(text)} "
            val hit = keywords.firstOrNull { padded.contains(" $it ") } ?: continue
            ignoreUntil = now + COOLDOWN_MS
            onKeyword(hit)
            return true
        }
        return false
    }

    /** Huruf kecil, hanya huruf/angka, spasi tunggal. Cocok per kata utuh ("b" tidak cocok dengan "baik"). */
    private fun normalize(s: String): String =
        s.lowercase()
            .replace(NON_WORD, " ")
            .trim()

    // ---------------------------------------------------------------------------------------------
    // Listener
    // ---------------------------------------------------------------------------------------------

    private val listener = object : RecognitionListener {

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (handleTexts(texts)) {
                // Sudah cocok: hentikan sesi ini, mulai sesi baru setelah jeda singkat.
                try {
                    recognizer?.cancel()
                } catch (e: Exception) {
                    Log.w(TAG, "cancel gagal", e)
                }
                scheduleRestart(RESTART_AFTER_HIT_MS)
            }
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            handleTexts(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Normal: tidak ada yang bicara. Cukup mulai lagi.
                    consecutiveErrors = 0
                    scheduleRestart(RESTART_DELAY_MS)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.w(TAG, "Izin mikrofon belum diberikan; pengenalan suara dihentikan")
                    running = false
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    consecutiveErrors++
                    try {
                        recognizer?.cancel()
                    } catch (e: Exception) {
                        Log.w(TAG, "cancel gagal", e)
                    }
                    scheduleRestart(backoffMs())
                }

                else -> {
                    // Jaringan/server/klien bermasalah: buat ulang recognizer dengan jeda bertahap.
                    consecutiveErrors++
                    Log.w(TAG, "Error pengenalan suara: $error")
                    destroyRecognizer()
                    scheduleRestart(backoffMs())
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "VoiceTriggerManager"
        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        const val COOLDOWN_MS = 1200L
        const val RESTART_DELAY_MS = 150L
        const val RESTART_AFTER_HIT_MS = 500L
    }
}
