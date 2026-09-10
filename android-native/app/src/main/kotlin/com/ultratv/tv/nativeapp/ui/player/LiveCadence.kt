package com.ultratv.tv.nativeapp.ui.player

import android.app.Activity
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.round

/**
 * Estimates broadcast cadence from decoded video presentation timestamps when
 * the container/codec does not publish a frame rate. IPTV MPEG-TS feeds often
 * omit Format.frameRate even though their PTS cadence is stable. Knowing the
 * cadence lets Fire TV stay on an exact 50/59.94-family output mode instead of
 * relying on TV-side motion conversion.
 */
internal class LiveCadenceEstimator(
    private val maxSamples: Int = 45,
) {
    private val samplesUs = ArrayDeque<Long>()
    private var lastPtsUs: Long? = null

    fun reset() {
        samplesUs.clear()
        lastPtsUs = null
    }

    fun sample(presentationTimeUs: Long): Float? {
        val previous = lastPtsUs
        lastPtsUs = presentationTimeUs
        if (previous == null) return null

        val delta = presentationTimeUs - previous
        // Ignore discontinuities, seeks and duplicate timestamps. The range
        // covers roughly 10-100 fps while rejecting IPTV timestamp jumps.
        if (delta !in 10_000L..100_000L) return null

        samplesUs.addLast(delta)
        while (samplesUs.size > maxSamples) samplesUs.removeFirst()
        if (samplesUs.size < 20) return null

        // Median is intentionally used instead of average because MPEG-TS
        // timestamps can contain occasional outliers around discontinuities.
        val ordered = samplesUs.sorted()
        val medianUs = ordered[ordered.size / 2].toDouble()
        if (medianUs <= 0.0) return null
        return normalizeBroadcastRate((1_000_000.0 / medianUs).toFloat())
    }
}

internal fun normalizeBroadcastRate(value: Float): Float {
    if (value <= 0f || !value.isFinite()) return 0f
    val common = floatArrayOf(
        23.976f, 24f, 25f, 29.97f, 30f,
        47.952f, 48f, 50f, 59.94f, 60f,
    )
    val nearest = common.minByOrNull { abs(it - value) } ?: return value
    return if (abs(nearest - value) <= 1.2f) nearest else value
}

/** Keep the current HDMI/output resolution and only choose among refresh rates. */
internal fun chooseSameResolutionMode(display: Display, fps: Float): Display.Mode {
    val current = display.mode
    if (fps <= 0f || !fps.isFinite()) return current

    val candidates = display.supportedModes.filter {
        it.physicalWidth == current.physicalWidth &&
            it.physicalHeight == current.physicalHeight
    }.ifEmpty { listOf(current) }

    val exactMultiples = candidates.filter { mode ->
        val ratio = mode.refreshRate / fps
        val nearest = round(ratio)
        nearest >= 1f && abs(ratio - nearest) <= 0.02f
    }

    // Prefer the highest exact multiple. 29.97 -> 59.94 and 25 -> 50/100.
    return exactMultiples.maxByOrNull { it.refreshRate }
        ?: candidates.minByOrNull { mode ->
            val ratio = (mode.refreshRate / fps).coerceAtLeast(1f)
            val nearest = round(ratio)
            abs(mode.refreshRate - fps * nearest)
        }
        ?: current
}

internal fun applyCadenceMode(activity: Activity, fps: Float): String {
    val display = activity.windowManager.defaultDisplay ?: return "—"
    val target = chooseSameResolutionMode(display, fps)
    val lp = activity.window.attributes
    if (lp.preferredDisplayModeId != target.modeId) {
        lp.preferredDisplayModeId = target.modeId
        activity.window.attributes = lp
    }
    return formatDisplayMode(target)
}

internal fun formatDisplayMode(mode: Display.Mode): String =
    "${mode.physicalWidth}×${mode.physicalHeight} @ ${"%.2f".format(mode.refreshRate)} Hz"
