package com.mekromn.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewTreeObserver
import kotlin.math.hypot
import kotlin.math.max

/** Spatial grammar: reveal from the bubble, conceal to it; never squash a webpage into an oval. */
internal class WindowMotion {
    private var running: Animator? = null
    private var epoch = 0
    var busy = false
        private set
    fun cancel() {
        epoch++; running?.removeAllListeners(); running?.cancel(); running = null; busy = false
    }
    fun reveal(view: View, x: Int, y: Int, bubbleRadius: Float, opening: Boolean, done: () -> Unit = {}) {
        cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { view.alpha = 1f; done(); return }
        val mine = epoch
        busy = true
        // Wait for the ONE destination layout to commit. Radius animation itself runs in HWUI.
        val start = {
            if (mine == epoch && view.isAttachedToWindow) {
                view.alpha = 1f
                val cx = x.coerceIn(0, view.width); val cy = y.coerceIn(0, view.height)
                val radius = hypot(max(cx, view.width - cx).toDouble(), max(cy, view.height - cy).toDouble()).toFloat()
                val animation = ViewAnimationUtils.createCircularReveal(view, cx, cy,
                    if (opening) bubbleRadius else radius, if (opening) radius else bubbleRadius)
                animation.duration = if (opening) 260L else 210L
                animation.interpolator = Ui.ease
                animation.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (mine == epoch) { running = null; busy = false; done() }
                    }
                })
                running = animation; animation.start()
            } else if (mine == epoch) { busy = false }
        }
        if (opening) {
            view.alpha = 0f
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
                    start(); return true
                }
            }
            view.viewTreeObserver.addOnPreDrawListener(listener); view.postInvalidateOnAnimation()
        } else start()
    }
    fun move(from: WindowBox, to: WindowBox, place: (WindowBox) -> Unit, done: () -> Unit) {
        cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || from == to) { place(to); done(); return }
        busy = true; val mine = epoch
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150; interpolator = Ui.ease
            addUpdateListener {
                val p = it.animatedValue as Float
                place(from.copy(x = (from.x + (to.x - from.x) * p).toInt(), y = (from.y + (to.y - from.y) * p).toInt()))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (mine == epoch) { busy = false; running = null; place(to); done() }
                }
            })
        }
        running = animation; animation.start()
    }
}
