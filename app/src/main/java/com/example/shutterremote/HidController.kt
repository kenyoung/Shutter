package com.example.shutterremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Owns the Bluetooth HID Device profile: registers this phone as a virtual
 * shutter remote, tracks the connected camera phone, and sends shutter reports.
 * Requires no root — uses the public [BluetoothHidDevice] API (API 28+).
 *
 * Callers must ensure BLUETOOTH_CONNECT is granted (API 31+) before use.
 */
class HidController(private val context: Context) {

    enum class TriggerKey { VOLUME_UP, ENTER }

    /** Callbacks are delivered on the main thread. */
    interface Listener {
        fun onStatusChanged(status: String)
        fun onHostConnectionChanged(device: BluetoothDevice?)
    }

    @Volatile
    var triggerKey: TriggerKey = TriggerKey.VOLUME_UP

    private var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var appRegistered = false

    @Volatile
    private var connectedHost: BluetoothDevice? = null

    val isConnected: Boolean get() = connectedHost != null
    val host: BluetoothDevice? get() = connectedHost

    fun setListener(l: Listener?) {
        listener = l
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Acquire the HID_DEVICE proxy; registration follows once it connects. */
    fun start() {
        val a = adapter
        if (a == null) {
            notifyStatus("Bluetooth not available on this device")
            return
        }
        if (!a.isEnabled) {
            notifyStatus("Bluetooth is off — enable it, then reopen")
            return
        }
        if (!hasBtPermission()) {
            notifyStatus("Bluetooth permission not granted")
            return
        }
        notifyStatus("Starting HID service…")
        a.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    /** Unregister and release the proxy. */
    fun stop() {
        val dev = hidDevice
        if (dev != null && hasBtPermission()) {
            try {
                connectedHost?.let { dev.disconnect(it) }
                dev.unregisterApp()
            } catch (_: SecurityException) {
            }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, dev)
        }
        hidDevice = null
        appRegistered = false
        connectedHost = null
    }

    /** Devices already paired with this phone — candidates to connect to. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasBtPermission()) return emptyList()
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val dev = hidDevice
        if (dev == null || !appRegistered) {
            notifyStatus("HID not ready yet")
            return
        }
        if (!hasBtPermission()) return
        notifyStatus("Connecting to ${deviceLabel(device)}…")
        dev.connect(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val dev = hidDevice ?: return
        val target = connectedHost ?: return
        if (!hasBtPermission()) return
        dev.disconnect(target)
    }

    /**
     * Send one shutter trigger: key-down then a short-delayed key-up.
     * Returns false if there is no connected host to send to.
     */
    @SuppressLint("MissingPermission")
    fun fireShutter(): Boolean {
        val dev = hidDevice ?: return false
        val target = connectedHost ?: return false
        if (!hasBtPermission()) return false

        val (reportId, down, up) = when (triggerKey) {
            TriggerKey.VOLUME_UP -> Triple(
                HidConstants.REPORT_ID_CONSUMER,
                HidConstants.CONSUMER_VOLUME_UP,
                HidConstants.CONSUMER_RELEASE
            )
            TriggerKey.ENTER -> Triple(
                HidConstants.REPORT_ID_KEYBOARD,
                HidConstants.KEYBOARD_ENTER,
                HidConstants.KEYBOARD_RELEASE
            )
        }
        dev.sendReport(target, reportId, down)
        mainHandler.postDelayed({
            try {
                dev.sendReport(target, reportId, up)
            } catch (_: SecurityException) {
            }
        }, KEY_PRESS_MS)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val dev = hidDevice ?: return
        if (!hasBtPermission()) return
        dev.registerApp(HidConstants.sdpSettings, null, null, executor, hidCallback)
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                appRegistered = false
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            notifyStatus(if (registered) "Ready — pair from the camera phone" else "HID unregistered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            connectedHost = if (state == BluetoothProfile.STATE_CONNECTED) device else null
            mainHandler.post {
                listener?.onHostConnectionChanged(connectedHost)
                notifyStatus(
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> "Connected to ${deviceLabel(device)}"
                        BluetoothProfile.STATE_CONNECTING -> "Connecting…"
                        BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting…"
                        else -> "Ready — not connected"
                    }
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun deviceLabel(device: BluetoothDevice): String {
        val name = if (hasBtPermission()) device.name else null
        return name ?: device.address
    }

    private fun notifyStatus(status: String) {
        mainHandler.post { listener?.onStatusChanged(status) }
    }

    companion object {
        private const val KEY_PRESS_MS = 60L
    }
}
