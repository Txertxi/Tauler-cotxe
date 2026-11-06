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
    /** Cinc rodes (0000,0): mateixa amplada total que abans amb quatre (≈302), rodes més petites i amb la mateixa proporció. */
    private val tripDrum = DrumPainter(53f, 74f, 4f, true, true, true)
    private val compass = CompassPainter()
    private val altimeter = AltimeterPainter()
    private val clock = ClockPainter()
    private val white = Txt(Pal.WHITE)
    private val whiteLight = Txt(Pal.WHITE, false)
    private val gray = Txt(Pal.GRAY, false)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cal = Calendar.getInstance()
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Pal.RING
    }

    /** Es crida (amb píxels: esquerra, dalt, dreta, baix) quan el rectangle del mapa canvia. */
    var onMapRect: ((Int, Int, Int, Int) -> Unit)? = null
    private var mapL = -1
    private var mapT = -1
    private var mapR = -1
    private var mapB = -1

    private var speedDisp = 0.0
    private var headingDisp = 0.0
    private var headingTarget = 0.0
    private var haveHeading = false
    private var altDisp = 0.0
    private var haveAlt = false
    private var distDisp = 0.0
    private var haveDist = false

    private companion object {
        /** Distància des de l'esquerra del velocímetre fins a l'esquerra del mapa: roda de 302 + 16 d'espai. */
        const val MAP_LEFT_FROM_X0 = 318f
    }

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
        tripDrum.draw(c, x0, 312f + dy / 2f, Drum.positions(distDisp / 100.0, 5).reversedArray())

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

        // ---- Mapa: entre el comptaquilòmetres del viatge i la dreta de l'altímetre (el pinta MapPanel).
        val ml = x0 + MAP_LEFT_FROM_X0
        val mr = lay.chartR
        val mt = DriveLayout.CHART_T
        val mb = lay.chartB
        c.drawRoundRect(ml - 3f, mt - 3f, mr + 3f, mb + 3f, 8f, 8f, frame)
        val l = pxX(ml).roundToInt()
        val t = pxY(mt).roundToInt()
        val r = pxX(mr).roundToInt()
        val b = pxY(mb).roundToInt()
        if (l != mapL || t != mapT || r != mapR || b != mapB) {
            mapL = l
            mapT = t
            mapR = r
            mapB = b
            onMapRect?.invoke(l, t, r, b)
        }

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
    var onProfile: (() -> Unit)? = null

    /** Cinc rodes (0000,0): amplada total ≈ 575 com abans amb quatre, amb la mateixa proporció. */
    private val drum = DrumPainter(102f, 145f, 6f, true, true, true)
    private val gray = Txt(Pal.GRAY, false)
    private val resetRect = RectF()
    private val profileRect = RectF()
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
        profileRect.set(PROFILE_BTN_L, ph - 80f, PROFILE_BTN_R, ph - 10f)

        gray.draw(c, "Distància del viatge (km)", 40f, 44f, 36f)
        drum.draw(c, 40f, 62f, Drum.positions(distDisp / 100.0, 5).reversedArray())

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

        drawButton(c, profileRect, "Perfil de viatge", 36f)
        drawButton(c, resetRect, "Posa a zero", 36f)
    }

    override fun onTap(x: Float, y: Float) {
        if (resetRect.contains(x, y)) onReset?.invoke()
        else if (profileRect.contains(x, y)) onProfile?.invoke()
    }

    companion object {
        /** Costat esquerre i dret del botó «Perfil de viatge» (a l'esquerra de «Posa a zero»). */
        const val PROFILE_BTN_L = 620f
        const val PROFILE_BTN_R = 920f
    }
}

/**
 * Pàgina 4 (no té pestanya): perfil del viatge de les darreres 3 hores, amb l'hora real cada 15 minuts,
 * i sota les dades del viatge. S'hi va des de Viatge amb «Perfil de viatge»; el botó «Tornar a Viatge»
 * és exactament al mateix lloc de la pantalla, així que no cal moure el dit.
 */
class ProfilePage(ctx: Context) : BasePage(ctx, 1280f, 520f) {

    var onBack: (() -> Unit)? = null

    private val chart = ProfileChartPainter()
    private val gray = Txt(Pal.GRAY, false)
    private val backRect = RectF()

    override fun paintPage(c: Canvas) {
        val t = GpsHub.trip
        val prof = GpsHub.profile
        val tz = TimeZone.getDefault()

        backRect.set(TripPage.PROFILE_BTN_L, ph - 80f, TripPage.PROFILE_BTN_R, ph - 10f)

        // Les files de text són 4 per columna, de 40 en 40, i acaben just a sobre dels botons de baix
        // a la dreta (y = ph - 92); el gràfic ocupa tot el que queda a dalt.
        val rowStep = 40f
        val lastRow = ph - 92f
        val firstRow = lastRow - 3f * rowStep
        chart.draw(c, 16f, 14f, 1264f, firstRow - 40f, prof, TripProfile.WINDOW_PROFILE_MS, true)

        val st = prof.altStats()
        fun alt0(v: Double?): String = if (v == null) "--" else Fmt.num(v, 0) + " m"
        val start = if (prof.startMs > 0L) Fmt.hm(prof.startMs, tz) else "--:--"
        val drop = if (st == null) null else st[1] - st[0]

        val labelsA = arrayOf("Hora d'inici", "Distància", "Velocitat màxima", "Velocitat mitjana")
        val valuesA = arrayOf(
            start, Fmt.num(t.distanceM / 1000.0, 1) + " km",
            Fmt.num(t.maxKmh, 0) + " km/h", Fmt.num(t.avgKmh(), 0) + " km/h"
        )
        val labelsB = arrayOf("Alçada màxima", "Alçada mínima", "Alçada mitjana", "Desnivell")
        val valuesB = arrayOf(alt0(st?.get(1)), alt0(st?.get(0)), alt0(st?.get(2)), alt0(drop))
        for (i in 0 until 4) {
            val y = firstRow + i * rowStep
            row(c, labelsA[i], valuesA[i], y, 40f, 600f, 29f, 36f)
            row(c, labelsB[i], valuesB[i], y, 660f, 1260f, 29f, 36f)
        }

        gray.draw(c, "Darreres 3 hores", 40f, ph - 28f, 26f)
        drawButton(c, backRect, "Tornar a Viatge", 36f)
    }

    override fun onTap(x: Float, y: Float) {
        if (backRect.contains(x, y)) onBack?.invoke()
    }
}
