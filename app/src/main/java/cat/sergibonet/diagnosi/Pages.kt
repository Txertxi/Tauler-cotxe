package cat.sergibonet.diagnosi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pàgina 1: el que cal mirar mentre es condueix. Ocupa tota l'amplada, com les pestanyes:
 * el velocímetre queda sota "Conducció" i les tres esferes, centrades sota "Viatge" + "Ajustaments".
 */
class DrivePage(ctx: Context) : BasePage(ctx, 1280f, 520f, true) {

    private val speedDrum = DrumPainter(DriveLayout.SPEED_CELL, 180f, DriveLayout.SPEED_GAP, false, true)
    private val tripDrum = DrumPainter(66f, 92f, 4f, true, true, true)
    private val compass = CompassPainter()
    private val altimeter = AltimeterPainter()
    private val clock = ClockPainter()
    private val chart = ProfileChartPainter()
    private val white = Txt(Pal.WHITE)
    private val whiteLight = Txt(Pal.WHITE, false)
    private val gray = Txt(Pal.GRAY, false)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cal = Calendar.getInstance()

    private var speedDisp = 0.0
    private var headingDisp = 0.0
    private var headingTarget = 0.0
    private var haveHeading = false
    private var altDisp = 0.0
    private var haveAlt = false
    private var distDisp = 0.0
    private var haveDist = false

    override fun paintPage(c: Canvas) {
        val fresh = GpsHub.hasFreshFix()
        val raw = GpsHub.speedKmh

        // Velocitat suavitzada: per sota del llindar es considera aturat (0).
        val target = if (fresh && raw >= Cfg.STOP_KMH) min(raw, 999.0) else 0.0
        speedDisp += (target - speedDisp) * (1.0 - exp(-dt / 0.7))
        if (abs(target - speedDisp) < 0.02) speedDisp = target

        // Rumb: només s'actualitza en moviment; parat es manté l'últim.
        val headingOk = fresh && GpsHub.hasBearing && raw >= Cfg.HEADING_MIN_KMH
        if (headingOk) {
            headingTarget = GpsHub.bearing
            if (!haveHeading) {
                haveHeading = true
                headingDisp = headingTarget
            }
        }
        headingDisp = Geo.norm360(
            headingDisp + Geo.shortestDiff(headingDisp, headingTarget) * (1.0 - exp(-dt / 0.35))
        )

        // Alçada suavitzada: l'agulla no ha de tremolar amb el soroll del GPS.
        val altTarget = GpsHub.correctedAltitude()
        if (!altTarget.isNaN()) {
            if (!haveAlt) {
                altDisp = altTarget
                haveAlt = true
            } else {
                altDisp += (altTarget - altDisp) * (1.0 - exp(-dt / 1.2))
            }
        }

        // Distància suavitzada: la roda gira sense salts entre lectures del GPS.
        val distTarget = GpsHub.trip.distanceM
        if (!haveDist || distTarget < distDisp - 1.0) {
            distDisp = distTarget
            haveDist = true
        } else {
            distDisp += (distTarget - distDisp) * (1.0 - exp(-dt / 0.4))
            if (abs(distTarget - distDisp) < 0.5) distDisp = distTarget
        }

        val now = GpsHub.nowMs()
        val tz = TimeZone.getDefault()
        val lay = DriveLayout(pw, ph)
        val dy = lay.dy

        // ---- Columna esquerra, sota "Conducció": velocitat, distància del viatge, estat del GPS
        val x0 = lay.speedX
        speedDrum.draw(c, x0, 14f, Drum.positions(speedDisp, 3).reversedArray())
        gray.draw(c, "km/h", x0 + DriveLayout.SPEED_W, 236f, 34f, Paint.Align.RIGHT)
        gray.draw(c, "Viatge (km)", x0, 298f + dy / 2f, 30f)
        tripDrum.draw(c, x0, 312f + dy / 2f, Drum.positions(distDisp / 100.0, 4).reversedArray())

        dot.color = GpsHub.statusColor()
        c.drawCircle(x0 + 12f, 470f + dy, 12f, dot)
        val gpsText = if (fresh) {
            "GPS ${GpsHub.satsUsed}/${GpsHub.satsVisible}"
        } else {
            GpsHub.statusLabel()
        }
        gray.draw(c, gpsText, x0 + 36f, 481f + dy, 34f, Paint.Align.LEFT, 330f)

        // ---- Tres esferes de la mateixa mida, centrades sota "Viatge" + "Ajustaments".
        // El rumb (en graus) i l'alçada (metres) són dins de cada esfera, en un quadre de rodes.
        val dimAlpha = if (headingOk) 255 else 120
        compass.draw(c, lay.c1, DriveLayout.DIAL_Y, DriveLayout.DIAL_R, headingDisp.toFloat(), dimAlpha)
        altimeter.draw(
            c, lay.c2, DriveLayout.DIAL_Y, DriveLayout.DIAL_R,
            if (haveAlt && !altTarget.isNaN()) altDisp else Double.NaN
        )
        clock.draw(c, lay.c3, DriveLayout.DIAL_Y, DriveLayout.DIAL_R, now, tz)

        // ---- Gràfic del viatge sota la brúixola i l'altímetre
        chart.draw(c, lay.chartL, DriveLayout.CHART_T, lay.chartR, lay.chartB, GpsHub.profile)

        // ---- Hora digital, dia i data sota el rellotge
        white.draw(c, Fmt.hm(now, tz), lay.c3, 372f + dy / 2f, 88f, Paint.Align.CENTER, 300f)
        cal.timeZone = tz
        cal.timeInMillis = now
        white.draw(c, Cat.weekday(cal), lay.c3, 424f + dy / 2f, 32f, Paint.Align.CENTER, 300f)
        whiteLight.draw(c, Cat.dayMonthYear(cal), lay.c3, 464f + dy / 2f, 32f, Paint.Align.CENTER, 300f)
    }
}

/** Pàgina 2: viatge, dades del GPS i hores del sol. */
class TripPage(ctx: Context) : BasePage(ctx, 1280f, 520f) {

    var onReset: (() -> Unit)? = null

    private val drum = DrumPainter(127f, 180f, 6f, true, true, true)
    private val gray = Txt(Pal.GRAY, false)
    private val resetRect = RectF()
    private var distDisp = 0.0
    private var haveDist = false

    override fun paintPage(c: Canvas) {
        val t = GpsHub.trip

        if (!haveDist || t.distanceM < distDisp - 1.0) {
            distDisp = t.distanceM
            haveDist = true
        } else {
            distDisp += (t.distanceM - distDisp) * (1.0 - exp(-dt / 0.4))
            if (abs(t.distanceM - distDisp) < 0.5) distDisp = t.distanceM
        }

        // El botó "Posa a zero" va a baix a la dreta; les files del cantó esquerre arriben fins a baix.
        resetRect.set(940f, ph - 80f, 1260f, ph - 10f)

        gray.draw(c, "Distància del viatge (km)", 40f, 44f, 36f)
        drum.draw(c, 40f, 62f, Drum.positions(distDisp / 100.0, 4).reversedArray())

        // Vuit files, repartides entre y = 278 i la vora de baix de la pàgina (ph - 12).
        val st = GpsHub.profile.altStats()
        fun alt0(v: Double?): String = if (v == null) "--" else Fmt.num(v, 0) + " m"
        val labels = arrayOf(
            "En marxa", "Aturat", "Temps total", "Velocitat mitjana", "Velocitat màxima",
            "Alçada màxima", "Alçada mínima", "Alçada mitjana"
        )
        val values = arrayOf(
            Fmt.hms(t.movingMs), Fmt.hms(t.stoppedMs), Fmt.hms(t.movingMs + t.stoppedMs),
            Fmt.num(t.avgKmh(), 0) + " km/h", Fmt.num(t.maxKmh, 0) + " km/h",
            alt0(st?.get(1)), alt0(st?.get(0)), alt0(st?.get(2))
        )
        val step = (ph - 12f - 278f) / (labels.size - 1)
        val vSize = min(36f, step * 0.95f)
        val lSize = vSize * 0.8f
        for (i in labels.indices) {
            row(c, labels[i], values[i], 278f + i * step, 40f, 600f, lSize, vSize)
        }

        val tz = TimeZone.getDefault()
        SunCache.refresh(GpsHub.nowMs(), tz, GpsHub.lat, GpsHub.lon)
        val alt = GpsHub.correctedAltitude()
        val raw = GpsHub.altitudeRaw
        val corr = Prefs.altOffset
        val rawText = if (raw.isNaN()) "--" else Fmt.num(raw, 0) + " m (corr. " + Fmt.signed(corr, 0) + ")"
        val x0 = 660f
        val x1 = 1260f
        // Nou files: de y = 52 a 20 unitats per sobre del botó (46 de separació a la pàgina base).
        val rs = (resetRect.top - 20f - 52f) / 8f
        row(c, "Estat", GpsHub.statusLabel(), 52f, x0, x1, 30f, 34f, GpsHub.statusColor())
        row(c, "Satèl·lits", "${GpsHub.satsUsed} usats de ${GpsHub.satsVisible}", 52f + rs, x0, x1, 30f, 34f)
        row(c, "Precisió", if (GpsHub.accuracy.isNaN()) "--" else "±" + Fmt.num(GpsHub.accuracy, 0) + " m", 52f + 2 * rs, x0, x1, 30f, 34f)
        row(c, "Latitud", Fmt.coord(GpsHub.lat, "N", "S"), 52f + 3 * rs, x0, x1, 30f, 34f)
        row(c, "Longitud", Fmt.coord(GpsHub.lon, "E", "O"), 52f + 4 * rs, x0, x1, 30f, 34f)
        row(c, "Alçada", if (alt.isNaN()) "--" else Fmt.num(alt, 0) + " m", 52f + 5 * rs, x0, x1, 30f, 34f)
        row(c, "Alçada GPS", rawText, 52f + 6 * rs, x0, x1, 30f, 34f)
        row(c, "Sortida del sol", SunCache.rise, 52f + 7 * rs, x0, x1, 30f, 34f)
        row(c, "Posta del sol", SunCache.set, 52f + 8 * rs, x0, x1, 30f, 34f)

        drawButton(c, resetRect, "Posa a zero", 36f)
    }

    override fun onTap(x: Float, y: Float) {
        if (resetRect.contains(x, y)) onReset?.invoke()
    }
}
