package cat.sergibonet.diagnosi

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/** Diu si la pantalla del tauler és ara mateix a la vista. */
object UiState {
    @Volatile
    var visible = false
}

/**
 * Torna al tauler després d'haver obert una altra app (ràdio, música...) amb un toc curt.
 * L'àudio de l'app continua sonant en segon pla. Android pot restringir que una app
 * en segon pla passi al davant, per això es proven dues vies i es guarda el resultat,
 * que es pot consultar a Ajustaments.
 */
object Returner {
    private val handler = Handler(Looper.getMainLooper())
    private var token = 0

    var lastResult = "Encara no s'ha provat"
        private set

    fun scheduleReturn(ctx: Context, delayMs: Long) {
        val app = ctx.applicationContext
        val id = ++token
        lastResult = "Esperant per tornar..."
        handler.postDelayed({ step(app, id, 0) }, delayMs)
    }

    private fun step(app: Context, id: Int, n: Int) {
        if (id != token) return
        if (UiState.visible) {
            lastResult = when (n) {
                0 -> "Ja era al tauler"
                1 -> "Tornat amb moveToFront"
                else -> "Tornat reobrint la pantalla"
            }
            return
        }
        when (n) {
            0 -> {
                // Via 1: portar la tasca del tauler al davant (el mateix que triar-la a la llista d'apps).
                try {
                    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.appTasks.firstOrNull()?.moveToFront()
                } catch (e: Exception) {
                    lastResult = "moveToFront ha fallat: ${e.javaClass.simpleName}"
                }
                handler.postDelayed({ step(app, id, 1) }, 700L)
            }
            1 -> {
                // Via 2: tornar a obrir la pantalla del tauler (reaprofita la que ja existeix).
                try {
                    app.startActivity(
                        Intent(app, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    )
                } catch (e: Exception) {
                    lastResult = "startActivity ha fallat: ${e.javaClass.simpleName}"
                }
                handler.postDelayed({ step(app, id, 2) }, 700L)
            }
            else -> {
                lastResult = "No s'ha pogut tornar: Android ho ha bloquejat"
                val hint = if (Settings.canDrawOverlays(app)) {
                    "No s'ha pogut tornar al tauler"
                } else {
                    "No s'ha pogut tornar. Activa «Mostrar sobre altres apps» a Ajustaments"
                }
                Toast.makeText(app, hint, Toast.LENGTH_LONG).show()
            }
        }
    }
}
