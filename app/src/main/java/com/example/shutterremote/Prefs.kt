package com.example.shutterremote

import android.content.Context

/**
 * Lightweight persistence for the user's choices so a long imaging session can
 * be set up the same way each night without re-entering everything.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var intervalValue: String
        get() = sp.getString(KEY_INTERVAL, "5") ?: "5"
        set(v) = sp.edit().putString(KEY_INTERVAL, v).apply()

    var unitSeconds: Boolean
        get() = sp.getBoolean(KEY_UNIT_SECONDS, false)
        set(v) = sp.edit().putBoolean(KEY_UNIT_SECONDS, v).apply()

    var maxShots: String
        get() = sp.getString(KEY_MAX_SHOTS, "") ?: ""
        set(v) = sp.edit().putString(KEY_MAX_SHOTS, v).apply()

    /** true = Beep Mode, false = Count Mode (the default). */
    var feedbackBeep: Boolean
        get() = sp.getBoolean(KEY_FEEDBACK_BEEP, false)
        set(v) = sp.edit().putBoolean(KEY_FEEDBACK_BEEP, v).apply()

    /** true = Enter key, false = Volume Up (the default). */
    var triggerEnter: Boolean
        get() = sp.getBoolean(KEY_TRIGGER_ENTER, false)
        set(v) = sp.edit().putBoolean(KEY_TRIGGER_ENTER, v).apply()

    /** true = all audio suppressed; defaults to false (un-muted). */
    var muted: Boolean
        get() = sp.getBoolean(KEY_MUTED, false)
        set(v) = sp.edit().putBoolean(KEY_MUTED, v).apply()

    var lastDeviceAddress: String?
        get() = sp.getString(KEY_LAST_DEVICE, null)
        set(v) = sp.edit().putString(KEY_LAST_DEVICE, v).apply()

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
