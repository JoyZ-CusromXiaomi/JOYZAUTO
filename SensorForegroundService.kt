package com.joyz.autoscrolljoy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service yang menjaga sensor (kamera depan + mikrofon) tetap boleh berjalan saat
 * aplikasi di latar belakang. Android mewajibkan notifikasi untuk ini.
 *
 * Hemat daya:
 *  - Sensor hanya menyala bila mode terkait aktif DAN layar menyala. Layar mati -> kamera & suara berhenti.
 *  - Analisis kamera dibatasi ~6 fps pada resolusi kecil (lihat [EyeTrackingAnalyzer]).
 *  - Tanpa sensor yang dibutuhkan, service langsung berhenti.
 *
 * PENTING: service ini harus dimulai dari layar aplikasi (MainActivity) saat aplikasi terlihat.
 * Android 11+ menolak akses kamera/mikrofon bagi foreground service yang baru dimulai dari
 * latar belakang. Service yang sudah berjalan boleh terus memakainya di latar belakang.
 */
class SensorForegroundService : LifecycleService() {

    private lateinit var prefs: ScrollPrefs

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: EyeTrackingAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private var voice: VoiceTriggerManager? = null

    private var wantEye = false
    private var wantVoice = false
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    applyEngines()
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    applyEngines()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = ScrollPrefs.get(this)
        createNotificationChannel()
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        wantEye = prefs.enabled && prefs.modeEye && hasPermission(this, Manifest.permission.CAMERA)
        wantVoice = prefs.enabled && prefs.modeVoice && hasPermission(this, Manifest.permission.RECORD_AUDIO)

        // Seharusnya tidak terjadi (sync() sudah memeriksa), tapi jaga-jaga bila pengaturan berubah.
        if (!wantEye && !wantVoice) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var type = 0
                if (wantEye) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (wantVoice) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menjadi foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        applyEngines()

        // Tidak sticky: bila proses dimatikan sistem, jangan dihidupkan lagi dari latar belakang
        // (Android akan menolak akses kamera/mikrofon). Pengguna cukup membuka aplikasi lagi.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // Sudah dilepas.
        }
        stopEye()
        stopVoice()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Mesin sensor
    // ---------------------------------------------------------------------------------------------

    private fun applyEngines() {
        if (wantEye && screenOn) startEye() else stopEye()
        if (wantVoice && screenOn) startVoice() else stopVoice()
    }

    private fun startEye() {
        if (analyzer != null) return

        val newAnalyzer = EyeTrackingAnalyzer(
            thresholdProvider = { prefs.eyeThresholdDegrees() },
            onLookUp = { ScrollBridge.requestScroll("eye") }
        )
        analyzer = newAnalyzer

        val executor = analysisExecutor
            ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            // Sudah dihentikan sebelum kamera siap.
            if (analyzer !== newAnalyzer) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                // Resolusi kecil cukup untuk sudut kepala dan jauh lebih hemat.
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(480, 360),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, newAnalyzer)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menyalakan kamera depan", e)
                stopEye()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopEye() {
        val current = analyzer ?: return
        analyzer = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal melepas kamera", e)
        }
        current.close()
    }

    private fun startVoice() {
        if (voice != null) return
        voice = VoiceTriggerManager(
            context = this,
            keywordsProvider = { prefs.keywordList },
            onKeyword = { keyword -> ScrollBridge.requestScroll("voice:$keyword") }
        ).also { it.start() }
    }

    private fun stopVoice() {
        voice?.stop()
        voice = null
    }

    // ---------------------------------------------------------------------------------------------
    // Notifikasi
    // ---------------------------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SensorForegroundService::class.java).setAction(ACTION_STOP),
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scroll)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text_sensors))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "SensorService"
        private const val CHANNEL_ID = "sensor_service"
        private const val NOTIF_ID = 1001

        private const val ACTION_SYNC = "com.joyz.autoscrolljoy.action.SYNC"
        private const val ACTION_STOP = "com.joyz.autoscrolljoy.action.STOP"

        private fun hasPermission(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /**
         * Selaraskan service dengan pengaturan saat ini: jalankan bila ada sensor yang dibutuhkan,
         * hentikan bila tidak. Panggil dari MainActivity (saat aplikasi terlihat).
         */
        fun sync(context: Context) {
            val prefs = ScrollPrefs.get(context)
            val needEye = prefs.enabled && prefs.modeEye &&
                hasPermission(context, Manifest.permission.CAMERA)
            val needVoice = prefs.enabled && prefs.modeVoice &&
                hasPermission(context, Manifest.permission.RECORD_AUDIO)

            val intent = Intent(context, SensorForegroundService::class.java)
            if (needEye || needVoice) {
                try {
                    ContextCompat.startForegroundService(context, intent.setAction(ACTION_SYNC))
                } catch (e: Exception) {
                    Log.w(TAG, "Tidak bisa memulai foreground service", e)
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
