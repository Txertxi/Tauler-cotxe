package cat.sergibonet.diagnosi

import android.content.Context
import android.content.SharedPreferences

/** Ajustaments i dades que s'han de recordar entre sessions. */
object Prefs {
    const val SLOTS = 6
    val SLOT_NAMES = arrayOf("Telèfon", "Ràdio", "Música local", "Navegador", "Música mòbil", "Android Auto")

    /** Botons que amb un toc curt obren l'app i tornen enseguida al tauler (l'àudio continua). */
    val RETURN_SLOTS = setOf(1, 2, 4)

    private var sp: SharedPreferences? = null

    private fun p(): SharedPreferences =
        sp ?: throw IllegalStateException("Prefs.init no s'ha cridat")

    fun init(ctx: Context) {
        if (sp == null) {
            sp = ctx.applicationContext.getSharedPreferences("tauler", Context.MODE_PRIVATE)
        }
    }

    private fun getD(key: String, def: Double): Double = p().getString(key, null)?.toDoubleOrNull() ?: def

    private fun putD(key: String, v: Double) {
        p().edit().putString(key, v.toString()).apply()
    }

    // ---------------------------------------------------------------- alçada
    var altOffset: Double
        get() = getD("alt_offset", 0.0)
        set(v) = putD("alt_offset", v)

    // ---------------------------------------------------------------- mapa
    var mapZoom: Int
        get() = p().getInt("map_zoom", 14)
        set(v) = p().edit().putInt("map_zoom", v).apply()

    /** Mapa atenuat (per a la nit). */
    var mapDim: Boolean
        get() = p().getBoolean("map_dim", false)
        set(v) = p().edit().putBoolean("map_dim", v).apply()

    // ---------------------------------------------------------------- botons d'apps
    var defaultsDone: Boolean
        get() = p().getBoolean("defaults_done", false)
        set(v) {
            p().edit().putBoolean("defaults_done", v).apply()
        }

    fun slotPkg(i: Int): String = p().getString("slot${i}_pkg", "") ?: ""

    fun slotCls(i: Int): String = p().getString("slot${i}_cls", "") ?: ""

    fun setSlot(i: Int, pkg: String, cls: String) {
        p().edit().putString("slot${i}_pkg", pkg).putString("slot${i}_cls", cls).apply()
    }

    /** Retard (ms) abans de tornar al tauler després d'obrir una app amb toc curt. */
    var returnDelayMs: Long
        get() = p().getLong("return_delay", 1500L).coerceIn(300L, 6000L)
        set(v) {
            p().edit().putLong("return_delay", v.coerceIn(300L, 6000L)).apply()
        }

    // ---------------------------------------------------------------- avís de velocitat
    var alertOn: Boolean
        get() = p().getBoolean("alert_on", false)
        set(v) {
            p().edit().putBoolean("alert_on", v).apply()
        }

    var alertKmh: Double
        get() = getD("alert_kmh", 130.0)
        set(v) = putD("alert_kmh", v)

    /** Canal d'àudio de l'avís: 0 multimèdia, 1 alarma, 2 navegació (per defecte). */
    var alertChannel: Int
        get() = p().getInt("alert_channel", 2).coerceIn(0, 2)
        set(v) {
            p().edit().putInt("alert_channel", v.coerceIn(0, 2)).apply()
        }

    // ---------------------------------------------------------------- última posició (per a la sortida del sol)
    fun lastPos(): DoubleArray? {
        val la = getD("last_lat", Double.NaN)
        val lo = getD("last_lon", Double.NaN)
        return if (la.isNaN() || lo.isNaN()) null else doubleArrayOf(la, lo)
    }

    fun saveLastPos(lat: Double, lon: Double) {
        putD("last_lat", lat)
        putD("last_lon", lon)
    }

    // ---------------------------------------------------------------- viatge
    fun loadTrip(t: TripCalc) {
        t.restore(
            getD("trip_dist", 0.0),
            p().getLong("trip_moving", 0L),
            p().getLong("trip_stopped", 0L),
            getD("trip_max", 0.0)
        )
    }

    fun saveTrip(t: TripCalc) {
        p().edit()
            .putString("trip_dist", t.distanceM.toString())
            .putLong("trip_moving", t.movingMs)
            .putLong("trip_stopped", t.stoppedMs)
            .putString("trip_max", t.maxKmh.toString())
            .apply()
    }
}
