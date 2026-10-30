package com.joyz.autoscrolljoy

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.joyz.autoscrolljoy.databinding.ActivityMainBinding

/**
 * Layar utama: sakelar ON/OFF, status izin (Aksesibilitas, Kamera, Mikrofon), dan pilihan mode.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ScrollPrefs

    /** True saat kode mengubah posisi sakelar, agar listener tidak ikut terpicu. */
    private var updatingUi = false

    /** Aksi yang dijalankan setelah dialog izin ditutup. */
    private var afterPermissionResult: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            afterPermissionResult?.invoke()
            afterPermissionResult = null

            // Ditolak dan sistem tidak mau menampilkan dialog lagi -> arahkan ke setelan aplikasi.
            val blocked = result.any { (permission, granted) ->
                !granted &&
                    permission != Manifest.permission.POST_NOTIFICATIONS &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
            }
            if (blocked) {
                Snackbar.make(binding.root, R.string.perm_denied_permanent, Snackbar.LENGTH_LONG)
                    .setAction(R.string.btn_open_settings) { openAppSettings() }
                    .show()
            }
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        prefs = ScrollPrefs.get(this)
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        applyChanges()
    }

    // ---------------------------------------------------------------------------------------------
    // Listener
    // ---------------------------------------------------------------------------------------------

    private fun setupListeners() {
        binding.swMaster.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) onMasterToggled(checked)
        }

        binding.swModeEye.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) onEyeToggled(checked)
        }

        binding.swModeTimer.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.modeTimer = checked
            applyChanges()
        }

        binding.swModeVoice.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) onVoiceToggled(checked)
        }

        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }

        binding.btnCamera.setOnClickListener { onPermissionButton(Manifest.permission.CAMERA) }
        binding.btnMic.setOnClickListener { onPermissionButton(Manifest.permission.RECORD_AUDIO) }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun onMasterToggled(checked: Boolean) {
        if (!checked) {
            prefs.enabled = false
            applyChanges()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            setSwitch(binding.swMaster, false)
            showAccessibilityDialog()
            return
        }

        prefs.enabled = true

        // Minta izin yang dibutuhkan mode yang sedang aktif (+ notifikasi di Android 13+).
        val needed = mutableListOf<String>()
        if (prefs.modeEye) needed += Manifest.permission.CAMERA
        if (prefs.modeVoice) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        requestThen(needed) { applyChanges() }
    }

    private fun onEyeToggled(checked: Boolean) {
        if (!checked) {
            prefs.modeEye = false
            applyChanges()
            return
        }
        requestThen(listOf(Manifest.permission.CAMERA)) {
            prefs.modeEye = hasPermission(Manifest.permission.CAMERA)
            applyChanges()
        }
    }

    private fun onVoiceToggled(checked: Boolean) {
        if (!checked) {
            prefs.modeVoice = false
            applyChanges()
            return
        }
        requestThen(listOf(Manifest.permission.RECORD_AUDIO)) {
            prefs.modeVoice = hasPermission(Manifest.permission.RECORD_AUDIO)
            applyChanges()
        }
    }

    private fun onPermissionButton(permission: String) {
        if (hasPermission(permission)) {
            openAppSettings()
        } else {
            requestThen(listOf(permission)) { }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Izin
    // ---------------------------------------------------------------------------------------------

    /** Minta izin yang belum diberikan lalu jalankan [after]; langsung jalan bila semua sudah ada. */
    private fun requestThen(permissions: List<String>, after: () -> Unit) {
        val missing = permissions.filter { !hasPermission(it) }
        if (missing.isEmpty()) {
            after()
        } else {
            afterPermissionResult = after
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, AutoScrollAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
        }
        return false
    }

    private fun showAccessibilityDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_acc_title)
            .setMessage(R.string.dialog_acc_message)
            .setPositiveButton(R.string.btn_open_settings) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Tampilan
    // ---------------------------------------------------------------------------------------------

    /** Selaraskan service sensor dengan pengaturan, lalu segarkan tampilan. */
    private fun applyChanges() {
        refreshUi()
        SensorForegroundService.sync(this)
    }

    private fun refreshUi() {
        val accessibilityOn = isAccessibilityServiceEnabled()
        val cameraOn = hasPermission(Manifest.permission.CAMERA)
        val micOn = hasPermission(Manifest.permission.RECORD_AUDIO)

        // Rapikan pengaturan yang tidak lagi valid (izin dicabut, layanan dimatikan, dsb.).
        if (prefs.enabled && !accessibilityOn) prefs.enabled = false
        if (prefs.modeEye && !cameraOn) prefs.modeEye = false
        if (prefs.modeVoice && !micOn) prefs.modeVoice = false

        updatingUi = true
        binding.swMaster.isChecked = prefs.enabled
        binding.swModeEye.isChecked = prefs.modeEye
        binding.swModeTimer.isChecked = prefs.modeTimer
        binding.swModeVoice.isChecked = prefs.modeVoice
        updatingUi = false

        bindRow(
            binding.dotAccessibility, binding.tvAccessibilityState, binding.btnAccessibility,
            accessibilityOn,
            R.string.perm_state_on, R.string.perm_state_off,
            R.string.btn_manage, R.string.btn_enable
        )
        bindRow(
            binding.dotCamera, binding.tvCameraState, binding.btnCamera,
            cameraOn,
            R.string.perm_state_granted, R.string.perm_state_not_granted,
            R.string.btn_manage, R.string.btn_grant
        )
        bindRow(
            binding.dotMic, binding.tvMicState, binding.btnMic,
            micOn,
            R.string.perm_state_granted, R.string.perm_state_not_granted,
            R.string.btn_manage, R.string.btn_grant
        )

        binding.tvRestrictedHint.isVisible =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !accessibilityOn

        val modes = buildList {
            if (prefs.modeEye) add(getString(R.string.mode_short_eye))
            if (prefs.modeTimer) add(getString(R.string.mode_short_timer))
            if (prefs.modeVoice) add(getString(R.string.mode_short_voice))
        }
        binding.tvMasterStatus.text = when {
            !accessibilityOn -> getString(R.string.status_need_accessibility)
            !prefs.enabled -> getString(R.string.status_off)
            modes.isEmpty() -> getString(R.string.status_no_mode)
            else -> getString(R.string.status_on, modes.joinToString(", "))
        }
    }

    private fun bindRow(
        dot: View,
        state: TextView,
        button: MaterialButton,
        on: Boolean,
        @StringRes stateOn: Int,
        @StringRes stateOff: Int,
        @StringRes buttonOn: Int,
        @StringRes buttonOff: Int
    ) {
        val color = ContextCompat.getColor(this, if (on) R.color.status_on else R.color.status_off)
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(color))
        state.setText(if (on) stateOn else stateOff)
        button.setText(if (on) buttonOn else buttonOff)
    }

    private fun setSwitch(view: android.widget.CompoundButton, checked: Boolean) {
        updatingUi = true
        view.isChecked = checked
        updatingUi = false
    }
}
