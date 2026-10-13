package com.releasemanager.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.releasemanager.R

/** Large notification icons drawn from the app logo: plain while running, with a green ✓ / red ✕ badge when finished. */
object NotifIcons {

    private const val RING = 0xFF1B1E23.toInt() // the logo's own dark, so the badge looks cut into the picture

    private fun side(ctx: Context): Int =
        ctx.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width).coerceAtLeast(96)

    // Built once: a progress notification is re-posted every second and must not decode and draw a bitmap each time
    @Volatile private var logoCache: Bitmap? = null
    @Volatile private var okCache: Bitmap? = null
    @Volatile private var failCache: Bitmap? = null

    fun logo(ctx: Context): Bitmap = logoCache ?: drawLogo(ctx.applicationContext).also { logoCache = it }

    fun result(ctx: Context, ok: Boolean): Bitmap {
        val cached = if (ok) okCache else failCache
        if (cached != null) return cached
        val built = drawResult(ctx.applicationContext, ok)
        if (ok) okCache = built else failCache = built
        return built
    }

    /** The app logo with rounded corners (same 22 % as the logo on the connect screen). */
    private fun drawLogo(ctx: Context): Bitmap {
        val s = side(ctx)
        val src = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_app)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val r = s * 0.22f
        c.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), r, r, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, null, RectF(0f, 0f, s.toFloat(), s.toFloat()), p)
        src.recycle()
        return out
    }

    /** The logo plus a round badge in the bottom-right corner: green with a white check, or red with a white cross. */
    private fun drawResult(ctx: Context, ok: Boolean): Bitmap {
        val out = logo(ctx).copy(Bitmap.Config.ARGB_8888, true)
        val s = out.width.toFloat()
        val c = Canvas(out)
        val r = s * 0.23f
        val cx = s - r - s * 0.02f
        val cy = s - r - s * 0.02f

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = RING
        c.drawCircle(cx, cy, r, p)
        p.color = ContextCompat.getColor(ctx, if (ok) R.color.success else R.color.danger)
        c.drawCircle(cx, cy, r * 0.82f, p)

        p.style = Paint.Style.STROKE
        p.color = 0xFFFFFFFF.toInt()
        p.strokeWidth = r * 0.28f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val path = Path()
        if (ok) {
            path.moveTo(cx - r * 0.34f, cy + r * 0.02f)
            path.lineTo(cx - r * 0.08f, cy + r * 0.30f)
            path.lineTo(cx + r * 0.38f, cy - r * 0.28f)
        } else {
            path.moveTo(cx - r * 0.27f, cy - r * 0.27f)
            path.lineTo(cx + r * 0.27f, cy + r * 0.27f)
            path.moveTo(cx + r * 0.27f, cy - r * 0.27f)
            path.lineTo(cx - r * 0.27f, cy + r * 0.27f)
        }
        c.drawPath(path, p)
        return out
    }
}
