package cat.sergibonet.diagnosi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Tauler del cotxe: tres pàgines (Conducció, Viatge, Ajustaments)
 * i una barra inferior amb botons grans per obrir altres apps.
 */
class MainActivity : Activity() {

    private class AppEntry(val label: String, val pkg: String, val cls: String)

    private val tabNames = arrayOf("Conducció", "Viatge", "Ajustaments")
    private val tabButtons = ArrayList<Button>()
    private lateinit var pages: Array<View>

    /** Perfil V: pàgina extra que penja de Viatge (no té pestanya pròpia). */
    private lateinit var profilePage: ProfilePage
    private lateinit var mapPanel: MapPanel
    private lateinit var mapInfo: TextView
    private lateinit var mapDimBtn: Button
    private var current = 0

    // Peces de la pantalla d'ajustaments que s'han d'actualitzar.
    private lateinit var altInfo: TextView
    private lateinit var gpsInfo: TextView
    private lateinit var alertInfo: TextView
    private lateinit var alertBtn: Button
    private lateinit var alertChBtn: Button
    private lateinit var delayInfo: TextView
    private lateinit var overlayInfo: TextView
    private lateinit var returnInfo: TextView
    private val slotRowButtons = ArrayList<Button>()

    // ------------------------------------------------------------------ cicle de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        SpeedAlert.init(this)
        GpsHub.initStorage(this)
        GpsHub.restore()
        applyDefaults()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        selectTab(0)
        ensureGps()
    }

    override fun onResume() {
        super.onResume()
        UiState.visible = true
        mapPanel.refresh()
        GpsHub.permissionOk = hasLocationPermission()
        if (GpsHub.permissionOk && !GpsHub.serviceRunning) startGpsService()
        if (current == TAB_SETTINGS) refreshSettings()
    }

    override fun onDestroy() {
        mapPanel.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        UiState.visible = false
        GpsHub.persist()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        GpsHub.permissionOk = hasLocationPermission()
        if (GpsHub.permissionOk) startGpsService()
        if (current == TAB_SETTINGS) refreshSettings()
    }

    // ------------------------------------------------------------------ GPS i permisos

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureGps() {
        GpsHub.permissionOk = hasLocationPermission()
        if (GpsHub.permissionOk) {
            startGpsService()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
        }
    }

    private fun startGpsService() {
        try {
            startForegroundService(Intent(this, GpsService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "No s'ha pogut engegar el GPS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ construcció de la pantalla

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundBg(normal: Int, pressed: Int): StateListDrawable {
        val radius = dp(14f).toFloat()
        val n = GradientDrawable()
        n.setColor(normal)
        n.cornerRadius = radius
        val p = GradientDrawable()
        p.setColor(pressed)
        p.cornerRadius = radius
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), p)
        s.addState(intArrayOf(), n)
        return s
    }

    private fun makeButton(text: String, sp: Float, normal: Int, pressed: Int, textColor: Int): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        b.setTextColor(textColor)
        b.background = roundBg(normal, pressed)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.maxLines = 2
        b.setPadding(dp(4f), 0, dp(4f), 0)
        return b
    }

    private fun weightParams(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        lp.setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
        return lp
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        // Pestanyes de dalt
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        for (i in tabNames.indices) {
            val b = makeButton(tabNames[i], 24f, Pal.BUTTON, Pal.BUTTON_PRESSED, Color.WHITE)
            b.setOnClickListener { selectTab(i) }
            tabs.addView(b, weightParams())
            tabButtons.add(b)
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f)))

        // Pàgines
        val drive = DrivePage(this)
        val trip = TripPage(this)
        trip.onReset = { confirmResetTrip() }
        trip.onProfile = { showProfile() }
        profilePage = ProfilePage(this)
        profilePage.onBack = { selectTab(TAB_TRIP) }
        pages = arrayOf(drive, trip, buildSettings())
        mapPanel = MapPanel(this)
        mapPanel.onNeedPermission = { openAllFilesSettings() }
        drive.onMapRect = { l, t, r, b ->
            val lp = FrameLayout.LayoutParams(r - l, b - t)
            lp.leftMargin = l
            lp.topMargin = t
            lp.gravity = Gravity.TOP or Gravity.START
            mapPanel.post { mapPanel.layoutParams = lp }
        }

        val content = FrameLayout(this)
        for (p in pages) {
            content.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        content.addView(
            profilePage,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // El mapa va damunt de la pàgina Conducció, al rectangle que ella li indica.
        content.addView(mapPanel, FrameLayout.LayoutParams(1, 1))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Barra de botons d'apps
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        for (i in 0 until Prefs.SLOTS) {
            val b = makeButton(Prefs.SLOT_NAMES[i], 24f, Pal.BUTTON, Pal.BUTTON_PRESSED, Color.WHITE)
            b.setOnClickListener { onSlotTap(i) }
            b.setOnLongClickListener {
                onSlotLong(i)
                true
            }
            bar.addView(b, weightParams())
        }
        val home = makeButton("Inici", 24f, 0xFF1D4E89.toInt(), 0xFF2F6DB8.toInt(), Color.WHITE)
        home.setOnClickListener { goHome() }
        bar.addView(home, weightParams())
        // Mateixa alçada que les pestanyes de dalt (64 dp).
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f)))

        return root
    }

    /** Mostra el Perfil V; la pestanya «Viatge» continua marcada. */
    private fun showProfile() {
        pages[TAB_TRIP].visibility = View.GONE
        profilePage.visibility = View.VISIBLE
    }

    private fun selectTab(i: Int) {
        current = i
        profilePage.visibility = View.GONE
        mapPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        for (j in pages.indices) {
            pages[j].visibility = if (j == i) View.VISIBLE else View.GONE
        }
        for (j in tabButtons.indices) {
            val sel = j == i
            tabButtons[j].background = if (sel) roundBg(Pal.AMBER, Pal.AMBER) else roundBg(Pal.BUTTON, Pal.BUTTON_PRESSED)
            tabButtons[j].setTextColor(if (sel) Color.BLACK else Color.WHITE)
        }
        if (i == TAB_SETTINGS) refreshSettings()
    }

    // ------------------------------------------------------------------ botons d'apps

    private fun applyDefaults() {
        if (Prefs.defaultsDone) return
        val defaults = mapOf(
            3 to "com.google.android.apps.maps",
            4 to "com.spotify.music",
            5 to "com.google.android.projection.gearhead"
        )
        for ((slot, pkg) in defaults) {
            val cn = packageManager.getLaunchIntentForPackage(pkg)?.component
            if (cn != null) Prefs.setSlot(slot, cn.packageName, cn.className)
        }
        Prefs.defaultsDone = true
    }

    private fun isUnassigned(i: Int): Boolean = Prefs.slotPkg(i).isEmpty() || Prefs.slotCls(i).isEmpty()

    /** Toc curt: a Ràdio i músiques obre l'app i torna al tauler; als altres, obre l'app i s'hi queda. */
    private fun onSlotTap(i: Int) {
        if (isUnassigned(i)) {
            Toast.makeText(this, "«${Prefs.SLOT_NAMES[i]}»: tria quina app s'ha d'obrir", Toast.LENGTH_LONG).show()
            chooseApp(i)
            return
        }
        launchSlot(i, i in Prefs.RETURN_SLOTS)
    }

    /** Toc llarg: a Ràdio i músiques obre l'app i s'hi queda; als altres, canvia l'app assignada. */
    private fun onSlotLong(i: Int) {
        if (i in Prefs.RETURN_SLOTS && !isUnassigned(i)) {
            launchSlot(i, false)
        } else {
            chooseApp(i)
        }
    }

    private fun launchSlot(i: Int, backToDashboard: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(Prefs.slotPkg(i), Prefs.slotCls(i))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            if (backToDashboard) Returner.scheduleReturn(this, Prefs.returnDelayMs)
        } catch (e: Exception) {
            Toast.makeText(this, "No s'ha pogut obrir «${Prefs.SLOT_NAMES[i]}»: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun goHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No s'ha pogut tornar a l'inici: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun listApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = ArrayList<AppEntry>()
        for (r in pm.queryIntentActivities(intent, 0)) {
            if (r.activityInfo.packageName == packageName) continue
            out.add(AppEntry(r.loadLabel(pm).toString(), r.activityInfo.packageName, r.activityInfo.name))
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    private fun appLabel(pkg: String, cls: String): String {
        if (pkg.isEmpty() || cls.isEmpty()) return "Sense assignar (toca per triar)"
        return try {
            val pm = packageManager
            pm.getActivityInfo(ComponentName(pkg, cls), 0).loadLabel(pm).toString()
        } catch (e: Exception) {
            "$pkg (no instal·lada?)"
        }
    }

    private fun chooseApp(slot: Int) {
        val apps = listApps()
        val labels = ArrayList<String>()
        labels.add("(Cap: esborra l'assignació)")
        for (a in apps) labels.add(a.label)
        val lv = ListView(this)
        lv.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                v.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                return v
            }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("App per a «${Prefs.SLOT_NAMES[slot]}»")
            .setView(lv)
            .setNegativeButton("Cancel·la", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            if (pos == 0) {
                Prefs.setSlot(slot, "", "")
            } else {
                val a = apps[pos - 1]
                Prefs.setSlot(slot, a.pkg, a.cls)
            }
            dlg.dismiss()
            refreshSettings()
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ viatge

    private fun confirmResetTrip() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Posar el viatge a zero?")
            .setMessage("S'esborren la distància, els temps i la velocitat màxima.")
            .setPositiveButton("Posa a zero") { _, _ -> GpsHub.resetTrip() }
            .setNegativeButton("Cancel·la", null)
            .create()
        dlg.show()
        dlg.findViewById<TextView>(android.R.id.message)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }

    // ------------------------------------------------------------------ teclat numèric propi

    /** Teclat gran per escriure un número enter (sense dependre del teclat del sistema). */
    private fun askNumber(title: String, unit: String, allowNegative: Boolean, onOk: (Int) -> Unit) {
        val sb = StringBuilder()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))

        val display = label("0 $unit", 40f, Color.WHITE)
        display.gravity = Gravity.CENTER
        display.typeface = Typeface.DEFAULT_BOLD
        display.setPadding(0, dp(8f), 0, dp(12f))
        col.addView(display)

        fun refresh() {
            display.text = (if (sb.isEmpty()) "0" else sb.toString()) + " " + unit
        }

        fun key(text: String, action: () -> Unit): Button {
            val b = makeButton(text, 30f, Pal.BUTTON, Pal.BUTTON_PRESSED, Color.WHITE)
            b.setOnClickListener {
                action()
                refresh()
            }
            return b
        }

        val rows = arrayOf(
            arrayOf("7", "8", "9"),
            arrayOf("4", "5", "6"),
            arrayOf("1", "2", "3"),
            arrayOf("±", "0", "⌫")
        )
        for (r in rows) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            for (k in r) {
                val b = key(k) {
                    when (k) {
                        "⌫" -> {
                            if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                            if (sb.toString() == "-") sb.setLength(0)
                        }
                        "±" -> {
                            if (allowNegative) {
                                if (sb.startsWith("-")) sb.deleteCharAt(0) else sb.insert(0, "-")
                            }
                        }
                        else -> {
                            val digits = sb.count { it.isDigit() }
                            if (digits < 5) sb.append(k)
                        }
                    }
                }
                val lp = LinearLayout.LayoutParams(0, dp(64f), 1f)
                lp.setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
                line.addView(b, lp)
            }
            col.addView(line)
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(col)
            .setPositiveButton("D'acord") { _, _ ->
                onOk(sb.toString().toIntOrNull() ?: 0)
                refreshSettings()
            }
            .setNegativeButton("Cancel·la", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
    }

    // ------------------------------------------------------------------ pàgina d'ajustaments

    private fun label(text: String, sp: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        return t
    }

    private fun header(col: LinearLayout, text: String) {
        val t = label(text, 30f, Pal.AMBER)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(22f), 0, dp(6f))
        col.addView(t)
    }

    private fun hint(col: LinearLayout, text: String) {
        val t = label(text, 20f, Pal.GRAY)
        t.setPadding(0, dp(4f), 0, dp(4f))
        col.addView(t)
    }

    private fun bigButton(text: String, onClick: () -> Unit): Button {
        val b = makeButton(text, 24f, Pal.BUTTON, Pal.BUTTON_PRESSED, Color.WHITE)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addButton(col: LinearLayout, b: Button) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f))
        lp.setMargins(0, dp(4f), 0, dp(4f))
        col.addView(b, lp)
    }

    /** Fila de botons petits (p. ex. «−1» i «+1»), repartits a parts iguals. */
    private fun addButtonRow(col: LinearLayout, vararg buttons: Button) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (b in buttons) {
            val lp = LinearLayout.LayoutParams(0, dp(64f), 1f)
            lp.setMargins(dp(3f), dp(4f), dp(3f), dp(4f))
            row.addView(b, lp)
        }
        col.addView(row)
    }

    private fun buildSettings(): ScrollView {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24f), dp(8f), dp(24f), dp(32f))
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- Altímetre
        header(col, "Correcció de l'altímetre")
        altInfo = label("", 26f, Color.WHITE)
        col.addView(altInfo)
        addButtonRow(
            col,
            bigButton("−1 m") {
                Prefs.altOffset = Prefs.altOffset - 1
                refreshSettings()
            },
            bigButton("+1 m") {
                Prefs.altOffset = Prefs.altOffset + 1
                refreshSettings()
            }
        )
        addButton(col, bigButton("Escriu la correcció (p. ex. −48 m)") {
            askNumber("Correcció de l'alçada", "m", true) { Prefs.altOffset = it.toDouble() }
        })
        addButton(col, bigButton("Escriu l'alçada real d'on ets") {
            val raw = GpsHub.altitudeRaw
            if (raw.isNaN()) {
                Toast.makeText(this, "Encara no hi ha alçada del GPS", Toast.LENGTH_LONG).show()
            } else {
                askNumber("Alçada real d'aquí", "m", true) { Prefs.altOffset = it - raw }
            }
        })
        addButton(col, bigButton("Treu la correcció (0 m)") {
            Prefs.altOffset = 0.0
            refreshSettings()
        })
        hint(col, "L'alçada del GPS és aproximada. Pots escriure directament la correcció o dir l'alçada real d'on ets i l'app calcula la correcció. Es desa sola.")

        // --- Avís de velocitat
        header(col, "Avís de velocitat")
        alertInfo = label("", 26f, Color.WHITE)
        col.addView(alertInfo)
        alertBtn = bigButton("") {
            Prefs.alertOn = !Prefs.alertOn
            refreshSettings()
        }
        addButton(col, alertBtn)
        addButton(col, bigButton("Canvia el llindar (km/h)") {
            askNumber("Llindar de l'avís", "km/h", false) { Prefs.alertKmh = it.toDouble() }
        })
        alertChBtn = bigButton("") {
            Prefs.alertChannel = (Prefs.alertChannel + 1) % SpeedAlert.CHANNEL_NAMES.size
            refreshSettings()
            SpeedAlert.play()
        }
        addButton(col, alertChBtn)
        addButton(col, bigButton("Prova el so") { SpeedAlert.play() })
        hint(col, "Quan la velocitat del GPS arriba al llindar, sonen quatre pips forts i es repeteixen cada 10 segons mentre s'hi mantingui. «Navegació» és el canal que se sent millor per sobre de la música. «Multimèdia» pot aturar la música i no tornar-la a posar. Cada canvi de canal sona un cop per comparar.")

        // --- Botons d'apps
        header(col, "Botons d'apps")
        for (i in 0 until Prefs.SLOTS) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val name = label(Prefs.SLOT_NAMES[i], 26f, Color.WHITE)
            name.typeface = Typeface.DEFAULT_BOLD
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val b = bigButton("") { chooseApp(i) }
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            slotRowButtons.add(b)
            val lp = LinearLayout.LayoutParams(0, dp(64f), 2f)
            lp.setMargins(0, dp(4f), 0, dp(4f))
            row.addView(b, lp)
            col.addView(row)
        }
        hint(col, "Ràdio, Música local i Música mòbil: toc curt = obre l'app i torna al tauler (l'àudio continua); toc llarg = obre l'app i s'hi queda. Als altres botons, el toc llarg canvia l'app assignada.")

        // --- Retorn al tauler
        header(col, "Retorn al tauler")
        delayInfo = label("", 26f, Color.WHITE)
        col.addView(delayInfo)
        addButtonRow(
            col,
            bigButton("−500") { changeDelay(-500L) },
            bigButton("−100") { changeDelay(-100L) },
            bigButton("+100") { changeDelay(100L) },
            bigButton("+500") { changeDelay(500L) }
        )
        hint(col, "Temps que espera abans de tornar al tauler. Com més curt, menys es veu l'app; si la ràdio no arrenca bé, allarga'l.")
        overlayInfo = label("", 24f, Color.WHITE)
        col.addView(overlayInfo)
        addButton(col, bigButton("Permet «Mostrar sobre altres apps»") { openOverlaySettings() })
        hint(col, "Només si el tauler no torna sol: aquest permís fa que Android ho permeti.")
        returnInfo = label("", 22f, Pal.GRAY)
        col.addView(returnInfo)

        // --- Mapa
        header(col, "Mapa")
        mapInfo = label("", 22f, Color.WHITE)
        col.addView(mapInfo)
        addButton(col, bigButton("Permet «Accés a tots els fitxers»") { openAllFilesSettings() })
        mapDimBtn = bigButton("") {
            Prefs.mapDim = !Prefs.mapDim
            mapPanel.applyDim()
            refreshSettings()
        }
        addButton(col, mapDimBtn)
        hint(col, "Els mapes (fitxers .map d'OpenAndroMaps) van a la carpeta «Mapes», a l'arrel de la memòria interna. Android demana el permís «Accés a tots els fitxers» perquè l'app pugui llegir-los. El mapa té el nord sempre amunt i el cotxe al centre.")

        // --- Viatge
        header(col, "Viatge")
        addButton(col, bigButton("Posa el viatge a zero") { confirmResetTrip() })

        // --- GPS
        header(col, "GPS")
        gpsInfo = label("", 24f, Color.WHITE)
        col.addView(gpsInfo)
        addButton(col, bigButton("Demana el permís d'ubicació") { ensureGps() })
        addButton(col, bigButton("Atura el GPS i tanca l'app") {
            GpsHub.persist()
            SpeedAlert.release()
            stopService(Intent(this, GpsService::class.java))
            finishAndRemoveTask()
        })

        // --- Diagnosi
        header(col, "Diagnosi")
        addButton(col, bigButton("Obre la diagnosi de tecles, GPS i sistema") {
            startActivity(Intent(this, DiagActivity::class.java))
        })
        hint(col, "Per veure quines tecles del volant arriben a l'app. Per tornar, fes servir el botó de tornar de la unitat.")

        val ver = label("Federicu Tauler V2 (Beta 1.2)", 18f, Pal.GRAY)
        ver.setPadding(0, dp(24f), 0, 0)
        col.addView(ver)
        return sv
    }

    private fun changeDelay(delta: Long) {
        Prefs.returnDelayMs = Prefs.returnDelayMs + delta
        refreshSettings()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Aquesta unitat no obre aquesta pantalla: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAllFilesSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "Aquesta unitat no obre aquesta pantalla: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onOff(v: Boolean) = if (v) "sí" else "no"

    private fun refreshSettings() {
        val raw = GpsHub.altitudeRaw
        val off = Prefs.altOffset
        altInfo.text = if (raw.isNaN()) {
            "Correcció: " + Fmt.signed(off, 0) + " m   (encara no hi ha alçada del GPS)"
        } else {
            "GPS brut: " + Fmt.num(raw, 0) + " m   Correcció: " + Fmt.signed(off, 0) +
                " m   Es mostra: " + Fmt.num(raw + off, 0) + " m"
        }
        for (i in slotRowButtons.indices) {
            slotRowButtons[i].text = appLabel(Prefs.slotPkg(i), Prefs.slotCls(i))
        }
        alertInfo.text = "Llindar: " + Fmt.num(Prefs.alertKmh, 0) + " km/h"
        alertBtn.text = "Avís de velocitat activat: " + onOff(Prefs.alertOn)
        alertChBtn.text = "Canal de l'avís: " + SpeedAlert.CHANNEL_NAMES[Prefs.alertChannel]
        delayInfo.text = "Retard abans de tornar: " + Prefs.returnDelayMs + " ms"
        overlayInfo.text = "«Mostrar sobre altres apps»: " + (if (Settings.canDrawOverlays(this)) "permès" else "no permès")
        mapInfo.text = mapPanel.info()
        mapDimBtn.text = "Mapa atenuat (nit): " + onOff(Prefs.mapDim)
        returnInfo.text = "Últim retorn al tauler: " + Returner.lastResult
        gpsInfo.text = GpsHub.statusLabel() + " — satèl·lits " + GpsHub.satsUsed + "/" + GpsHub.satsVisible +
            "   Servei: " + (if (GpsHub.serviceRunning) "actiu" else "aturat")
    }

    private companion object {
        const val REQ_LOCATION = 1
        const val TAB_TRIP = 1
        const val TAB_SETTINGS = 2
    }
}
