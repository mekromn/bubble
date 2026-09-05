package com.mekromn.bubble

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

internal object Ui {
    const val BG = 0xff080b12.toInt()
    const val SURFACE = 0xff161e2c.toInt()
    const val SURFACE_HIGH = 0xff202c40.toInt()
    const val BLUE = 0xffadcaff.toInt()
    const val MINT = 0xff8de0c7.toInt()
    const val TEXT = 0xffedf2ff.toInt()
    const val MUTED = 0xffa5afc1.toInt()
    const val LINE = 0xff2c3a50.toInt()
    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    fun dp(c: Context, n: Float): Int = (n * c.resources.displayMetrics.density + 0.5f).toInt()
    fun shape(c: Context, color: Int = SURFACE, radius: Float = 24f, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(c, radius).toFloat()
            if (border != null) setStroke(dp(c, 1f), border)
        }
    fun ripple(c: Context, color: Int = SURFACE, radius: Float = 24f) =
        RippleDrawable(ColorStateList.valueOf(0x25adcaff), shape(c, color, radius), null)
    fun text(c: Context, value: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun show(view: View, shown: Boolean) {
        view.animate().cancel()
        if (shown) {
            view.visibility = View.VISIBLE
            if (!ValueAnimator.areAnimatorsEnabled()) { view.alpha = 1f; view.translationY = 0f; return }
            view.alpha = 0f; view.translationY = dp(view.context, 18f).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(210).setInterpolator(ease).start()
        } else if (ValueAnimator.areAnimatorsEnabled()) {
            view.animate().alpha(0f).translationY(dp(view.context, 12f).toFloat()).setDuration(150)
                .setInterpolator(ease).withEndAction { view.visibility = View.GONE; view.translationY = 0f }.start()
        } else view.visibility = View.GONE
    }
}

/** Lightweight vector control: cached paints/path, no per-frame bitmaps or allocations. */
internal class GlyphView(c: Context, var glyph: String, label: String, private val accented: Boolean = false) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    var count: Int = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    init {
        contentDescription = label; isFocusable = true; isClickable = true
        background = Ui.ripple(c, if (accented) Ui.SURFACE_HIGH else Ui.BG, 20f)
        minimumWidth = Ui.dp(c, 48f); minimumHeight = Ui.dp(c, 48f)
        tooltipText = label
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = Ui.dp(context, 24f).toFloat()
        canvas.save(); canvas.translate((width-size)/2, (height-size)/2); canvas.scale(size/24, size/24)
        paint.color = if (accented) Ui.BLUE else Ui.TEXT
        paint.alpha = if (isEnabled) 255 else 75
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f
        path.reset()
        when (glyph) {
            "back", "forward" -> {
                if (glyph == "forward") { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }
                path.moveTo(14f, 5f); path.lineTo(7f, 12f); path.lineTo(14f, 19f); canvas.drawPath(path, paint)
            }
            "add" -> { canvas.drawLine(12f, 5f, 12f, 19f, paint); canvas.drawLine(5f,12f,19f,12f,paint) }
            "close" -> { canvas.drawLine(6f,6f,18f,18f,paint); canvas.drawLine(18f,6f,6f,18f,paint) }
            "menu" -> { paint.style=Paint.Style.FILL; for (x in listOf(5f,12f,19f)) canvas.drawCircle(x,12f,1.5f,paint) }
            "reload" -> {
                canvas.drawArc(4f,4f,20f,20f,35f,290f,false,paint)
                path.moveTo(19f,3f); path.lineTo(19f,8f); path.lineTo(14f,8f); canvas.drawPath(path,paint)
            }
            "bubble" -> {
                canvas.drawRoundRect(3f,3f,21f,17f,6f,6f,paint)
                path.moveTo(7f,17f);path.lineTo(7f,21f);path.lineTo(12f,17f);canvas.drawPath(path,paint)
                canvas.drawLine(8f,9f,16f,9f,paint)
            }
            "tabs" -> {
                canvas.drawRoundRect(3f,3f,21f,21f,5f,5f,paint)
                paint.style=Paint.Style.FILL;paint.textSize=10f;paint.textAlign=Paint.Align.CENTER
                canvas.drawText(if(count>99) "99+" else count.toString(),12f,15.7f,paint)
            }
            else -> { canvas.drawCircle(12f,12f,8f,paint);canvas.drawLine(4f,12f,20f,12f,paint);canvas.drawOval(8f,4f,16f,20f,paint) }
        }
        canvas.restore()
    }
}
