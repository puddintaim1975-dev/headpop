package dev.zerokool.headpop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import java.util.PriorityQueue
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Draws comedic balloons over detected faces. Pinch a head with two fingers:
 * the balloon compresses with your pinch; close far enough and it POPs
 * (particles + haptic + sound + points). Release early and it boings back.
 */
class FaceOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    interface GameEvents {
        fun onScore(score: Int, combo: Int, best: Int)
    }

    var gameEvents: GameEvents? = null
    var mirrored = false

    data class FaceBox(val id: Int, val l: Float, val t: Float, val r: Float, val b: Float,
                       val tex: Bitmap? = null)

    @Volatile private var faces: List<FaceBox> = emptyList()
    @Volatile private var upW = 1
    @Volatile private var upH = 1

    private class Head {
        var cx = 0f; var cy = 0f; var r = 0f
        var squish = 0f
        var releaseAt = -1L
        var releaseFrom = 0f
        var poppedAt = -1_000_000L
        var lastSeen = 0L
        val hue = Random.nextFloat()
        var tex: Bitmap? = null
    }

    private val heads = HashMap<Int, Head>()

    // ---- pinch state ----
    private var pinchId1 = -1
    private var pinchId2 = -1
    private var pinchStartSpan = 0f
    private var pinchHeadId = -1
    private var lastSquishStep = -1
    private val pinchHead: Head? get() = heads[pinchHeadId]

    // ---- effects ----
    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float,
                    val color: Int, val size: Float, var born: Long)
    private val particles = ArrayList<P>()
    private class PopTxt(val x: Float, val y: Float, val text: String, var born: Long)
    private val popups = ArrayList<PopTxt>()

    private val sfx = Sfx(ctx)
    private val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private var score = 0
    private var combo = 0
    private var best = 0
    private var lastPopAt = 0L
    private var everPopped = false

    // ---- paints (allocated once) ----
    private val balloonFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 150 }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7f; color = 0xFF2b2b2b.toInt()
    }
    private val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt() }
    private val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7a2020.toInt() }
    private val texPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val faceClipPath = Path()
    private val textureMatrix = Matrix()
    private val bang = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 46f; textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt()); typeface = Typeface.DEFAULT_BOLD
    }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt(); textSize = 54f; textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt()); typeface = Typeface.DEFAULT_BOLD
    }

    companion object {
        private const val COOLDOWN_MS = 1200L
        private const val SQUISH_GAIN = 2.6f
        private const val COMBO_WINDOW_MS = 2200L
        private val POP_COLORS = intArrayOf(
            0xFFFF5A5A.toInt(), 0xFFFFC83C.toInt(), 0xFF50DC78.toInt(),
            0xFF5AAAFF.toInt(), 0xFFDC78FF.toInt()
        )
    }

    fun updateFaces(list: List<FaceBox>, uw: Int, uh: Int) {
        faces = list; upW = uw; upH = uh
        post { invalidate() }
    }

    // ---------- geometry: upright-frame boxes -> view coords (FILL_CENTER) ----------
    private fun mapX(x: Float): Float {
        val s = max(width.toFloat() / upW, height.toFloat() / upH)
        var mx = (x - upW / 2f) * s + width / 2f
        if (mirrored) mx = width - mx
        return mx
    }
    private fun mapY(y: Float): Float {
        val s = max(width.toFloat() / upW, height.toFloat() / upH)
        return (y - upH / 2f) * s + height / 2f
    }

    private fun syncHeads(now: Long) {
        for (f in faces) {
            val h = heads.getOrPut(f.id) { Head() }
            val l = mapX(f.l); val r = mapX(f.r)
            val t = mapY(f.t); val b = mapY(f.b)
            h.cx = (l + r) / 2f
            h.cy = t + (b - t) * 0.45f
            h.r = max(r - l, (b - t) * 0.95f) * 0.55f
            h.lastSeen = now
            if (f.tex != null && f.tex !== h.tex) {
                h.tex?.recycle()
                h.tex = f.tex
            }
        }
        val it = heads.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key != pinchHeadId && now - e.value.lastSeen > 700) {
                e.value.tex?.recycle()
                it.remove()
            }
        }
    }

    // ---------- touch: the squish ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = System.currentTimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2 && pinchHead == null) {
                    syncHeads(now)
                    val i1 = 0
                    val i2 = 1
                    val mx = (e.getX(i1) + e.getX(i2)) / 2f
                    val my = (e.getY(i1) + e.getY(i2)) / 2f
                    val target = heads.values.minByOrNull {
                        hypot(it.cx - mx, it.cy - my)
                    }
                    if (target != null && hypot(target.cx - mx, target.cy - my) < target.r * 1.6f
                        && now - target.poppedAt > COOLDOWN_MS) {
                        pinchHeadId = heads.entries.first { it.value === target }.key
                        pinchId1 = e.getPointerId(i1)
                        pinchId2 = e.getPointerId(i2)
                        pinchStartSpan = max(24f, hypot(
                            e.getX(i2) - e.getX(i1), e.getY(i2) - e.getY(i1)))
                        target.squish = 0f
                        target.releaseAt = -1L
                        lastSquishStep = 0
                        sfx.playSquish(0f)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val h = pinchHead
                if (h != null) {
                    val i1 = e.findPointerIndex(pinchId1)
                    val i2 = e.findPointerIndex(pinchId2)
                    if (i1 >= 0 && i2 >= 0) {
                        val span = hypot(
                            e.getX(i2) - e.getX(i1), e.getY(i2) - e.getY(i1))
                        var q = (1f - span / pinchStartSpan) * SQUISH_GAIN
                        q = q.coerceIn(0f, 1f)
                        h.squish = q
                        val step = (q / 0.34f).toInt()
                        if (step > lastSquishStep) {
                            lastSquishStep = step
                            sfx.playSquish(q)
                        }
                        if (q >= 1f) pop(h, now)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val h = pinchHead
                if (h != null && h.squish < 1f) {
                    h.releaseAt = now
                    h.releaseFrom = h.squish
                    ensureAnim()
                }
                pinchHeadId = -1
                lastSquishStep = -1
            }
        }
        return true
    }

    private fun pop(h: Head, now: Long) {
        h.poppedAt = now
        h.squish = 0f
        pinchHeadId = -1
        lastSquishStep = -1
        everPopped = true
        combo = if (now - lastPopAt < COMBO_WINDOW_MS) combo + 1 else 1
        lastPopAt = now
        val gained = 100 * combo
        score += gained
        if (score > best) best = score
        popups.add(PopTxt(h.cx, h.cy - h.r, "+$gained" + if (combo > 1) " x$combo" else "", now))
        repeat(26) {
            val ang = Random.nextFloat() * (Math.PI * 2).toFloat()
            val spd = (0.25f + Random.nextFloat() * 0.6f) * h.r * 6f
            particles.add(P(
                h.cx, h.cy,
                kotlin.math.cos(ang) * spd, kotlin.math.sin(ang) * spd - h.r * 2f,
                POP_COLORS[Random.nextInt(POP_COLORS.size)],
                6f + Random.nextFloat() * 12f, now
            ))
        }
        gameEvents?.onScore(score, combo, best)
        buzz()
        sfx.playPop()
        ensureAnim()
    }

    private fun buzz() {
        if (Build.VERSION.SDK_INT >= 26)
            vib.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vib.vibrate(70)
    }

    // ---------- frame loop while effects are active ----------
    private var animRunning = false
    private fun ensureAnim() {
        if (animRunning) return
        animRunning = true
        Choreographer.getInstance().postFrameCallback {
            animRunning = false
            invalidate()
            val now = System.currentTimeMillis()
            val active = particles.isNotEmpty() || popups.isNotEmpty() ||
                heads.values.any {
                    it.releaseAt > 0 || (now - it.poppedAt in 0 until COOLDOWN_MS)
                }
            if (active) ensureAnim()
        }
    }

    // ---------- drawing ----------
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 52f; textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 0f, 0xFF000000.toInt()); typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = System.currentTimeMillis()
        syncHeads(now)
        for (h in heads.values) drawHead(c, h, now)
        drawParticles(c, now)
        drawPopups(c, now)
        if (!everPopped && pinchHead == null) {
            val pulse = 200 + (kotlin.math.sin(now / 350.0) * 60).toInt()
            hintPaint.alpha = pulse
            c.drawText("pinch a head with two fingers", width / 2f, height * 0.78f, hintPaint)
        }
    }

    private fun headQ(h: Head, now: Long): Float {
        if (h === pinchHead) return h.squish
        if (h.releaseAt > 0) {
            val t = (now - h.releaseAt) / 150f
            if (t >= 1f) { h.releaseAt = -1; return 0f }
            return h.releaseFrom * (1f - t)
        }
        return 0f
    }

    private fun drawHead(c: Canvas, h: Head, now: Long) {
        val q = headQ(h, now)
        val f = (now - h.poppedAt).toFloat() / COOLDOWN_MS  // pop lifecycle 0..1
        var ws = 1f - 0.72f * q
        var hs = 1f + 0.38f * q
        if (f in 0f..1f) {  // popped: flatten to pancake, then re-inflate with bounce
            hs = when {
                f < 0.12f -> 1f - 0.85f * (f / 0.12f)
                f < 0.62f -> 0.15f
                else -> min(1f, 0.15f + ((f - 0.62f) / 0.30f).let {
                    if (it < 0.5f) it * 1.25f else 1.25f - 0.25f * (it - 0.5f) * 2f
                } * 0.85f)
            }
            ws = 1f + (1f - hs) * 0.55f
        }
        val rx = h.r * ws
        val ry = h.r * hs
        balloonFill.color = Color.HSVToColor(floatArrayOf(h.hue * 360f, 0.45f, 1f))

        val tex = h.tex
        val oval = RectF(h.cx - rx, h.cy - ry, h.cx + rx, h.cy + ry)
        var featA = 1f
        if (tex != null && !tex.isRecycled) {
            // Tier 1: their actual face inside the balloon, bold comic outline
            faceClipPath.reset()
            faceClipPath.addOval(oval, Path.Direction.CW)
            c.save()
            c.clipPath(faceClipPath)
            val sx = (rx * 2f) / tex.width
            val sy = (ry * 2f) / tex.height
            if (mirrored) {
                textureMatrix.setScale(-sx, sy)
                textureMatrix.postTranslate(h.cx + rx, h.cy - ry)
            } else {
                textureMatrix.setScale(sx, sy)
                textureMatrix.postTranslate(h.cx - rx, h.cy - ry)
            }
            c.drawBitmap(tex, textureMatrix, texPaint)
            // unify with the balloon hue so it still reads as OUR balloon
            balloonFill.alpha = 110
            c.drawOval(oval, balloonFill)
            balloonFill.alpha = 150
            c.restore()
            rim.strokeWidth = 11f
            c.drawOval(oval, rim)
            rim.strokeWidth = 7f
            // their face at rest; comic panic face fades in as the squish builds
            featA = if (f in 0f..1f) 1f else (q * 4f).coerceIn(0f, 1f)
        } else {
            c.drawOval(oval, balloonFill)
            c.drawOval(oval, rim)
        }

        // eyes: bulge + pupils converge as squish grows; XX when popped
        val eyeY = h.cy - ry * 0.28f
        val eyeDx = rx * 0.38f
        val eyeR = max(4f, h.r * 0.16f * (1f + 0.35f * q))
        if (featA < 1f) {
            eyeWhite.alpha = (255 * featA).toInt()
            pupil.alpha = (255 * featA).toInt()
            mouth.alpha = (255 * featA).toInt()
        }
        if (f in 0f..0.62f) {
            // X X eyes
            for (s in floatArrayOf(-1f, 1f)) {
                val ex = h.cx + s * eyeDx
                val st = max(3f, eyeR * 0.5f)
                rim.strokeWidth = st
                c.drawLine(ex - eyeR, eyeY - eyeR, ex + eyeR, eyeY + eyeR, rim)
                c.drawLine(ex - eyeR, eyeY + eyeR, ex + eyeR, eyeY - eyeR, rim)
            }
            rim.strokeWidth = 7f
        } else {
            c.drawCircle(h.cx - eyeDx, eyeY, eyeR, eyeWhite)
            c.drawCircle(h.cx + eyeDx, eyeY, eyeR, eyeWhite)
            val conv = q * eyeR * 0.45f
            c.drawCircle(h.cx - eyeDx + conv, eyeY, eyeR * 0.42f, pupil)
            c.drawCircle(h.cx + eyeDx - conv, eyeY, eyeR * 0.42f, pupil)
            // mouth: a growing O of distress
            val mr = h.r * (0.10f + 0.30f * q)
            c.drawOval(h.cx - mr, h.cy + ry * 0.15f, h.cx + mr,
                h.cy + ry * 0.15f + mr * 2f, mouth)
        }
        if (featA < 1f) {
            eyeWhite.alpha = 255; pupil.alpha = 255; mouth.alpha = 255
        }
        if (q > 0.62f && f !in 0f..1f) {
            c.drawText("!!", h.cx, h.cy - ry - 18f, warnPaint)
        }
    }

    private fun drawParticles(c: Canvas, now: Long) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val t = (now - p.born) / 1000f
            if (t > 0.8f) { it.remove(); continue }
            p.vy += 900f * 0.016f  // gravity per frame-ish
            p.x += p.vx * 0.016f
            p.y += p.vy * 0.016f
            bang.color = p.color
            bang.alpha = ((1f - t / 0.8f) * 255).toInt()
            c.drawCircle(p.x, p.y, p.size * (1f - t), bang)
        }
    }

    private fun drawPopups(c: Canvas, now: Long) {
        val it = popups.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val t = (now - p.born) / 1000f
            if (t > 0.9f) { it.remove(); continue }
            txtPaint.alpha = ((1f - t / 0.9f) * 255).toInt()
            c.drawText(p.text, p.x, p.y - t * 120f, txtPaint)
        }
    }
}

/** Game SFX: pop + rubbery squish (pitch rises with compression) */
class Sfx(ctx: Context) {
    private val pool = SoundPool.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
        .setMaxStreams(6)
        .build()
    private val popId = pool.load(ctx, R.raw.pop, 1)
    private val squishId = pool.load(ctx, R.raw.squish, 1)

    fun playPop() { pool.play(popId, 1f, 1f, 1, 0, 1f) }

    /** level 0..1 — higher compression = higher-pitched squelch */
    fun playSquish(level: Float) {
        val rate = 0.7f + level.coerceIn(0f, 1f) * 1.1f
        pool.play(squishId, 0.8f, 0.8f, 1, 0, rate)
    }
}