package com.example.shutterremote

import android.content.Context

/**
 * Lightweight persistence for the user's choices so a long imaging session can
 * be set up the same way each night without re-entering everything.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val intervalValue: String
        get() = sp.getString(KEY_INTERVAL, "5") ?: "5"

    val unitSeconds: Boolean
        get() = sp.getBoolean(KEY_UNIT_SECONDS, false)

    val maxShots: String
        get() = sp.getString(KEY_MAX_SHOTS, "") ?: ""

    /** true = Beep Mode, false = Count Mode (the default). */
    val feedbackBeep: Boolean
        get() = sp.getBoolean(KEY_FEEDBACK_BEEP, false)

    /** true = Enter key, false = Volume Up (the default). */
    val triggerEnter: Boolean
        get() = sp.getBoolean(KEY_TRIGGER_ENTER, false)

    /** true = all audio suppressed; defaults to false (un-muted). */
    val muted: Boolean
        get() = sp.getBoolean(KEY_MUTED, false)

    var lastDeviceAddress: String?
        get() = sp.getString(KEY_LAST_DEVICE, null)
        set(v) = sp.edit().putString(KEY_LAST_DEVICE, v).apply()

    /** Persist all interactive settings in a single commit (called on stop). */
    fun saveSession(
        intervalValue: String,
        unitSeconds: Boolean,
        maxShots: String,
        feedbackBeep: Boolean,
        triggerEnter: Boolean,
        muted: Boolean,
    ) {
        sp.edit()
            .putString(KEY_INTERVAL, intervalValue)
            .putBoolean(KEY_UNIT_SECONDS, unitSeconds)
            .putString(KEY_MAX_SHOTS, maxShots)
            .putBoolean(KEY_FEEDBACK_BEEP, feedbackBeep)
            .putBoolean(KEY_TRIGGER_ENTER, triggerEnter)
            .putBoolean(KEY_MUTED, muted)
            .apply()
    }

    companion object {
        private const val NAME = "shutter_prefs"
        private const val KEY_INTERVAL = "interval_value"
        private const val KEY_UNIT_SECONDS = "unit_seconds"
        private const val KEY_MAX_SHOTS = "max_shots"
        private const val KEY_FEEDBACK_BEEP = "feedback_beep"
        private const val KEY_TRIGGER_ENTER = "trigger_enter"
        private const val KEY_MUTED = "muted"
        private const val KEY_LAST_DEVICE = "last_device"
    }
}
