package cat.sergibonet.diagnosi

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Lògica pura (sense Android): es pot provar a part, sense la unitat.
 */

/** Constants que decideixen quan es considera que el cotxe està aturat. */
object Cfg {
    /** Per sota d'aquesta velocitat (km/h) el tauler mostra 0. */
    const val STOP_KMH = 3.0

    /** Per sota d'aquesta velocitat el rumb del GPS no és fiable: es manté l'últim. */
    const val HEADING_MIN_KMH = 5.0

    /** Eix de velocitat dels gràfics: variable, de 0 al màxim de la finestra, amb graella cada 20 km/h. */
    const val CHART_KMH_STEP = 20.0

    /** Final mínim de l'eix de velocitat (km/h), perquè un viatge molt lent no quedi desproporcionat. */
    const val CHART_KMH_MIN_TOP = 60.0

    /**
     * Alçada (dp) que va guanyar la pàgina quan la barra de baix va passar de 104 a 64 dp.
     * L'escala del dibuix es manté com abans; l'espai guanyat és per al gràfic i les files de Viatge.
     */
    const val BAR_SAVED_DP = 40f
}

/**
 * Posicions de la pàgina Conducció, en unitats virtuals. `pw` és l'amplada virtual de la pàgina,
 * que ocupa tota l'amplada de la pantalla com les pestanyes de dalt (tres parts iguals).
 * `ph` és l'alçada virtual (mínim 520): tot el que sobra és per al gràfic.
 */
class DriveLayout(val pw: Float, ph: Float = 520f) {
    /** Alçada virtual guanyada respecte a la disposició base de 520. */
    val dy = max(0f, ph - 520f)

    /** Base del gràfic: 16 unitats per sobre del cantó de baix de la pàgina. */
    val chartB = 504f + dy

    /** Cantó esquerre del velocímetre: centrat sota la pestanya "Conducció" (centre = pw / 6). */
    val speedX = pw / 6f - SPEED_W / 2f

    /** Centre de les tres esferes: el de la parella "Viatge" + "Ajustaments" (centre = 2 pw / 3). */
    val c2 = pw * 2f / 3f
    val c1 = c2 - DIAL_GAP
    val c3 = c2 + DIAL_GAP

    /** Gràfic sota les dues primeres esferes, alineat amb les seves vores exteriors. */
    val chartL = c1 - DIAL_R
    val chartR = c2 + DIAL_R

    companion object {
        const val SPEED_CELL = 127f
        const val SPEED_GAP = 6f
        const val SPEED_W = 3f * SPEED_CELL + 2f * SPEED_GAP
        const val DIAL_R = 112f
        const val DIAL_GAP = 280f
        const val DIAL_Y = 128f
        const val CHART_T = 258f
    }
}

object SunTimes {
    /**
     * Sortida i posta de sol (ms d'epoch), o null si no n'hi ha (dia o nit polar).
     * `localNoonEpochMs` és el migdia local del dia que interessa.
     */
    fun compute(latDeg: Double, lonDeg: Double, localNoonEpochMs: Long): LongArray? {
        if (latDeg.isNaN() || lonDeg.isNaN()) return null
        val rad = Math.PI / 180.0
        val jDate = localNoonEpochMs / 86400000.0 + 2440587.5
        val n = floor(jDate - 2451545.0 + lonDeg / 360.0 + 0.5)
        val jStar = n - lonDeg / 360.0
        val m = (357.5291 + 0.98560028 * jStar).mod(360.0)
        val mr = m * rad
        val c = 1.9148 * sin(mr) + 0.02 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lam = (m + c + 180.0 + 102.9372).mod(360.0)
        val lr = lam * rad
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lr)
        val sinD = sin(lr) * sin(23.4397 * rad)
        val cosD = sqrt(1.0 - sinD * sinD)
        val cosW = (sin(-0.833 * rad) - sin(latDeg * rad) * sinD) / (cos(latDeg * rad) * cosD)
        if (cosW.isNaN() || cosW > 1.0 || cosW < -1.0) return null
        val w = Math.toDegrees(acos(cosW))
        return longArrayOf(toMs(jTransit - w / 360.0), toMs(jTransit + w / 360.0))
    }

    private fun toMs(j: Double): Long = ((j - 2440587.5) * 86400000.0).toLong()

    /** Migdia local (ms d'epoch) del dia que conté `timeMs` a la zona `tz`. */
    fun localNoon(timeMs: Long, tz: TimeZone): Long {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = timeMs
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

object Drum {
    /**
     * Posició contínua de cada roda de dígits (índex 0 = unitats), com en un
     * comptaquilòmetres mecànic: la roda de les unitats gira contínuament i les
     * altres només avancen durant l'última unitat abans del canvi (p. ex. 39,0 → 39,9 no mou les desenes).
     */
    fun positions(value: Double, wheels: Int): DoubleArray {
        val modulus = 10.0.pow(wheels)
        var v = value
        if (v.isNaN() || v < 0.0) v = 0.0
        v %= modulus
        val out = DoubleArray(wheels)
        for (k in 0 until wheels) {
            val p10 = 10.0.pow(k)
            val fl = floor(v / p10)
            // Com en un comptaquilòmetres mecànic: la roda k només avança mentre totes les
            // inferiors són a 9 i l'última de totes (les unitats) acaba la seva volta.
            out[k] = if (k == 0) v else fl + max(0.0, (v % p10) - (p10 - 1.0))
        }
        return out
    }
}

object Geo {
    fun norm360(a: Double): Double {
        var r = a % 360.0
        if (r < 0.0) r += 360.0
        return r
    }

    /** Diferència amb signe, pel camí més curt, de `from` a `to` (en graus, -180..180). */
    fun shortestDiff(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private val NAMES = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    fun cardinal(deg: Double): String = NAMES[((norm360(deg) + 22.5) / 45.0).toInt() % 8]
}

/** Dades del viatge: distància, temps en marxa i aturat, velocitat màxima. */
class TripCalc {
    var distanceM = 0.0
    var movingMs = 0L
    var stoppedMs = 0L
    var maxKmh = 0.0

    private var lastMs = -1L
    private var moving = false

    fun reset() {
        distanceM = 0.0
        movingMs = 0L
        stoppedMs = 0L
        maxKmh = 0.0
        lastMs = -1L
        moving = false
    }

    fun restore(distanceM: Double, movingMs: Long, stoppedMs: Long, maxKmh: Double) {
        this.distanceM = distanceM
        this.movingMs = movingMs
        this.stoppedMs = stoppedMs
        this.maxKmh = maxKmh
    }

    /** `elapsedMs`: instant de la lectura (rellotge monòton). */
    fun onFix(elapsedMs: Long, speedKmh: Double, accuracyM: Double) {
        if (accuracyM > MAX_ACCURACY_M) return
        val v = if (speedKmh.isNaN() || speedKmh < 0.0 || speedKmh > 350.0) 0.0 else speedKmh
        moving = if (moving) v >= EXIT_KMH else v >= ENTER_KMH
        if (moving && v > maxKmh) maxKmh = v
        val dt = if (lastMs < 0L) 0L else elapsedMs - lastMs
        lastMs = elapsedMs
        if (dt <= 0L || dt > MAX_GAP_MS) return
        if (moving) {
            distanceM += v / 3.6 * dt / 1000.0
            movingMs += dt
        } else {
            stoppedMs += dt
        }
    }

    fun avgKmh(): Double =
        if (movingMs < 1000L) 0.0 else distanceM / 1000.0 / (movingMs / 3600000.0)

    companion object {
        const val ENTER_KMH = 4.0
        const val EXIT_KMH = 2.5
        const val MAX_GAP_MS = 10000L
        const val MAX_ACCURACY_M = 50.0
    }
}

/**
 * Decideix quan ha de sonar l'avís de velocitat: tan bon punt es arriba al llindar
 * i després cada 10 segons mentre es mantingui. Per sota del llindar menys 3 km/h
 * es reinicia (així, voltant el llindar no sona a cada lectura).
 */
class AlertLogic {
    private var lastBeepMs = -1L

    /** `true` = cal fer sonar l'avís ara. `nowMs`: rellotge monòton (ms). */
    fun onFix(nowMs: Long, kmh: Double, enabled: Boolean, thresholdKmh: Double): Boolean {
        if (!enabled || kmh.isNaN()) {
            lastBeepMs = -1L
            return false
        }
        if (kmh < thresholdKmh - RESET_MARGIN_KMH) {
            lastBeepMs = -1L
            return false
        }
        if (kmh < thresholdKmh) return false
        if (lastBeepMs >= 0L && nowMs - lastBeepMs < INTERVAL_MS - TOLERANCE_MS) return false
        lastBeepMs = nowMs
        return true
    }

    companion object {
        const val INTERVAL_MS = 10000L
        const val TOLERANCE_MS = 400L
        const val RESET_MARGIN_KMH = 3.0
    }
}

/**
 * Perfil del viatge: velocitat i alçada a intervals regulars, amb l'hora real de cada mostra.
 * Només s'hi anota mentre el cotxe es mou (o fa menys de 2 minuts que s'ha aturat): una parada
 * llarga amb la unitat encesa no omple el gràfic de mostres.
 *
 * Es guarden les darreres [WINDOW_PROFILE_MIN] minuts (3 hores): quan s'omple, la mostra més
 * antiga surt per l'esquerra. Els gràfics trien la finestra que mostren (25 min a Conducció, 3 h al
 * Perfil V) i, com que cada mostra porta l'hora, una parada llarga es veu com un forat al traç.
 * Les estadístiques d'alçada (mínima, màxima, mitjana) s'acumulen a part, sobre TOT el viatge.
 */
class TripProfile {
    var n = 0
        private set

    /** Hora (ms d'epoch) de cada mostra. */
    val t = LongArray(CAP)
    val speed = FloatArray(CAP)

    /** Alçada corregida (m); NaN = sense lectura. */
    val alt = FloatArray(CAP)
    var dirty = false
        private set

    /** Hora (ms d'epoch) de la primera mostra des de l'últim «Posa a zero»; 0 si encara no n'hi ha. */
    var startMs = 0L
        private set

    // Alçades de tot el viatge (no de la finestra).
    private var altLo = Double.MAX_VALUE
    private var altHi = -Double.MAX_VALUE
    private var altSum = 0.0
    private var altCnt = 0

    private var nextMs = -1L
    private var lastMovingMs = -1L
    private var spdSm = 0.0
    private var altSm = Double.NaN

    fun clear() {
        n = 0
        startMs = 0L
        nextMs = -1L
        lastMovingMs = -1L
        spdSm = 0.0
        altSm = Double.NaN
        resetStats()
        dirty = true
    }

    private fun resetStats() {
        altLo = Double.MAX_VALUE
        altHi = -Double.MAX_VALUE
        altSum = 0.0
        altCnt = 0
    }

    private fun addStat(a: Double) {
        if (a < altLo) altLo = a
        if (a > altHi) altHi = a
        altSum += a
        altCnt++
    }

    /**
     * `elapsedMs`: rellotge monòton (ms), per al ritme de les mostres; `wallMs`: hora real (ms d'epoch);
     * `altM`: alçada corregida (NaN si no n'hi ha).
     */
    fun onFix(elapsedMs: Long, wallMs: Long, kmh: Double, altM: Double, accuracyM: Double) {
        if (accuracyM > TripCalc.MAX_ACCURACY_M) return
        var v = if (kmh.isNaN() || kmh < 0.0 || kmh > 350.0) 0.0 else kmh
        if (v < Cfg.STOP_KMH) v = 0.0
        spdSm += (v - spdSm) * 0.4
        if (!altM.isNaN()) altSm = if (altSm.isNaN()) altM else altSm + (altM - altSm) * 0.3
        if (v >= TripCalc.ENTER_KMH) lastMovingMs = elapsedMs
        val active = lastMovingMs >= 0L && elapsedMs - lastMovingMs <= IDLE_MS
        if (!active) {
            nextMs = -1L
            return
        }
        if (nextMs >= 0L && elapsedMs < nextMs) return
        if (n == CAP) slide()
        nextMs = elapsedMs + STEP_MS
        // Les hores han de ser estrictament creixents (l'hora GPS es corregeix a poc a poc).
        val w = if (n > 0 && wallMs <= t[n - 1]) t[n - 1] + 1L else wallMs
        if (n == 0) startMs = w
        t[n] = w
        speed[n] = spdSm.toFloat()
        alt[n] = if (altSm.isNaN()) Float.NaN else altSm.toFloat()
        if (!altSm.isNaN()) addStat(alt[n].toDouble())
        n++
        dirty = true
    }

    /** Finestra mòbil: treu la mostra més antiga i deixa lloc a una de nova al final. */
    private fun slide() {
        System.arraycopy(t, 1, t, 0, CAP - 1)
        System.arraycopy(speed, 1, speed, 0, CAP - 1)
        System.arraycopy(alt, 1, alt, 0, CAP - 1)
        n = CAP - 1
    }

    /**
     * Límits [esquerra, dreta] (ms d'epoch) de la finestra de `windowMs` ms que ha de mostrar un gràfic.
     * Mentre el viatge és més curt que la finestra, comença a l'hora d'inici i el traç neix a l'esquerra;
     * després la finestra acompanya l'última mostra. Null si no hi ha cap mostra.
     */
    fun windowBounds(windowMs: Long): LongArray? {
        if (n == 0) return null
        val end = t[n - 1]
        val left = if (startMs > 0L && end - startMs < windowMs) startMs else end - windowMs
        return longArrayOf(left, left + windowMs)
    }

    /**
     * Eix de velocitat [final, pas] per a la finestra `wb`: de 0 al màxim d'aquella finestra, arrodonit
     * amunt a un múltiple del pas (20 km/h; 40 si passa de 160) i amb un final mínim de 60 km/h.
     */
    fun speedAxis(wb: LongArray?): DoubleArray {
        var mx = 0.0
        if (wb != null) {
            for (i in 0 until n) {
                if (t[i] < wb[0] || t[i] > wb[1]) continue
                if (speed[i] > mx) mx = speed[i].toDouble()
            }
        }
        var top = ceil(mx / Cfg.CHART_KMH_STEP) * Cfg.CHART_KMH_STEP
        if (top < Cfg.CHART_KMH_MIN_TOP) top = Cfg.CHART_KMH_MIN_TOP
        var step = Cfg.CHART_KMH_STEP
        if (top > 160.0) {
            step = Cfg.CHART_KMH_STEP * 2.0
            top = ceil(top / step) * step
        }
        return doubleArrayOf(top, step)
    }

    /**
     * Eix d'alçades per a la finestra `wb`: del mínim al màxim d'aquella finestra, arrodonits a 10 m i
     * amb un marge mínim de 50 m perquè el soroll del GPS no es vegi com una muntanya. Null si no hi ha dades.
     */
    fun altAxis(wb: LongArray?): DoubleArray? {
        if (wb == null) return null
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until n) {
            if (t[i] < wb[0] || t[i] > wb[1]) continue
            val a = alt[i]
            if (a.isNaN()) continue
            if (a.toDouble() < lo) lo = a.toDouble()
            if (a.toDouble() > hi) hi = a.toDouble()
        }
        if (lo > hi) return null
        if (hi - lo < MIN_SPAN_M) {
            val mid = (lo + hi) / 2.0
            lo = mid - MIN_SPAN_M / 2.0
            hi = mid + MIN_SPAN_M / 2.0
        }
        return doubleArrayOf(floor(lo / 10.0) * 10.0, ceil(hi / 10.0) * 10.0)
    }

    /**
     * [mínim, màxim, mitjana] de les alçades de TOT el viatge, o null si no n'hi ha. Són valors
     * suavitzats (el soroll del GPS s'ha filtrat), així que un cim molt puntual pot quedar
     * uns metres per sota del real.
     */
    fun altStats(): DoubleArray? {
        if (altCnt == 0) return null
        return doubleArrayOf(altLo, altHi, altSum / altCnt)
    }

    /** Desa el perfil. S'escriu a un fitxer temporal i es canvia de nom: un tall de corrent no el corromp. */
    fun save(f: File) {
        try {
            val tmp = File(f.path + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
                o.writeInt(VERSION)
                o.writeLong(startMs)
                o.writeInt(n)
                o.writeDouble(altLo)
                o.writeDouble(altHi)
                o.writeDouble(altSum)
                o.writeInt(altCnt)
                for (i in 0 until n) {
                    o.writeLong(t[i])
                    o.writeFloat(speed[i])
                    o.writeFloat(alt[i])
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            dirty = false
        } catch (e: IOException) {
            // si no es pot desar, el gràfic continua a la memòria
        }
    }

    /** Llegeix el perfil. Els fitxers d'altres versions (sense hores) s'ignoren: el perfil comença de zero. */
    fun load(f: File) {
        if (!f.exists()) return
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
                if (i.readInt() != VERSION) return
                val st = i.readLong()
                val cnt = i.readInt()
                val lo = i.readDouble()
                val hi = i.readDouble()
                val sum = i.readDouble()
                val sc = i.readInt()
                if (cnt < 0 || cnt > CAP || sc < 0) return
                for (k in 0 until cnt) {
                    t[k] = i.readLong()
                    speed[k] = i.readFloat()
                    alt[k] = i.readFloat()
                }
                n = cnt
                startMs = st
                altLo = lo
                altHi = hi
                altSum = sum
                altCnt = sc
            }
            nextMs = -1L
            lastMovingMs = -1L
            dirty = false
        } catch (e: IOException) {
            n = 0
            startMs = 0L
            resetStats()
        }
    }

    companion object {
        /** Minuts que mostra el gràfic de Conducció. */
        const val WINDOW_DRIVE_MIN = 25

        /** Minuts que mostra el Perfil V (i que es guarden). */
        const val WINDOW_PROFILE_MIN = 180
        const val WINDOW_DRIVE_MS = WINDOW_DRIVE_MIN * 60000L
        const val WINDOW_PROFILE_MS = WINDOW_PROFILE_MIN * 60000L

        /** Una mostra cada 10 s. */
        const val STEP_MS = 10000L
        const val CAP = (WINDOW_PROFILE_MS / STEP_MS).toInt()

        /** Si entre dues mostres passa més d'això, el traç es talla (el cotxe s'ha aturat de debò). */
        const val GAP_MS = 35000L
        const val IDLE_MS = 120000L
        const val MIN_SPAN_M = 50.0
        private const val VERSION = 3
    }
}

object Fmt {
    /** Durada en forma curta: "45 min" o "1 h 25 min". */
    fun dur(ms: Long): String {
        val m = max(0L, ms) / 60000L
        return if (m >= 60L) "${m / 60L} h ${m % 60L} min" else "$m min"
    }

    fun hm(epochMs: Long, tz: TimeZone): String {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = epochMs
        return String.format(
            Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    fun hms(ms: Long): String {
        val s = max(0L, ms) / 1000L
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600L, (s / 60L) % 60L, s % 60L)
    }

    /** Latitud o longitud amb l'hemisferi, p. ex. "41.15322° N". */
    fun coord(v: Double, pos: String, neg: String): String {
        if (v.isNaN()) return "--"
        return String.format(Locale.US, "%.5f° %s", abs(v), if (v >= 0.0) pos else neg)
    }

    fun num(v: Double, decimals: Int): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%.${decimals}f", v)

    fun signed(v: Double, decimals: Int): String =
        String.format(Locale.US, "%+.${decimals}f", v)
}

object Cat {
    private val DAYS = arrayOf(
        "diumenge", "dilluns", "dimarts", "dimecres", "dijous", "divendres", "dissabte"
    )
    private val MONTHS = arrayOf(
        "de gener", "de febrer", "de març", "d'abril", "de maig", "de juny",
        "de juliol", "d'agost", "de setembre", "d'octubre", "de novembre", "de desembre"
    )

    fun weekday(cal: Calendar): String = DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]

    fun dayMonthYear(cal: Calendar): String =
        "${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS[cal.get(Calendar.MONTH)]} de ${cal.get(Calendar.YEAR)}"
}
