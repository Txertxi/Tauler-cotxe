package cat.sergibonet.diagnosi

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnosi per a la unitat Android del cotxe.
 * Quatre pestanyes: Tecles (volant), GPS, Apps instal·lades i Sistema.
 */
class DiagActivity : Activity() {

    private companion object {
        const val TAB_KEYS = 0
        const val TAB_GPS = 1
        const val TAB_APPS = 2
        const val TAB_SYS = 3
        const val REQ_LOCATION = 1
        const val MAX_LOG = 30000
        const val KEYS_HINT =
            "Prem els botons del volant (volum, track+, track-, mode...),\n" +
            "els de sota el volant i els de la propia unitat.\n" +
            "Cada pulsacio apareixera aqui, la mes recent a dalt.\n\n" +
            "[TECLA]        = tecla rebuda per l'app en primer pla\n" +
            "[MEDIASESSION] = boto multimedia rebut a traves del sistema\n"
    }

    private val keysLog = StringBuilder()
    private var appsText = ""
    private var sysText = ""
    private var tab = TAB_KEYS
    private var lastRender = 0L

    private var lastLoc: Location? = null
    private var satVisible = 0
    private var satUsed = 0
    private var gpsStarted = false
    private var gpsError = ""
    private var azimuth: Float? = null
    private var hasRotationSensor = false

    private var mediaSession: MediaSession? = null
    private lateinit var textView: TextView
    private val tabButtons = ArrayList<Button>()

    // ------------------------------------------------------------------ cicle de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        appsText = buildApps()
        sysText = buildSystem()
        setupMediaSession()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
        }
        showTab(TAB_KEYS)
    }

    override fun onResume() {
        super.onResume()
        startGps()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        hasRotationSensor = rv != null
        if (rv != null) sm.registerListener(sensorListener, rv, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() {
        super.onPause()
        stopGps()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.unregisterListener(sensorListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
        mediaSession = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            startGps()
            requestRender()
        }
    }

    // ------------------------------------------------------------------ interficie

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL

        val names = arrayOf("Tecles", "GPS", "Apps", "Sistema")
        for (i in names.indices) {
            val b = Button(this)
            b.text = names[i]
            b.textSize = 20f
            b.setOnClickListener { showTab(i) }
            bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            tabButtons.add(b)
        }

        val clear = Button(this)
        clear.text = "Esborra"
        clear.textSize = 20f
        clear.setOnClickListener {
            keysLog.setLength(0)
            render()
        }
        bar.addView(clear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val copy = Button(this)
        copy.text = "Copia"
        copy.textSize = 20f
        copy.setOnClickListener { copyReport() }
        bar.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val share = Button(this)
        share.text = "Comparteix"
        share.textSize = 20f
        share.setOnClickListener { shareReport() }
        bar.addView(share, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        textView = TextView(this)
        textView.setTextColor(Color.rgb(120, 255, 120))
        textView.typeface = Typeface.MONOSPACE
        textView.textSize = 18f
        textView.setPadding(24, 16, 24, 16)

        val scroll = ScrollView(this)
        scroll.addView(textView)

        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun showTab(index: Int) {
        tab = index
        for (j in tabButtons.indices) {
            tabButtons[j].alpha = if (j == index) 1f else 0.55f
        }
        render()
    }

    private fun render() {
        val text = when (tab) {
            TAB_KEYS -> if (keysLog.isEmpty()) KEYS_HINT else keysLog.toString()
            TAB_GPS -> buildGpsText()
            TAB_APPS -> appsText
            else -> sysText
        }
        textView.text = text
    }

    private fun requestRender() {
        val now = SystemClock.elapsedRealtime()
        if (tab == TAB_GPS && now - lastRender < 400) return
        lastRender = now
        render()
    }

    // ------------------------------------------------------------------ tecles

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logKey("TECLA", event)
        // Deixem que el sistema continui processant la tecla (p. ex. el volum).
        return super.dispatchKeyEvent(event)
    }

    private fun logKey(origin: String, e: KeyEvent?) {
        val line = if (e == null) {
            "${now()} [$origin] (sense KeyEvent)\n"
        } else {
            val act = when (e.action) {
                KeyEvent.ACTION_DOWN -> "DOWN"
                KeyEvent.ACTION_UP -> "UP  "
                else -> "ACT${e.action}"
            }
            val dev = e.device?.name ?: "?"
            "${now()} [$origin] $act ${KeyEvent.keyCodeToString(e.keyCode)} (${e.keyCode})" +
                " scan=${e.scanCode} rep=${e.repeatCount}" +
                " disp=${e.deviceId}:$dev src=0x${Integer.toHexString(e.source)}\n"
        }
        keysLog.insert(0, line)
        if (keysLog.length > MAX_LOG) keysLog.setLength(MAX_LOG)
        runOnUiThread { if (tab == TAB_KEYS) render() }
    }

    private fun logNote(text: String) {
        keysLog.insert(0, "${now()} [MEDIASESSION] callback: $text\n")
        runOnUiThread { if (tab == TAB_KEYS) render() }
    }

    @Suppress("DEPRECATION")
    private fun extractKeyEvent(i: Intent): KeyEvent? =
        i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

    private fun setupMediaSession() {
        val ms = MediaSession(this, "DiagnosiCotxe")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                logKey("MEDIASESSION", extractKeyEvent(mediaButtonIntent))
                return super.onMediaButtonEvent(mediaButtonIntent)
            }

            override fun onPlay() = logNote("onPlay")
            override fun onPause() = logNote("onPause")
            override fun onStop() = logNote("onStop")
            override fun onSkipToNext() = logNote("onSkipToNext")
            override fun onSkipToPrevious() = logNote("onSkipToPrevious")
        })
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
            .build()
        ms.setPlaybackState(state)
        ms.isActive = true
        mediaSession = ms
    }

    // ------------------------------------------------------------------ GPS i bruixola

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLoc = location
            requestRender()
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satVisible = status.satelliteCount
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            satUsed = used
            requestRender()
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val r = FloatArray(9)
            val o = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(r, event.values)
            SensorManager.getOrientation(r, o)
            var deg = Math.toDegrees(o[0].toDouble()).toFloat()
            if (deg < 0f) deg += 360f
            azimuth = deg
            if (tab == TAB_GPS) requestRender()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startGps() {
        if (gpsStarted) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
            lm.registerGnssStatusCallback(mainExecutor, gnssCallback)
            gpsStarted = true
            gpsError = ""
        } catch (e: SecurityException) {
            gpsError = "Error de permisos: ${e.message}"
        } catch (e: IllegalArgumentException) {
            gpsError = "Sense proveidor GPS: ${e.message}"
        }
    }

    private fun stopGps() {
        if (!gpsStarted) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(locListener)
        lm.unregisterGnssStatusCallback(gnssCallback)
        gpsStarted = false
    }

    private fun buildGpsText(): String {
        val sb = StringBuilder()
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val perm = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        sb.append("Permis d'ubicacio : ").append(if (perm) "concedit" else "NO concedit").append('\n')
        sb.append("Proveidors        : ").append(lm.allProviders.joinToString()).append('\n')
        sb.append("GPS activat       : ").append(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)).append('\n')
        sb.append("Satel.lits        : ").append(satUsed).append(" usats de ").append(satVisible).append(" visibles\n")
        if (gpsError.isNotEmpty()) sb.append("ERROR             : ").append(gpsError).append('\n')
        sb.append('\n')

        val l = lastLoc
        if (l == null) {
            sb.append("Encara no hi ha posicio.\n")
            sb.append("(Cal cel obert; el primer ajust pot trigar uns minuts.)\n")
        } else {
            val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L
            sb.append(String.format(Locale.US, "Velocitat  : %.1f km/h  (te velocitat: %s)\n", l.speed * 3.6f, l.hasSpeed()))
            sb.append(String.format(Locale.US, "Rumb (GPS) : %.0f graus  (te rumb: %s)\n", l.bearing, l.hasBearing()))
            sb.append(String.format(Locale.US, "Alcada     : %.0f m  (te alcada: %s)\n", l.altitude, l.hasAltitude()))
            sb.append(String.format(Locale.US, "Precisio   : %.0f m\n", l.accuracy))
            sb.append(String.format(Locale.US, "Latitud    : %.5f\n", l.latitude))
            sb.append(String.format(Locale.US, "Longitud   : %.5f\n", l.longitude))
            sb.append("Hora GPS   : ").append(fmt.format(Date(l.time))).append('\n')
            sb.append(String.format(Locale.US, "Edat dada  : %d s\n", age))
        }
        sb.append("Hora sistema: ").append(fmt.format(Date())).append('\n')

        sb.append("\nBRUIXOLA\n")
        sb.append("Magnetometre     : ")
            .append(if (sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null) "si" else "NO").append('\n')
        sb.append("Sensor de rotacio: ").append(if (hasRotationSensor) "si" else "NO").append('\n')
        val az = azimuth
        if (hasRotationSensor && az != null) {
            sb.append(String.format(Locale.US, "Azimut           : %.0f graus\n", az))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ apps i sistema

    @Suppress("DEPRECATION")
    private fun buildApps(): String {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val apps = found.sortedBy { it.loadLabel(pm).toString().lowercase() }
        val sb = StringBuilder("APPS AMB ICONA: ${apps.size}\n\n")
        for (r in apps) {
            sb.append(r.loadLabel(pm)).append('\n')
            sb.append("   ").append(r.activityInfo.packageName)
                .append(" / ").append(r.activityInfo.name).append('\n')
        }
        return sb.toString()
    }

    private fun buildSystem(): String {
        val sb = StringBuilder()
        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        sb.append("Model     : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})\n")
        sb.append("Android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Compilacio: ${Build.DISPLAY}\n")
        sb.append("Pantalla  : ${dm.widthPixels} x ${dm.heightPixels} px, densitat ${dm.density} (${dm.densityDpi} dpi)\n")
        sb.append("Rotacio   : ${display?.rotation}\n")
        sb.append("Orientacio: ${if (landscape) "apaisada" else "vertical"}\n")

        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
        sb.append("\nSENSORS (${sensors.size})\n")
        for (s in sensors) {
            sb.append(" - ${s.name} [${s.stringType}]\n")
        }

        sb.append("\nDISPOSITIUS D'ENTRADA\n")
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            sb.append(" - #$id ${d.name} src=0x${Integer.toHexString(d.sources)} virtual=${d.isVirtual}\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ informe

    private fun fullReport(): String =
        "== TECLES ==\n$keysLog\n\n" +
            "== GPS ==\n${buildGpsText()}\n\n" +
            "== SISTEMA ==\n$sysText\n\n" +
            "== APPS ==\n$appsText"

    private fun copyReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("informe", fullReport()))
        Toast.makeText(this, "Informe copiat al porta-retalls", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, fullReport())
        try {
            startActivity(Intent.createChooser(send, "Comparteix l'informe"))
        } catch (e: Exception) {
            Toast.makeText(this, "No s'ha pogut compartir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
}
