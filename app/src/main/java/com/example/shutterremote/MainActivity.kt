package com.example.shutterremote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.shutterremote.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), ShutterService.ServiceListener {

    private lateinit var binding: ActivityMainBinding

    private var service: ShutterService? = null
    private var bound = false
    private var devices: List<BluetoothDevice> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Refresh whatever we can now that the user has answered.
        refreshDevices()
        service?.let { binding.statusText.text = it.lastStatus }
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // ACTION_REQUEST_DISCOVERABLE returns the duration (seconds) if accepted,
        // or RESULT_CANCELED if the user declined.
        if (result.resultCode != RESULT_CANCELED) toast(R.string.toast_discoverable)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, ibinder: IBinder?) {
            val s = (ibinder as ShutterService.LocalBinder).service
            service = s
            bound = true
            s.setUiListener(this@MainActivity)
            s.feedbackMode = selectedFeedbackMode()
            binding.statusText.text = s.lastStatus
            onIntervalStateChanged(s.isIntervalRunning)
            onHostConnectionChanged(s.controller.isConnected, s.controller.host?.let { s.controller.deviceLabel(it) })
            refreshDevices()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNeededPermissions()
        wireUi()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ShutterService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.setUiListener(null)
            unbindService(connection)
            bound = false
        }
    }

    private fun wireUi() {
        binding.discoverableButton.setOnClickListener { requestDiscoverable() }

        binding.refreshButton.setOnClickListener { refreshDevices() }

        binding.connectButton.setOnClickListener {
            val pos = binding.deviceSpinner.selectedItemPosition
            val device = devices.getOrNull(pos)
            if (device == null) {
                toast(R.string.toast_select_device)
            } else {
                service?.connect(device)
            }
        }

        binding.takePhotoButton.setOnClickListener {
            val ok = service?.fireOnce() ?: false
            toast(if (ok) R.string.toast_photo_sent else R.string.toast_not_connected)
        }

        binding.triggerKeyGroup.setOnCheckedChangeListener { _, checkedId ->
            val key = if (checkedId == R.id.keyEnter) {
                HidController.TriggerKey.ENTER
            } else {
                HidController.TriggerKey.VOLUME_UP
            }
            service?.setTriggerKey(key)
        }

        binding.feedbackGroup.setOnCheckedChangeListener { _, _ ->
            service?.feedbackMode = selectedFeedbackMode()
        }

        binding.intervalButton.setOnClickListener { toggleInterval() }
    }

    private fun selectedFeedbackMode(): ShutterService.FeedbackMode =
        if (binding.modeBeep.isChecked) {
            ShutterService.FeedbackMode.BEEP
        } else {
            ShutterService.FeedbackMode.COUNT
        }

    private fun toggleInterval() {
        val running = service?.isIntervalRunning ?: false
        if (running) {
            startService(
                Intent(this, ShutterService::class.java)
                    .setAction(ShutterService.ACTION_STOP_INTERVAL)
            )
            return
        }

        val value = binding.intervalInput.text.toString().toLongOrNull()
        if (value == null || value < 1) {
            toast(R.string.toast_bad_interval)
            return
        }
        if (service?.controller?.isConnected != true) {
            toast(R.string.toast_not_connected)
            return
        }
        val unitMs = if (binding.unitSeconds.isChecked) 1_000L else 60_000L
        val intervalMs = value * unitMs

        // Blank or 0 means unlimited; otherwise stop after this many exposures.
        val maxShots = binding.maxShotsInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0

        val intent = Intent(this, ShutterService::class.java)
            .setAction(ShutterService.ACTION_START_INTERVAL)
            .putExtra(ShutterService.EXTRA_INTERVAL_MS, intervalMs)
            .putExtra(ShutterService.EXTRA_MAX_SHOTS, maxShots)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestDiscoverable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(Manifest.permission.BLUETOOTH_ADVERTISE)
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE))
            toast(R.string.toast_advertise_needed)
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
        discoverableLauncher.launch(intent)
    }

    private fun refreshDevices() {
        val list = service?.bondedDevices() ?: emptyList()
        devices = list
        val labels = list.map { service?.controller?.deviceLabel(it) ?: it.address }
        val display = labels.ifEmpty { listOf(getString(R.string.toast_select_device)) }
        binding.deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            display
        )
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(Manifest.permission.BLUETOOTH_ADVERTISE)
        ) {
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) =
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // --- ShutterService.ServiceListener (delivered on the main thread) ---

    override fun onStatus(text: String) {
        binding.statusText.text = text
    }

    override fun onHostConnectionChanged(connected: Boolean, label: String?) {
        binding.connectButton.text = if (connected) {
            getString(R.string.btn_connect) + " ✓"
        } else {
            getString(R.string.btn_connect)
        }
    }

    override fun onIntervalStateChanged(running: Boolean) {
        binding.intervalButton.text =
            getString(if (running) R.string.btn_stop_auto else R.string.btn_start_auto)
        keepScreenOn(running)
    }

    override fun onShotFired(count: Int) {
        binding.intervalInfo.text = getString(R.string.hint_interval) + "\n\nShots fired: $count"
    }

    companion object {
        private const val DISCOVERABLE_SECONDS = 300
    }
}
