package com.joyz.autoscrolljoy

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs

/**
 * Pendeteksi gestur kepala dari kamera depan memakai CameraX + ML Kit Face Detection.
 *
 * CATATAN PENTING: ML Kit Face Detection tidak memberi vektor arah pandangan mata (gaze).
 * Yang tersedia adalah sudut kepala (Euler X/Y/Z). Karena itu "melihat ke atas" di sini
 * diterjemahkan sebagai MENDONGAKKAN KEPALA sedikit (pitch naik), yang praktis dan cukup andal.
 *
 * Cara kerja:
 *  1. Frame diproses maksimal ~6 fps dengan resolusi kecil, supaya hemat baterai.
 *  2. Beberapa frame pertama dipakai untuk kalibrasi: sudut pitch "netral" saat Anda menonton.
 *  3. Saat pitch melebihi netral + ambang (diatur lewat Sensitivitas) dan bertahan [HOLD_MS],
 *     [onLookUp] dipanggil satu kali.
 *  4. Pemicu baru aktif lagi setelah kepala kembali ke posisi netral (histeresis) dan jeda
 *     [COOLDOWN_MS] lewat. Posisi netral ikut bergeser pelan, jadi tidak masalah bila ponsel
 *     dipegang dengan sudut berbeda.
 *
 * @param thresholdProvider sudut dongak (derajat) yang dibutuhkan; dibaca tiap frame.
 * @param onLookUp dipanggil di main thread saat gestur terdeteksi.
 */
class EyeTrackingAnalyzer(
    private val thresholdProvider: () -> Float,
    private val onLookUp: () -> Unit
) : ImageAnalysis.Analyzer {

    // ACCURATE dipilih karena sudut Euler X (pitch) lebih andal di mode ini. Biaya tetap kecil
    // karena resolusi rendah dan frame rate dibatasi.
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.25f)
            .build()
    )

    @Volatile
    private var closed = false

    private var lastFrameAt = 0L
    private var baseline = 0f
    private var calibrationFrames = 0
    private var holdStartedAt = 0L
    private var armed = true
    private var lastTriggerAt = 0L

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val mediaImage = imageProxy.image
        if (closed || mediaImage == null || now - lastFrameAt < MIN_FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastFrameAt = now

        try {
            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(input)
                .addOnSuccessListener { faces -> if (!closed) handleFaces(faces, now) }
                .addOnFailureListener { e -> Log.w(TAG, "Deteksi wajah gagal", e) }
                .addOnCompleteListener { imageProxy.close() }
        } catch (e: Exception) {
            // Mis. detektor sudah ditutup saat service berhenti.
            Log.w(TAG, "Frame dilewati", e)
            imageProxy.close()
        }
    }

    private fun handleFaces(faces: List<Face>, now: Long) {
        // Ambil wajah terbesar (paling dekat dengan kamera).
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        if (face == null) {
            holdStartedAt = 0L
            return
        }

        // Abaikan bila kepala menoleh ke samping terlalu jauh: sudut pitch jadi kurang akurat.
        if (abs(face.headEulerAngleY) > MAX_YAW_DEG) {
            holdStartedAt = 0L
            return
        }

        val pitch = face.headEulerAngleX * LOOK_UP_SIGN

        // Kalibrasi: rata-rata beberapa frame pertama sebagai posisi netral.
        if (calibrationFrames < CALIBRATION_FRAMES) {
            baseline = if (calibrationFrames == 0) pitch
            else baseline + (pitch - baseline) / (calibrationFrames + 1)
            calibrationFrames++
            return
        }

        val delta = pitch - baseline
        val threshold = thresholdProvider()

        // Zona netral: ikuti pergeseran pelan dan aktifkan kembali pemicu.
        if (delta < threshold * 0.5f) {
            baseline += (pitch - baseline) * BASELINE_ALPHA
            armed = true
            holdStartedAt = 0L
            return
        }

        if (!armed || delta < threshold) {
            holdStartedAt = 0L
            return
        }

        // Kepala sedang mendongak melewati ambang.
        if (holdStartedAt == 0L) {
            holdStartedAt = now
        } else if (now - holdStartedAt >= HOLD_MS && now - lastTriggerAt >= COOLDOWN_MS) {
            armed = false
            lastTriggerAt = now
            holdStartedAt = 0L
            onLookUp()
        }
    }

    /** Hentikan analisis dan lepaskan detektor. Aman dipanggil berulang. */
    fun close() {
        if (closed) return
        closed = true
        detector.close()
    }

    private companion object {
        const val TAG = "EyeTrackingAnalyzer"

        /** ~6-7 frame per detik. */
        const val MIN_FRAME_INTERVAL_MS = 150L

        /** Kepala harus mendongak selama ini sebelum dianggap gestur sengaja (anti-kedip/anti-goyang). */
        const val HOLD_MS = 200L

        const val COOLDOWN_MS = 1500L
        const val CALIBRATION_FRAMES = 8
        const val BASELINE_ALPHA = 0.1f
        const val MAX_YAW_DEG = 35f

        /**
         * Tanda arah pitch. Di ML Kit, headEulerAngleX positif = kepala mendongak (menghadap ke atas).
         * Bila di perangkat Anda arahnya terbalik (mendongak malah tidak memicu, menunduk memicu),
         * ubah nilai ini menjadi -1f.
         */
        const val LOOK_UP_SIGN = 1f
    }
}
