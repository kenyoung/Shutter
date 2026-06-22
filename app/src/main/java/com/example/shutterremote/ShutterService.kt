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
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.text.SimpleDateFormat
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
    private var currentIntervalMs = 0L
    private var connectionLostDuringSession = false

    // elapsedRealtime() at which the next auto-trigger shot is scheduled to fire.
    @Volatile
    private var nextFireAtElapsed = 0L

    // Pushes the live status line to a workstation over UDP (null = disabled).
    private var reporter: StatusReporter? = null
    private var reporterJob: Job? = null
    private val statusClock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** How a successful trigger is signalled audibly. */
    enum class FeedbackMode { BEEP, COUNT }

    @Volatile
    var feedbackMode: FeedbackMode = FeedbackMode.COUNT

    /** When true, all audio (counts, beeps, announcements, alerts) is suppressed. */
    @Volatile
    var muted: Boolean = false

    // Speaks the running exposure count after each successful trigger (COUNT mode).
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Short confirmation beep (BEEP mode); created on first use.
    private var toneGenerator: ToneGenerator? = null

    var lastStatus: String = "Starting…"
        private set
    val isIntervalRunning: Boolean get() = intervalJob?.isActive == true

    /**
     * Immutable snapshot of the live countdown to the next auto-trigger shot.
     * All fields are derived from a single clock read so they agree with each
     * other. [shotsRemaining] is null for an unlimited session.
     */
    data class CountdownState(
        val secondsUntilNext: Int,
        val nextShotEpochMillis: Long,
        val shotsRemaining: Int?,
    )

    /**
     * The countdown to display right now, or null when none should be shown —
     * no auto-trigger session, or an interval too short ([COUNTDOWN_MIN_INTERVAL_MS])
     * to be worth watching. Null *is* "inactive", so callers can't read stale
     * timing by forgetting to check an active flag.
     */
    fun countdownState(): CountdownState? {
        if (!isIntervalRunning || currentIntervalMs < COUNTDOWN_MIN_INTERVAL_MS) return null
        val remainingMs = (nextFireAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        return CountdownState(
            secondsUntilNext = ((remainingMs + 999) / 1000).toInt(),
            nextShotEpochMillis = System.currentTimeMillis() + remainingMs,
            shotsRemaining = if (maxShots > 0) (maxShots - shotCount).coerceAtLeast(0) else null,
        )
    }

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
                val reportEnabled = intent.getBooleanExtra(EXTRA_REPORT_ENABLED, false)
                val reportHost = intent.getStringExtra(EXTRA_REPORT_HOST)
                val reportPort = intent.getIntExtra(EXTRA_REPORT_PORT, DEFAULT_REPORT_PORT)
                goForeground()
                startInterval(intervalMs, maxShots, reportEnabled, reportHost, reportPort)
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
        }
        return ok
    }

    /** Audible confirmation of a successful trigger, per the selected mode. */
    private fun signal(count: Int) {
        when (feedbackMode) {
            FeedbackMode.BEEP -> beep()
            // Skip the spoken count for sub-second auto-trigger intervals — the
            // number takes longer to say than the gap between shots. Single shots
            // (no interval running) are always announced.
            FeedbackMode.COUNT ->
                if (!isIntervalRunning || currentIntervalMs >= 1_000L) announce(count)
        }
    }

    /** Speak the exposure count aloud (e.g. "1", "2", …) as confirmation. */
    private fun announce(count: Int) =
        speak(count.toString(), TextToSpeech.QUEUE_FLUSH, "shot-$count")

    private fun beep() {
        if (muted) return
        try {
            val tg = toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { toneGenerator = it }
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        } catch (_: RuntimeException) {
            // Audio hardware unavailable — triggering still succeeded.
        }
    }

    private fun startInterval(
        intervalMs: Long,
        maxShots: Int,
        reportEnabled: Boolean = false,
        reportHost: String? = null,
        reportPort: Int = DEFAULT_REPORT_PORT,
    ) {
        intervalJob?.cancel()
        acquireWakeLock()
        shotCount = 0
        this.maxShots = maxShots
        currentIntervalMs = intervalMs
        connectionLostDuringSession = false
        nextFireAtElapsed = SystemClock.elapsedRealtime() + intervalMs
        startReporter(reportEnabled, reportHost, reportPort)
        uiListener?.onIntervalStateChanged(true)
        intervalJob = scope.launch {
            while (isActive) {
                fireOnce()
                updateNotification()
                if (maxShots in 1..shotCount) {
                    finishInterval() // reached the requested count
                    break
                }
                nextFireAtElapsed = SystemClock.elapsedRealtime() + intervalMs
                delay(intervalMs)
            }
            // A manual stop cancels the coroutine, so the loop just exits here
            // without finishing — only the limit path above runs finishInterval().
        }
    }

    /** Auto-trigger reached its exposure limit — tear the session down cleanly. */
    private fun finishInterval() {
        intervalJob = null
        stopReporter()
        releaseWakeLock()
        announceCompletion(maxShots)
        uiListener?.onIntervalStateChanged(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // --- Status feed to the workstation (UDP) ---

    /** Open the UDP feed and start pushing the status line once per second. */
    private fun startReporter(enabled: Boolean, host: String?, port: Int) {
        stopReporter()
        if (!enabled) return
        reporter = StatusReporter(host, port)
        reporterJob = scope.launch {
            while (isActive) {
                reporter?.send("$KIND_STATUS\t${statusLine()}")
                delay(REPORT_INTERVAL_MS)
            }
        }
    }

    private fun stopReporter() {
        reporterJob?.cancel()
        reporterJob = null
        reporter?.close()
        reporter = null
    }

    /** The same line the UI shows, or a running/idle fallback when no countdown. */
    private fun statusLine(): String {
        countdownState()?.let { return it.toLine(this, statusClock) }
        val progress = if (maxShots > 0) "$shotCount / $maxShots" else "$shotCount"
        return "Auto-trigger running · $progress shot(s)"
    }

    /** Spoken confirmation that the full requested set of exposures was taken. */
    private fun announceCompletion(total: Int) = speak("Set of $total exposures complete.")

    /**
     * Single TTS entry point — honours mute/readiness so no caller can bypass
     * it. Defaults to QUEUE_ADD so alerts/announcements follow any in-progress
     * count rather than cutting it off; the running count passes QUEUE_FLUSH to
     * replace a stale number.
     */
    private fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        utteranceId: String = "msg"
    ) {
        if (muted || !ttsReady) return
        tts?.speak(text, queueMode, null, utteranceId)
    }

    /** Speak a session alert and mirror it to the workstation feed. */
    private fun alert(text: String) {
        speak(text)
        reporter?.send("$KIND_ALERT\t$text")
    }

    fun stopInterval() {
        intervalJob?.cancel()
        intervalJob = null
        stopReporter()
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
        val connected = device != null
        // Audibly warn if the camera link drops mid-session, and confirm recovery,
        // so an unattended night doesn't silently stop capturing.
        if (isIntervalRunning) {
            if (!connected) {
                connectionLostDuringSession = true
                alert("Camera disconnected. Reconnecting.")
            } else if (connectionLostDuringSession) {
                connectionLostDuringSession = false
                alert("Camera reconnected.")
            }
        }
        val label = device?.let { controller.deviceLabel(it) }
        uiListener?.onHostConnectionChanged(connected, label)
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
        const val EXTRA_REPORT_ENABLED = "report_enabled"
        const val EXTRA_REPORT_HOST = "report_host"
        const val EXTRA_REPORT_PORT = "report_port"

        // Datagram type tags (payload = "<KIND>\t<message>").
        private const val KIND_STATUS = "STATUS"
        private const val KIND_ALERT = "ALERT"
        private const val DEFAULT_REPORT_PORT = 5005
        private const val REPORT_INTERVAL_MS = 1_000L

        private const val DEFAULT_INTERVAL_MS = 5 * 60_000L
        private const val MAX_WAKELOCK_MS = 12 * 60 * 60_000L // 12h safety cap
        private const val CHANNEL_ID = "shutter_remote"
        private const val NOTIFICATION_ID = 1
        private const val BEEP_VOLUME = 100 // ToneGenerator volume, 0–100
        private const val BEEP_MS = 150
        private const val COUNTDOWN_MIN_INTERVAL_MS = 10_000L
    }
}
