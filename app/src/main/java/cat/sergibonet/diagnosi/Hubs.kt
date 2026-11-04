package cat.sergibonet.diagnosi

import android.content.Context
import android.location.Location
import android.os.SystemClock
import java.io.File
import java.util.TimeZone

/**
 * Estat compartit del GPS. El servei l'actualitza i les pantalles el llegeixen.
 * Tot passa pel fil principal, per això no cal sincronitzar res.
 */
object GpsHub {
    var lat = Double.NaN
    var lon = Double.NaN
    var accuracy = Double.NaN
    var speedKmh = 0.0
    var bearing = 0.0
    var hasBearing = false
    var altitudeRaw = Double.NaN
    var satsUsed = 0
    var satsVisible = 0
    var lastFixElapsedMs = 0L
    var permissionOk = false
    var serviceRunning = false
    val trip = TripCalc()
    val profile = TripProfile()
    private var profileFile: File? = null

    private var clockOffsetMs = 0L
    private var haveClock = false
    private var restored = false
    private var lastSaveElapsed = 0L

    /** Indica on es desa el perfil del viatge (una sola vegada per procés). */
    fun initStorage(ctx: Context) {
        if (profileFile == null) profileFile = File(ctx.applicationContext.filesDir, "perfil_viatge.bin")
    }

    /** Carrega el viatge i l'última posició guardats (una sola vegada per procés). */
    fun restore() {
        if (restored) return
        restored = true
        Prefs.loadTrip(trip)
        profileFile?.let { profile.load(it) }
        val pos = Prefs.lastPos()
        if (pos != null) {
            lat = pos[0]
            lon = pos[1]
        }
    }

    fun fixAgeMs(): Long =
        if (lastFixElapsedMs == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastFixElapsedMs

    fun hasFreshFix(): Boolean = fixAgeMs() < 5000L

    fun hasPosition(): Boolean = !lat.isNaN() && !lon.isNaN()

    /** Hora actual: la del sistema, corregida amb l'hora GPS quan n'hi ha hagut. */
    fun nowMs(): Long = System.currentTimeMillis() + clockOffsetMs

    fun correctedAltitude(): Double =
        if (altitudeRaw.isNaN()) Double.NaN else altitudeRaw + Prefs.altOffset

    fun onLocation(l: Location) {
        lat = l.latitude
        lon = l.longitude
        accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else Double.NaN
        speedKmh = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        hasBearing = l.hasBearing()
        if (hasBearing) bearing = l.bearing.toDouble()
        altitudeRaw = if (l.hasAltitude()) l.altitude else Double.NaN
        lastFixElapsedMs = SystemClock.elapsedRealtime()

        val accOk = if (accuracy.isNaN()) 0.0 else accuracy
        trip.onFix(l.elapsedRealtimeNanos / 1_000_000L, speedKmh, accOk)
        profile.onFix(l.elapsedRealtimeNanos / 1_000_000L, speedKmh, correctedAltitude(), accOk)

        // Avís sonor de velocitat (si està activat als ajustaments).
        SpeedAlert.onFix(lastFixElapsedMs, speedKmh)

        // Hora GPS: es guarda la diferència amb el rellotge del sistema.
        val off = l.time - System.currentTimeMillis()
        if (!haveClock) {
            clockOffsetMs = off
            haveClock = true
        } else {
            clockOffsetMs = (clockOffsetMs * 0.8 + off * 0.2).toLong()
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveElapsed > 15000L) {
            lastSaveElapsed = now
            persist()
        }
    }

    fun onSatellites(used: Int, visible: Int) {
        satsUsed = used
        satsVisible = visible
    }

    fun resetTrip() {
        trip.reset()
        profile.clear()
        profileFile?.let { profile.save(it) }
        Prefs.saveTrip(trip)
    }

    fun persist() {
        Prefs.saveTrip(trip)
        if (profile.dirty) profileFile?.let { profile.save(it) }
        if (hasPosition()) Prefs.saveLastPos(lat, lon)
    }

    fun statusLabel(): String = when {
        !permissionOk -> "Sense permís d'ubicació"
        hasFreshFix() -> "Posició fixada"
        lastFixElapsedMs == 0L -> "Buscant satèl·lits..."
        else -> "Senyal perdut"
    }

    fun statusColor(): Int = when {
        !permissionOk -> Pal.RED
        hasFreshFix() -> Pal.GREEN
        else -> Pal.AMBER
    }
}

/** Hores de sortida i posta del sol, recalculades un cop per minut. */
object SunCache {
    var rise = "--:--"
        private set
    var set = "--:--"
        private set
    private var key = -1L

    fun refresh(nowMs: Long, tz: TimeZone, lat: Double, lon: Double) {
        val hasPos = !lat.isNaN() && !lon.isNaN()
        val k = (nowMs / 60000L) * 2L + (if (hasPos) 1L else 0L)
        if (k == key) return
        key = k
        val r = SunTimes.compute(lat, lon, SunTimes.localNoon(nowMs, tz))
        rise = if (r != null) Fmt.hm(r[0], tz) else "--:--"
        set = if (r != null) Fmt.hm(r[1], tz) else "--:--"
    }
}
