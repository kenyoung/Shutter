package com.example.shutterremote

import android.content.Context
import com.example.shutterremote.ShutterService.CountdownState
import java.text.DateFormat

/**
 * Render a [CountdownState] as the single status line — the same text shown on
 * the phone and pushed to the workstation, so the two never diverge. [clock]
 * formats the next-shot wall-clock time; the caller supplies it (each holds one
 * reusable formatter, since [DateFormat] is not thread-safe).
 */
fun CountdownState.toLine(context: Context, clock: DateFormat): String {
    val at = clock.format(nextShotEpochMillis)
    return if (shotsRemaining != null) {
        context.getString(R.string.countdown_remaining, shotsRemaining, at, secondsUntilNext)
    } else {
        context.getString(R.string.countdown_unlimited, at, secondsUntilNext)
    }
}
