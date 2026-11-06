package cat.sergibonet.diagnosi

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object Pal {
    val BG = 0xFF000000.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val GRAY = 0xFFB4BCC4.toInt()
    val AMBER = 0xFFFFB300.toInt()
    val YELLOW = 0xFFFFE100.toInt()
    val PINK = 0xFFFF5FA8.toInt()
    val GREEN = 0xFF3DDC84.toInt()
    val RED = 0xFFFF5252.toInt()
    val PANEL = 0xFF14181C.toInt()
    val DRUM = 0xFF1A1E23.toInt()
    val RING = 0xFF59626B.toInt()
    val BUTTON = 0xFF2B3036.toInt()
    val BUTTON_PRESSED = 0xFF4A535D.toInt()
}

/** Pintor de text amb ajust automàtic a una amplada màxima. */
class Txt(color: Int, bold: Boolean = true) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun draw(
        c: Canvas, s: String, x: Float, y: Float, size: Float,
        align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f, alpha: Int = 255
    ) {
        p.textSize = size
        if (maxW > 0f) {
            val w = p.measureText(s)
            if (w > maxW) p.textSize = size * maxW / w
        }
        p.textAlign = align
        p.alpha = alpha
        c.drawText(s, x, y, p)
    }
}

/** Rodes de dígits com un comptaquilòmetres mecànic. */
class DrumPainter(
    private val cellW: Float,
    private val cellH: Float,
    private val gap: Float,
    private val accentLast: Boolean,
    private val dimLeading: Boolean,
    private val decimalBeforeLast: Boolean = false
) {
    /** Espai extra per a la coma decimal, abans de l'última roda. */
    private val sepW = if (decimalBeforeLast) cellW * 0.4f else 0f
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = cellH * 0.78f
        color = Pal.AMBER
    }
    private val bg = Paint().apply { color = Pal.DRUM }
    private val digit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = cellH * 0.78f
    }
    private val shade = Paint().apply {
        shader = LinearGradient(
            0f, 0f, 0f, cellH,
            intArrayOf(0xF0000000.toInt(), 0x00000000, 0x00000000, 0xF0000000.toInt()),
            floatArrayOf(0f, 0.3f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Pal.RING
    }
    private val baseline = cellH / 2f - (digit.descent() + digit.ascent()) / 2f

    fun width(count: Int): Float = count * cellW + (count - 1) * gap + sepW

    /** `pos`: posicions de les rodes, començant per la de més a l'esquerra. */
    fun draw(c: Canvas, x: Float, y: Float, pos: DoubleArray) {
        if (decimalBeforeLast && pos.size > 1) {
            val last = pos.size - 1
            val sx = x + last * (cellW + gap) + sepW / 2f - gap / 2f
            c.drawText(",", sx, y + baseline, sepPaint)
        }
        for (i in pos.indices) {
            val p = pos[i]
            var higherZero = true
            for (j in 0 until i) if (pos[j] >= 1.0) higherZero = false
            val leading = dimLeading && i < pos.size - 1 && higherZero && p < 1.0
            val cx = x + i * (cellW + gap) + (if (decimalBeforeLast && i == pos.size - 1) sepW else 0f)
            c.save()
            c.translate(cx, y)
            c.clipRect(0f, 0f, cellW, cellH)
            c.drawRect(0f, 0f, cellW, cellH, bg)
            val fl = floor(p)
            val frac = (p - fl).toFloat()
            val d0 = (fl.toLong() % 10L).toInt()
            val d1 = (d0 + 1) % 10
            digit.color = if (accentLast && i == pos.size - 1) Pal.AMBER else Pal.WHITE
            drawDigit(c, d0, baseline - frac * cellH, leading)
            drawDigit(c, d1, baseline + (1f - frac) * cellH, false)
            c.drawRect(0f, 0f, cellW, cellH, shade)
            c.restore()
            c.drawRect(cx, y, cx + cellW, y + cellH, border)
        }
    }

    private fun drawDigit(c: Canvas, d: Int, y: Float, dim: Boolean) {
        digit.alpha = if (dim && d == 0) 70 else 255
        c.drawText(d.toString(), cellW / 2f, y, digit)
    }
}

/** Brúixola dibuixada: la targeta gira i el rumb actual queda a dalt. */
class CompassPainter {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PANEL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Pal.RING
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.GRAY
        strokeWidth = 2.5f
    }
    private val tickBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.WHITE
        strokeWidth = 4f
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.AMBER }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.WHITE }
    private val north = Txt(Pal.RED)
    private val cardinal = Txt(Pal.WHITE)
    private val inter = Txt(Pal.GRAY)
    private val path = Path()
    private val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    /** Rumb en graus: tres rodes com les de l'altímetre (cel·les de 20 x 28, per sota la fletxa). */
    private val headDrum = DrumPainter(20f, 28f, 2f, false, false)

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, headingDeg: Float, alpha: Int) {
        val s = r / 112f
        tick.alpha = alpha
        tickBig.alpha = alpha
        arrow.alpha = alpha
        c.drawCircle(cx, cy, r, face)
        c.drawCircle(cx, cy, r, ring)

        c.save()
        c.rotate(-headingDeg, cx, cy)
        for (i in 0 until 36) {
            val big = i % 3 == 0
            c.save()
            c.rotate(i * 10f, cx, cy)
            c.drawLine(cx, cy - r + 5f * s, cx, cy - r + (if (big) 24f else 14f) * s, if (big) tickBig else tick)
            c.restore()
        }
        for (i in 0 until 8) {
            val isCardinal = i % 2 == 0
            c.save()
            c.rotate(i * 45f, cx, cy)
            val painter = if (i == 0) north else if (isCardinal) cardinal else inter
            painter.draw(
                c, names[i], cx, cy - r + (if (isCardinal) 58f else 50f) * s,
                (if (isCardinal) 30f else 18f) * s, Paint.Align.CENTER, 0f, alpha
            )
            c.restore()
        }
        c.restore()

        // Marcador fix a dalt: indica la direcció de la marxa.
        path.reset()
        path.moveTo(cx - 13f * s, cy - r - 5f * s)
        path.lineTo(cx + 13f * s, cy - r - 5f * s)
        path.lineTo(cx, cy - r + 20f * s)
        path.close()
        c.drawPath(path, marker)

        // Fletxa central fixa.
        path.reset()
        path.moveTo(cx, cy - 26f * s)
        path.lineTo(cx + 15f * s, cy + 20f * s)
        path.lineTo(cx, cy + 8f * s)
        path.lineTo(cx - 15f * s, cy + 20f * s)
        path.close()
        c.drawPath(path, arrow)

        // Rumb només en graus, dins d'un quadre de rodes, sota la fletxa (de x = -32 a +32, y = +24 a +52).
        val deg = if (headingDeg.isNaN()) 0.0 else headingDeg.toDouble()
        if (alpha < 255) {
            c.saveLayerAlpha(cx - 40f * s, cy + 18f * s, cx + 40f * s, cy + 58f * s, alpha)
        } else {
            c.save()
        }
        c.translate(cx - 32f * s, cy + 24f * s)
        c.scale(s, s)
        headDrum.draw(c, 0f, 0f, Drum.positions(deg, 3).reversedArray())
        c.restore()
    }
}

/**
 * Altímetre (Beta 4). Una sola escala 0-9 amb tres lectures del mateix valor corregit:
 *  - agulla groga, llarga i prima: 0-99 m (una volta = 100 m; cada xifra = 10 m);
 *  - agulla blanca, prima i més curta, sense fletxa: centenars (una volta = 1.000 m);
 *  - rotatiu horitzontal de 4 rodes, entre les 7 i les 6: metres, de 0000 a 4000 (i més).
 * Les agulles són contínues i van per sobre del rotatiu (l'ull endevina el que tapen).
 */
class AltimeterPainter {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PANEL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Pal.RING
    }
    private val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.GRAY
        strokeWidth = 1.5f
    }
    private val major = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.WHITE
        strokeWidth = 3f
    }
    private val num = Txt(Pal.WHITE)
    private val label = Txt(Pal.GRAY)
    private val yellowHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.YELLOW
        strokeCap = Paint.Cap.ROUND
    }
    private val whiteHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.WHITE
        strokeCap = Paint.Cap.BUTT
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.WHITE }
    private val hubDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.YELLOW }

    /** Rotatiu de 4 rodes (cel·les en unitats d'una esfera de radi 112): 22 x 32. */
    private val drum = DrumPainter(22f, 32f, 2f, false, true)

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, altM: Double) {
        val s = r / 112f
        c.drawCircle(cx, cy, r, face)
        c.drawCircle(cx, cy, r, ring)

        // 50 marques (una cada 2 m per a l'agulla groga), una de llarga cada 5 (= 10 m).
        for (i in 0 until 50) {
            val big = i % 5 == 0
            c.save()
            c.rotate(i * 7.2f, cx, cy)
            c.drawLine(cx, cy - r + 6f * s, cx, cy - r + (if (big) 18f else 11f) * s, if (big) major else minor)
            c.restore()
        }

        // Xifres 0-9 sempre dretes.
        val rr = r - 30f * s
        val size = 17f * s
        for (k in 0 until 10) {
            val a = Math.toRadians(k * 36.0)
            val x = cx + (rr * Math.sin(a)).toFloat()
            val y = cy - (rr * Math.cos(a)).toFloat() + size * 0.35f
            num.draw(c, k.toString(), x, y, size, Paint.Align.CENTER)
        }

        // "ALT" al semicercle superior, entre el centre i la xifra 0.
        label.draw(c, "ALT", cx, cy - 34f * s, 16f * s, Paint.Align.CENTER)

        val a = if (altM.isNaN()) 0.0 else if (altM < 0.0) 0.0 else altM

        // Rotatiu horitzontal entre les 7 i les 6 (de x = -66 a +28, de y = +24 a +56).
        c.save()
        c.translate(cx - 66f * s, cy + 24f * s)
        c.scale(s, s)
        drum.draw(c, 0f, 0f, Drum.positions(if (altM.isNaN()) 0.0 else a, 4).reversedArray())
        c.restore()

        if (!altM.isNaN()) {
            val angHundreds = ((a % 1000.0) / 1000.0 * 360.0).toFloat()
            val angTens = ((a % 100.0) / 100.0 * 360.0).toFloat()

            // Agulla blanca (centenars): línia prima, sense fletxa, més curta que la groga.
            c.save()
            c.rotate(angHundreds, cx, cy)
            whiteHand.strokeWidth = 4f * s
            c.drawLine(cx, cy + 14f * s, cx, cy - 78f * s, whiteHand)
            c.restore()

            // Agulla groga (0-99 m): fina i llarga, per sobre de l'altra.
            c.save()
            c.rotate(angTens, cx, cy)
            yellowHand.strokeWidth = 3.2f * s
            c.drawLine(cx, cy + 24f * s, cx, cy - r + 10f * s, yellowHand)
            c.restore()
        }
        c.drawCircle(cx, cy, 9f * s, hub)
        c.drawCircle(cx, cy, 4f * s, hubDot)
    }
}

/**
 * Gràfic de velocitat (rosa) i alçada (verd) d'una finestra de temps del viatge. Les dues escales són
 * variables: la velocitat va de 0 al màxim de la finestra (graella i números cada 20 km/h) i l'alçada
 * del mínim al màxim de la finestra (marge mínim de 50 m). L'eix de temps és fix: el traç no es comprimeix
 * mai i una parada llarga es veu com un forat. Amb `timeLabels`, a baix hi ha l'hora real cada 15 minuts
 * comptant des de l'inici del viatge (si comença a les 15:16, les marques són 15:16, 15:31, 15:46...).
 */
class ProfileChartPainter {
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PANEL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Pal.RING
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.RING
        strokeWidth = 1.5f
    }
    private val timeGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.RING
        strokeWidth = 1.5f
        alpha = 150
    }
    private val pinkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Pal.PINK
    }
    private val greenLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Pal.GREEN
    }
    private val pinkDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PINK }
    private val greenDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.GREEN }
    private val pinkTxt = Txt(Pal.PINK)
    private val greenTxt = Txt(Pal.GREEN)
    private val grayTxt = Txt(Pal.GRAY, false)
    private val box = RectF()
    private val path = Path()

    fun draw(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, p: TripProfile,
        windowMs: Long, timeLabels: Boolean
    ) {
        box.set(l, t, r, b)
        c.drawRoundRect(box, 14f, 14f, panel)
        c.drawRoundRect(box, 14f, 14f, border)

        // Àrea del traçat: marge a l'esquerra i a la dreta per als números dels eixos
        // (i a baix, per a les hores, quan n'hi ha).
        val pl = l + 64f
        val pr = r - 70f
        val pt = t + 46f
        val pb = b - (if (timeLabels) 46f else 16f)

        val wb = p.windowBounds(windowMs)
        val sAxis = p.speedAxis(wb)
        val aAxis = p.altAxis(wb)

        pinkTxt.draw(c, "km/h", pl - 8f, t + 28f, 19f, Paint.Align.RIGHT)
        greenTxt.draw(c, "m", pr + 8f, t + 28f, 19f, Paint.Align.LEFT)

        // Velocitat: graella i número a cada pas (20 km/h), de 0 al final de l'eix.
        val top = sAxis[0]
        val step = sAxis[1]
        val steps = Math.round(top / step).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val y = pb - (pb - pt) * i / steps.toFloat()
            c.drawLine(pl, y, pr, y, grid)
            pinkTxt.draw(c, Fmt.num(step * i, 0), pl - 8f, y + 7f, 20f, Paint.Align.RIGHT)
        }
        // Alçada: tres números (mínim, mig i màxim).
        for (i in 0..2) {
            val y = pb - (pb - pt) * i / 2f
            val av = if (aAxis == null) "--" else Fmt.num(aAxis[0] + (aAxis[1] - aAxis[0]) * i / 2.0, 0)
            greenTxt.draw(c, av, pr + 8f, y + 7f, 20f, Paint.Align.LEFT)
        }

        if (wb == null) {
            grayTxt.draw(c, "Encara sense dades", (pl + pr) / 2f, (pt + pb) / 2f + 8f, 24f, Paint.Align.CENTER)
            return
        }
        val w = (wb[1] - wb[0]).toFloat()

        // Hores reals cada 15 minuts, comptant des de l'hora d'inici del viatge.
        if (timeLabels) {
            val tz = TimeZone.getDefault()
            val start = p.startMs
            var k = if (wb[0] <= start) 0L else (wb[0] - start + QUARTER_MS - 1L) / QUARTER_MS
            while (true) {
                val tm = start + k * QUARTER_MS
                if (tm > wb[1]) break
                val x = pl + (pr - pl) * ((tm - wb[0]) / w)
                c.drawLine(x, pt, x, pb, timeGrid)
                grayTxt.draw(c, Fmt.hm(tm, tz), x, pb + 32f, 19f, Paint.Align.CENTER)
                k++
            }
        }

        c.save()
        c.clipRect(pl - 2f, pt - 8f, pr + 10f, pb + 8f)

        // Primera mostra de la finestra (i la que hi ha just abans, perquè el traç entri des de la vora).
        var from = 0
        while (from < p.n && p.t[from] < wb[0]) from++
        if (from > 0) from--
        val last = p.n - 1

        // Alçada (verd).
        if (aAxis != null) {
            val span = max(aAxis[1] - aAxis[0], 1.0)
            path.rewind()
            var pen = false
            var lx = 0f
            var ly = 0f
            for (i in from..last) {
                val a = p.alt[i]
                if (a.isNaN()) {
                    pen = false
                    continue
                }
                val x = pl + (pr - pl) * ((p.t[i] - wb[0]) / w)
                val y = pb - (pb - pt) * ((a - aAxis[0]) / span).toFloat().coerceIn(0f, 1f)
                val gap = i > 0 && p.t[i] - p.t[i - 1] > TripProfile.GAP_MS
                if (!pen || gap) {
                    path.moveTo(x, y)
                    path.lineTo(x + 0.01f, y)
                } else {
                    path.lineTo(x, y)
                }
                pen = true
                lx = x
                ly = y
            }
            c.drawPath(path, greenLine)
            if (pen) c.drawCircle(lx, ly, 6f, greenDot)
        }

        // Velocitat (rosa), per sobre.
        path.rewind()
        for (i in from..last) {
            val x = pl + (pr - pl) * ((p.t[i] - wb[0]) / w)
            val y = pb - (pb - pt) * (p.speed[i] / top.toFloat()).coerceIn(0f, 1f)
            val gap = i > 0 && p.t[i] - p.t[i - 1] > TripProfile.GAP_MS
            if (i == from || gap) {
                path.moveTo(x, y)
                path.lineTo(x + 0.01f, y)
            } else {
                path.lineTo(x, y)
            }
            if (i == last) c.drawCircle(x, y, 6f, pinkDot)
        }
        c.drawPath(path, pinkLine)
        c.restore()
    }

    private companion object {
        const val QUARTER_MS = 15L * 60000L
    }
}

/** Rellotge analògic. */
class ClockPainter {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.PANEL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Pal.RING
    }
    private val tickP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.GRAY
        strokeCap = Paint.Cap.ROUND
    }
    private val handP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val secP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.RED
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f
    }
    private val num = Txt(Pal.WHITE)
    private val cal = Calendar.getInstance()

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, timeMs: Long, tz: TimeZone) {
        cal.timeZone = tz
        cal.timeInMillis = timeMs
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val minute = cal.get(Calendar.MINUTE) + sec / 60f
        val hour = cal.get(Calendar.HOUR) + minute / 60f

        c.drawCircle(cx, cy, r, face)
        c.drawCircle(cx, cy, r, ring)
        for (i in 0 until 60) {
            val big = i % 5 == 0
            tickP.strokeWidth = if (big) 5f else 2f
            c.save()
            c.rotate(i * 6f, cx, cy)
            c.drawLine(cx, cy - r + 8f, cx, cy - r + (if (big) 26f else 16f), tickP)
            c.restore()
        }
        val ns = r * 0.30f
        num.draw(c, "12", cx, cy - r + 34f + ns * 0.55f, ns, Paint.Align.CENTER)
        num.draw(c, "6", cx, cy + r - 34f + ns * 0.1f, ns, Paint.Align.CENTER)
        num.draw(c, "3", cx + r - 38f, cy + ns * 0.35f, ns, Paint.Align.CENTER)
        num.draw(c, "9", cx - r + 38f, cy + ns * 0.35f, ns, Paint.Align.CENTER)

        c.save()
        c.rotate(hour * 30f, cx, cy)
        handP.strokeWidth = 11f
        c.drawLine(cx, cy + r * 0.10f, cx, cy - r * 0.50f, handP)
        c.restore()

        c.save()
        c.rotate(minute * 6f, cx, cy)
        handP.strokeWidth = 7f
        c.drawLine(cx, cy + r * 0.12f, cx, cy - r * 0.76f, handP)
        c.restore()

        c.save()
        c.rotate(sec * 6f, cx, cy)
        c.drawLine(cx, cy + r * 0.20f, cx, cy - r * 0.84f, secP)
        c.restore()

        c.drawCircle(cx, cy, 9f, secP)
    }
}

/**
 * Pàgina dibuixada amb coordenades virtuals fixes (la pantalla s'hi adapta),
 * amb un bucle d'animació d'uns 30 fotogrames per segon.
 */
abstract class BasePage(
    ctx: Context,
    private val vw: Float,
    private val vh: Float,
    private val fullWidth: Boolean = false
) : View(ctx) {

    /**
     * Alçada virtual de la pàgina: mai menys de `vh`. L'escala es calcula com si la pàgina fos
     * Cfg.BAR_SAVED_DP més baixa (és la que s'havia validat), així el dibuix no creix i l'espai
     * guanyat queda lliure a baix.
     */
    protected var ph: Float = vh
        private set

    /**
     * Amplada virtual de la pàgina. Amb `fullWidth` la pàgina ocupa tota l'amplada de la pantalla
     * (com les pestanyes de dalt) i l'amplada virtual s'estén; si no, és fixa i la pàgina es centra.
     */
    protected var pw: Float = vw
        private set


    /** Segons transcorreguts des del fotograma anterior. */
    protected var dt = 0.03f

    private var lastNs = 0L
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private val btnFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.BUTTON }
    private val btnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Pal.RING
    }
    private val btnTxt = Txt(Pal.WHITE)
    private val labelTxt = Txt(Pal.GRAY, false)
    private val valueTxt = Txt(Pal.WHITE)

    /** Coordenada de pantalla (píxels) d'un punt virtual; vàlida un cop dibuixat el primer fotograma. */
    protected fun pxX(x: Float): Float = offX + x * scale
    protected fun pxY(y: Float): Float = offY + y * scale

    protected abstract fun paintPage(c: Canvas)

    protected open fun onTap(x: Float, y: Float) {}

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        dt = if (lastNs == 0L) 0.03f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.2f)
        lastNs = now
        canvas.drawColor(Pal.BG)
        val refH = max(height - Cfg.BAR_SAVED_DP * resources.displayMetrics.density, 1f)
        scale = max(min(width / vw, refH / vh), 0.0001f)
        if (fullWidth) {
            pw = width / scale
            offX = 0f
        } else {
            pw = vw
            offX = (width - vw * scale) / 2f
        }
        ph = max(height / scale, vh)
        offY = 0f
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        paintPage(canvas)
        canvas.restore()
        postInvalidateDelayed(33L)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            onTap((e.x - offX) / scale, (e.y - offY) / scale)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    protected fun drawButton(c: Canvas, r: RectF, label: String, size: Float = 40f) {
        c.drawRoundRect(r, 18f, 18f, btnFill)
        c.drawRoundRect(r, 18f, 18f, btnStroke)
        btnTxt.draw(c, label, r.centerX(), r.centerY() + size * 0.35f, size, Paint.Align.CENTER, r.width() - 24f)
    }

    /** Fila d'informació: etiqueta a l'esquerra i valor alineat a la dreta. */
    protected fun row(
        c: Canvas, label: String, value: String, y: Float, x0: Float, x1: Float,
        labelSize: Float, valueSize: Float, valueColor: Int = Pal.WHITE
    ) {
        labelTxt.draw(c, label, x0, y, labelSize, Paint.Align.LEFT, (x1 - x0) * 0.55f)
        if (valueColor == Pal.WHITE) {
            valueTxt.draw(c, value, x1, y, valueSize, Paint.Align.RIGHT, (x1 - x0) * 0.62f)
        } else {
            Txt(valueColor).draw(c, value, x1, y, valueSize, Paint.Align.RIGHT, (x1 - x0) * 0.62f)
        }
    }
}
