package com.joyz.autoscrolljoy

/**
 * Jembatan sederhana antara modul sensor (kamera/suara) dan layanan aksesibilitas.
 * Keduanya berjalan di proses yang sama, jadi cukup satu referensi statis.
 * Referensi dilepas saat layanan aksesibilitas berhenti, jadi tidak ada kebocoran.
 */
object ScrollBridge {

    @Volatile
    private var service: AutoScrollAccessibilityService? = null

    /** True bila layanan aksesibilitas sedang aktif dan siap menerima perintah scroll. */
    val isConnected: Boolean
        get() = service != null

    fun attach(s: AutoScrollAccessibilityService) {
        service = s
    }

    fun detach(s: AutoScrollAccessibilityService) {
        if (service === s) service = null
    }

    /**
     * Meminta satu kali scroll. Aman dipanggil dari thread mana pun.
     * @param force true = abaikan sakelar utama & filter aplikasi (dipakai tombol uji).
     */
    fun requestScroll(source: String, force: Boolean = false) {
        service?.requestScroll(source, force)
    }
}
