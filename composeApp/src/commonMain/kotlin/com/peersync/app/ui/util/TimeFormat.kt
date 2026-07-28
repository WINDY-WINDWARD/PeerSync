package com.peersync.app.ui.util

import java.util.*

/**
 * Format milliseconds as a human-readable track time.
 * Examples: 90000ms → "1:30", 3661000ms → "1:01:01"
 */
fun formatTrackTime(ms: Long): String {
     if (ms < 0) return "0:00"
     
     val totalSeconds = ms / 1000
     val hours = totalSeconds / 3600
     val minutes = (totalSeconds % 3600) / 60
     val seconds = totalSeconds % 60
     
     return if (hours > 0) {
         String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
     } else {
         String.format(Locale.US, "%d:%02d", minutes, seconds)
     }
}
