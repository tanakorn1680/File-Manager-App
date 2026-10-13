package com.releasemanager.ui

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar

/**
 * Drives a ProgressBar from real progress: it only moves forward and glides from one update to the next
 * instead of stepping. The bar's look (drawable, size) is untouched; only its resolution is raised to 1000.
 */
class SmoothProgress(private val bar: ProgressBar) {

    private var target = 0f
    private var shown = 0f
    private var animator: ValueAnimator? = null

    init { bar.max = STEPS }

    /** [fraction] 0..1; a value lower than the current target is ignored. */
    fun set(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (f <= target) return
        target = f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, f).apply {
            duration = GLIDE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                bar.progress = (shown * STEPS).toInt()
            }
            start()
        }
    }

    /** Back to empty, for a new transfer. */
    fun reset() {
        animator?.cancel()
        animator = null
        target = 0f
        shown = 0f
        bar.progress = 0
    }

    private companion object {
        const val STEPS = 1000

        /** A little longer than the 400 ms between updates, so the bar never waits for the next one. */
        const val GLIDE_MS = 500L
    }
}
