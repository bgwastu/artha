package net.wastu.artha.data.sync

import android.content.Context
import android.os.PowerManager

/**
 * A hidden-display run is abandoned the moment the screen sleeps, and the usual 15 s screen timeout is
 * shorter than a Bank Jago unlock or a GoPay walk. This holds the screen on for one run; without
 * ACQUIRE_CAUSES_WAKEUP it never wakes a screen that is already off, and it frees itself after [maxMs].
 */
@Suppress("DEPRECATION")
suspend fun <T> keepingScreenOn(context: Context, tag: String, maxMs: Long = 120_000L, block: suspend () -> T): T {
    val lock = runCatching {
        context.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "artha:$tag")
    }.getOrNull()
    runCatching { lock?.acquire(maxMs) }
    try { return block() } finally { runCatching { if (lock?.isHeld == true) lock.release() } }
}
