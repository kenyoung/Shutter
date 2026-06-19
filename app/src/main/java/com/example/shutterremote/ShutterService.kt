package com.example.shutterremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [HidController], runs the auto-trigger timer,
 * and holds a partial wakelock so periodic shots survive screen-off / doze over
 * a long imaging session.
 *
 * The activity binds for live control (connect, single shot, trigger key, status)
 * and uses start/stop commands to drive the persistent interval timer.
 */
class ShutterService : Service(), HidController.Listener {

    interface ServiceListener {
        fun onStatus(text: String)
        fun onHostConnectionChanged(connected: Boolean, label: String?)
        fun onIntervalStateChanged(running: Boolean)
        fun onShotFired(count: Int)
    }

    inner class LocalBinder : Binder() {
        val service: ShutterService get() = this@ShutterService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var controller: HidController
        private set

    private var uiListener: ServiceListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var intervalJob: Job? = null
    private var shotCount = 0
    private var maxShots = 0 // 0 = unlimited

    /** How a successful trigger is signalled audibly. */
    enum class FeedbackMode { BEEP, COUNT }

    @Volatile
    var feedbackMode: FeedbackMode = FeedbackMode.COUNT

    // Speaks the running exposure count after each successful trigger (COUNT mode).
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Short confirmation beep (BEEP mode); created on first use.
    private var toneGenerator: ToneGenerator? = null

    var lastStatus: String = "Starting…"
        private set
    val isIntervalRunning: Boolean get() = intervalJob?.isActive == true

    override fun onCreate() {
        super.onCreate()
        controller = HidController(this).also {
            it.setListener(this)
            it.start()
        }
        createNotificationChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_INTERVAL -> {
                val intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS)
                val maxShots = intent.getIntExtra(EXTRA_MAX_SHOTS, 0)
                goForeground()
                startInterval(intervalMs, maxShots)
            }
            ACTION_STOP_INTERVAL -> {
                stopInterval()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopInterval()
        controller.stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
        toneGenerator?.release()
        toneGenerator = null
        scope.cancel()
        super.onDestroy()
    }

    // --- Control surface used by the bound activity ---

    fun setUiListener(l: ServiceListener?) {
        uiListener = l
    }

    fun bondedDevices(): List<BluetoothDevice> = controller.bondedDevices()

    fun connect(device: BluetoothDevice) = controller.connect(device)

    fun disconnect() = controller.disconnect()

    fun setTriggerKey(key: HidController.TriggerKey) {
        controller.triggerKey = key
    }

    /** Fire a single shot now. Returns false if no camera phone is connected. */
    fun fireOnce(): Boolean {
        val ok = controller.fireShutter()
        if (ok) {
            shotCount++
            signal(shotCount)
            uiListener?.onShotFired(shotCount)
        }
        return ok
    }

    /** Audible confirmation of a successful trigger, per the selected mode. */
    private fun signal(count: Int) {
        when (feedbackMode) {
            FeedbackMode.BEEP -> beep()
            FeedbackMode.COUNT -> announce(count)
        }
    }

    /** Speak the exposure count aloud (e.g. "1", "2", …) as confirmation. */
    private fun announce(count: Int) {
        if (!ttsReady) return
        tts?.speak(count.toString(), TextToSpeech.QUEUE_FLUSH, null, "shot-$count")
    }

    private fun beep() {
        try {
            val tg = toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { toneGenerator = it }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        } catch (_: RuntimeException) {
            // Audio hardware unavailable — triggering still succeeded.
        }
    }

    private fun startInterval(intervalMs: Long, maxShots: Int) {
        intervalJob?.cancel()
        acquireWakeLock()
        shotCount = 0
        this.maxShots = maxShots
        uiListener?.onIntervalStateChanged(true)
        intervalJob = scope.launch {
            while (isActive) {
                fireOnce()
                updateNotification()
                if (maxShots in 1..shotCount) break // reached the requested count
                delay(intervalMs)
            }
            // Reaching here with the coroutine still active means we hit the
            // limit (a manual stop cancels the coroutine instead).
            if (isActive) finishInterval()
        }
    }

    /** Auto-trigger reached its exposure limit — tear the session down cleanly. */
    private fun finishInterval() {
        intervalJob = null
        releaseWakeLock()
        announceCompletion(maxShots)
        uiListener?.onIntervalStateChanged(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** Spoken confirmation that the full requested set of exposures was taken. */
    private fun announceCompletion(total: Int) {
        if (!ttsReady) return
        // QUEUE_ADD so it follows the last count rather than cutting it off.
        tts?.speak("Set of $total exposures complete.", TextToSpeech.QUEUE_ADD, null, "set-complete")
    }

    fun stopInterval() {
        intervalJob?.cancel()
        intervalJob = null
        releaseWakeLock()
        uiListener?.onIntervalStateChanged(false)
    }

    // --- HidController.Listener ---

    override fun onStatusChanged(status: String) {
        lastStatus = status
        uiListener?.onStatus(status)
        if (isIntervalRunning) updateNotification()
    }

    override fun onHostConnectionChanged(device: BluetoothDevice?) {
        val label = device?.let { controller.deviceLabel(it) }
        uiListener?.onHostConnectionChanged(device != null, label)
    }

    // --- Foreground / notification plumbing ---

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        if (!isIntervalRunning) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val progress = if (maxShots > 0) "$shotCount / $maxShots shot(s)" else "$shotCount shot(s)"
        val text = if (controller.isConnected) {
            "Auto-trigger running · $progress"
        } else {
            "Auto-trigger running · waiting for camera phone"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shutter Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shutter Remote",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Auto-trigger session is active" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShutterRemote::interval").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START_INTERVAL = "com.example.shutterremote.START_INTERVAL"
        const val ACTION_STOP_INTERVAL = "com.example.shutterremote.STOP_INTERVAL"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_MAX_SHOTS = "max_shots"

        private const val DEFAULT_INTERVAL_MS = 5 * 60_000L
        private const val MAX_WAKELOCK_MS = 12 * 60 * 60_000L // 12h safety cap
        private const val CHANNEL_ID = "shutter_remote"
        private const val NOTIFICATION_ID = 1
        private const val BEEP_VOLUME = 100 // ToneGenerator volume, 0–100
        private const val BEEP_MS = 150
    }
}
