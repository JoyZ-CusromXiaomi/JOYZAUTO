# Auto Scroll Joy

Aplikasi Android native (Kotlin) yang men-scroll video pendek otomatis (TikTok, Instagram Reels, YouTube Shorts, Facebook Reels, Snapchat Spotlight, Kwai) lewat `AccessibilityService`.

## Cara build

**Android Studio (paling mudah)**
1. Buka folder ini lewat *File → Open*.
2. Tunggu Gradle sync selesai (butuh internet, JDK 17 bawaan Android Studio sudah cukup).
3. *Build → Build Bundle(s) / APK(s) → Build APK(s)*, atau tekan Run ke ponsel.

**Terminal**
```
./gradlew assembleDebug      # hasil: app/build/outputs/apk/debug/app-debug.apk
```

**GitHub Actions**: upload folder ini ke repo GitHub. Workflow di `.github/workflows/build.yml` otomatis membangun APK; ambil dari tab *Actions → Artifacts*.

Versi: AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21, compileSdk/targetSdk 35, minSdk 26 (Android 8.0).

## Cara pakai

1. Buka aplikasi → tekan **Aktifkan** di baris *Layanan Aksesibilitas* → pilih *Auto Scroll Joy* → nyalakan.
   - Android 13+ untuk APK di luar Play Store: bila opsinya abu-abu, buka *Info Aplikasi → ⋮ → Izinkan setelan terbatas*, lalu ulangi.
2. Pilih mode (boleh lebih dari satu): **Gerakan kepala**, **Timer**, **Perintah suara**. Izin kamera/mikrofon diminta saat mode dinyalakan.
3. Nyalakan **sakelar utama**, buka aplikasi video pendek.
4. *Pengaturan → Uji scroll* untuk mengecek gestur tanpa sensor.

## Struktur

```
app/src/main/java/com/joyz/autoscrolljoy/
  AutoScrollAccessibilityService.kt  swipe halus (dispatchGesture), filter aplikasi, mode timer
  SensorForegroundService.kt         kamera + mikrofon di latar belakang (foreground service)
  EyeTrackingAnalyzer.kt             CameraX + ML Kit Face Detection (gerakan kepala)
  VoiceTriggerManager.kt             SpeechRecognizer, kata kunci custom
  ScrollPrefs.kt / ScrollBridge.kt   pengaturan & jembatan sensor → service
  MainActivity.kt / SettingsActivity.kt
app/src/main/res/                    layout, tema Material 3 flat terang/gelap, ikon
```

## Batasan yang perlu diketahui

- **Eye-tracking = gerakan kepala.** ML Kit tidak menyediakan arah pandangan mata, hanya sudut kepala. Pemicunya adalah mendongak sedikit. Jika arahnya terbalik di ponselmu, ubah `LOOK_UP_SIGN` ke `-1f` di `EyeTrackingAnalyzer.kt`.
- **Kamera/mikrofon di latar belakang** hanya jalan bila foreground service dimulai saat aplikasi terlihat (aturan Android 11+). Jika proses dimatikan sistem, buka aplikasi sekali lagi.
- **Perintah suara** memakai `SpeechRecognizer` bawaan: bisa ada bunyi bip, audio video meredup sebentar, dan di perangkat tanpa paket offline memakai layanan online. Huruf tunggal seperti "B" sulit dikenali; kata utuh lebih andal.
- **Timer** hanya estimasi; aplikasi tidak membaca durasi video sebenarnya.
- Scroll hanya jalan di paket yang tercantum di `SupportedApps` (bisa dimatikan di Pengaturan atau ditambah di kode).
- Kebijakan Google Play membatasi pemakaian API aksesibilitas untuk non-disabilitas; proyek ini cocok untuk pemakaian pribadi / sideload.
