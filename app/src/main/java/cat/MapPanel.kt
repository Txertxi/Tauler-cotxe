package cat.sergibonet.diagnosi

import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File

/**
 * Mapa sense connexió (OpenStreetMap, fitxers .map de Mapsforge/OpenAndroMaps).
 * Nord sempre amunt, cotxe sempre al centre. Es col·loca damunt de la pàgina Conducció.
 */
class MapPanel(ctx: Context) : FrameLayout(ctx) {

    /** Es crida quan cal que l'usuari doni el permís d'accés als fitxers. */
    var onNeedPermission: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val mapView: MapView
    private val scrim = View(ctx)
    private val arrow: ArrowView
    private val msgBox = LinearLayout(ctx)
    private val message = TextView(ctx)
    private val permBtn: Button
    private val attribution = TextView(ctx)

    private var tileCache: TileCache? = null
    private var layer: Layer? = null
    private var store: MultiMapDataStore? = null
    private val boxes = ArrayList<BoundingBox>()
    private var lastSig = ""
    private var state = State.NO_MAPS
    private var errors = ""
    private var lastCenterLat = Double.NaN
    private var lastCenterLon = Double.NaN
    private var zoom = Prefs.mapZoom
    private val dist = FloatArray(1)

    private enum class State { OK, NO_PERMISSION, NO_MAPS }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        if (!factoryReady) {
            AndroidGraphicFactory.createInstance(ctx.applicationContext as Application)
            factoryReady = true
        }
        setBackgroundColor(0xFF0B0D10.toInt())

        mapView = MapView(ctx)
        mapView.isClickable = false
        mapView.setBuiltInZoomControls(false)
        mapView.model.displayModel.setUserScaleFactor(1.35f)
        mapView.setZoomLevelMin(ZOOM_MIN.toByte())
        mapView.setZoomLevelMax(ZOOM_MAX.toByte())
        mapView.mapScaleBar.isVisible = true
        addView(mapView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Atenuació (mode nit): capa negra translúcida damunt el mapa.
        scrim.setBackgroundColor(Color.argb(140, 0, 0, 0))
        addView(scrim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Fletxa del cotxe; també s'empassa els tocs perquè el mapa no es pugui arrossegar.
        arrow = ArrowView(ctx)
        arrow.isClickable = true
        addView(arrow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Botons de zoom, a la dreta.
        val zoomCol = LinearLayout(ctx)
        zoomCol.orientation = LinearLayout.VERTICAL
        zoomCol.addView(zoomButton("+", 1), zoomParams())
        zoomCol.addView(zoomButton("−", -1), zoomParams())
        val zlp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        zlp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        zlp.rightMargin = dp(8f)
        addView(zoomCol, zlp)

        // Atribució obligatòria d'OpenStreetMap.
        attribution.text = "© OpenStreetMap contributors"
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        attribution.setTextColor(Color.WHITE)
        attribution.setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
        attribution.setBackgroundColor(Color.argb(150, 0, 0, 0))
        val alp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        alp.gravity = Gravity.END or Gravity.BOTTOM
        addView(attribution, alp)

        // Missatge quan no hi ha mapa (o quan el cotxe és fora de la zona dels mapes).
        msgBox.orientation = LinearLayout.VERTICAL
        msgBox.gravity = Gravity.CENTER
        msgBox.setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        message.setTextColor(Pal.GRAY)
        message.gravity = Gravity.CENTER
        msgBox.addView(message)
        permBtn = Button(ctx)
        permBtn.text = "Dona el permís"
        permBtn.isAllCaps = false
        permBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        permBtn.setTextColor(Color.BLACK)
        val bg = GradientDrawable()
        bg.setColor(Pal.AMBER)
        bg.cornerRadius = dp(12f).toFloat()
        permBtn.background = bg
        permBtn.setOnClickListener { onNeedPermission?.invoke() }
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52f))
        plp.topMargin = dp(10f)
        msgBox.addView(permBtn, plp)
        addView(msgBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        applyDim()
        showState()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun zoomParams(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(dp(54f), dp(54f))
        lp.setMargins(0, dp(4f), 0, dp(4f))
        return lp
    }

    private fun zoomButton(text: String, delta: Int): Button {
        val b = Button(context)
        b.text = text
        b.isAllCaps = false
        b.typeface = Typeface.DEFAULT_BOLD
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        b.setTextColor(Color.WHITE)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(0, 0, 0, 0)
        val n = GradientDrawable()
        n.setColor(Color.argb(200, 43, 48, 54))
        n.cornerRadius = dp(12f).toFloat()
        b.background = n
        b.setOnClickListener { changeZoom(delta) }
        return b
    }

    // ------------------------------------------------------------------ zoom

    private fun changeZoom(delta: Int) {
        zoom = (zoom + delta).coerceIn(ZOOM_MIN, ZOOM_MAX)
        Prefs.mapZoom = zoom
        mapView.model.mapViewPosition.zoomLevel = zoom.toByte()
    }

    // ------------------------------------------------------------------ fitxers de mapa

    private fun allFilesOk(): Boolean = Environment.isExternalStorageManager()

    /** Carpetes on es busquen els .map: «Mapes» a l'arrel de la memòria interna i la carpeta pròpia de l'app. */
    private fun mapDirs(): List<File> {
        val out = ArrayList<File>()
        val root = Environment.getExternalStorageDirectory()
        root.listFiles()?.forEach {
            if (it.isDirectory && it.name.lowercase() in DIR_NAMES) out.add(it)
        }
        context.getExternalFilesDir("Mapes")?.let { out.add(it) }
        return out
    }

    private fun findMaps(): List<File> {
        val out = ArrayList<File>()
        for (d in mapDirs()) {
            d.listFiles()?.forEach {
                if (it.isFile && it.name.lowercase().endsWith(".map")) out.add(it)
            }
        }
        out.sortBy { it.length() }
        return out
    }

    /** Descripció per a la pantalla d'ajustaments. */
    fun info(): String {
        val files = findMaps()
        val perm = if (allFilesOk()) "permís concedit" else "falta el permís d'accés als fitxers"
        val list = if (files.isEmpty()) "cap mapa trobat" else files.joinToString(", ") { it.name }
        return "Mapes: $list\nCarpeta «Mapes» (arrel de la memòria interna) — $perm"
    }

    /** Torna a mirar els fitxers i el permís; només refà el mapa si alguna cosa ha canviat. */
    fun refresh() {
        val perm = allFilesOk()
        val files = findMaps()
        val sig = perm.toString() + files.joinToString("|") { it.path + ":" + it.length() + ":" + it.lastModified() }
        if (sig == lastSig) return
        lastSig = sig
        unload()
        errors = ""
        if (files.isEmpty()) {
            state = if (perm) State.NO_MAPS else State.NO_PERMISSION
            showState()
            return
        }
        val multi = MultiMapDataStore(MultiMapDataStore.DataPolicy.DEDUPLICATE)
        for (f in files) {
            try {
                val mf = MapFile(f)
                multi.addMapDataStore(mf, false, false)
                boxes.add(mf.boundingBox())
            } catch (e: Exception) {
                errors += f.name + ": " + (e.message ?: e.javaClass.simpleName) + "\n"
            } catch (e: Error) {
                errors += f.name + ": " + e.javaClass.simpleName + "\n"
            }
        }
        if (boxes.isEmpty()) {
            state = State.NO_MAPS
            showState()
            return
        }
        store = multi
        val model = mapView.model
        val cache = AndroidUtil.createTileCache(
            context, "tauler_mapa", model.displayModel.tileSize, 1.0f,
            model.frameBufferModel.overdrawFactor, true
        )
        tileCache = cache
        val rl = AndroidUtil.createTileRendererLayer(
            cache, model.mapViewPosition, multi, MapsforgeThemes.MOTORIDER, false, true, false
        )
        layer = rl
        mapView.layerManager.layers.add(rl)

        zoom = Prefs.mapZoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
        model.mapViewPosition.zoomLevel = zoom.toByte()
        lastCenterLat = Double.NaN
        state = State.OK
        centerOnCar(true)
        showState()
    }

    private fun unload() {
        layer?.let {
            mapView.layerManager.layers.remove(it)
            it.onDestroy()
        }
        layer = null
        tileCache?.destroy()
        tileCache = null
        store?.close()
        store = null
        boxes.clear()
    }

    fun destroy() {
        handler.removeCallbacks(ticker)
        unload()
        mapView.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
    }

    // ------------------------------------------------------------------ estat visible

    private fun showState() {
        val ok = state == State.OK
        mapView.visibility = if (ok) View.VISIBLE else View.INVISIBLE
        arrow.visibility = if (ok) View.VISIBLE else View.GONE
        attribution.visibility = if (ok) View.VISIBLE else View.GONE
        if (ok) {
            // El missatge només apareix si el cotxe és fora de la zona dels mapes (vegeu tick()).
            msgBox.visibility = View.GONE
            return
        }
        msgBox.visibility = View.VISIBLE
        permBtn.visibility = if (state == State.NO_PERMISSION) View.VISIBLE else View.GONE
        message.text = when (state) {
            State.NO_PERMISSION ->
                "No puc llegir els mapes.\nCal el permís «Accés a tots els fitxers»."
            else -> "Cap mapa trobat.\nPosa els fitxers .map a la carpeta «Mapes» de la memòria interna." +
                (if (errors.isNotEmpty()) "\n\n" + errors.trim() else "")
        }
    }

    fun applyDim() {
        scrim.visibility = if (Prefs.mapDim) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ seguiment del cotxe

    private fun centerOnCar(force: Boolean) {
        if (state != State.OK) return
        var lat = GpsHub.lat
        var lon = GpsHub.lon
        if (lat.isNaN() || lon.isNaN()) {
            if (!force || boxes.isEmpty()) return
            val c = boxes[0].centerPoint
            lat = c.latitude
            lon = c.longitude
        }
        if (!force && !lastCenterLat.isNaN()) {
            Location.distanceBetween(lastCenterLat, lastCenterLon, lat, lon, dist)
            if (dist[0] < 2f) return
        }
        lastCenterLat = lat
        lastCenterLon = lon
        mapView.model.mapViewPosition.setCenter(LatLong(lat, lon))
    }

    private fun tick() {
        if (!isShown || state != State.OK) return
        centerOnCar(false)
        arrow.updateHeading()
        // Fora de la zona dels mapes baixats?
        if (GpsHub.hasPosition() && boxes.none { it.contains(GpsHub.lat, GpsHub.lon) }) {
            message.text = "Fora de la zona dels mapes baixats"
            msgBox.visibility = View.VISIBLE
            permBtn.visibility = View.GONE
        } else {
            msgBox.visibility = View.GONE
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ fletxa

    private class ArrowView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.AMBER }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
            color = Color.BLACK
        }
        private val path = Path()
        private var heading = 0f
        private var have = false

        fun updateHeading() {
            val moving = GpsHub.hasFreshFix() && GpsHub.hasBearing && GpsHub.speedKmh >= Cfg.HEADING_MIN_KMH
            if (moving) {
                heading = GpsHub.bearing.toFloat()
                have = true
            }
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val r = 18f * resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            if (!have) {
                c.drawCircle(cx, cy, r * 0.55f, fill)
                c.drawCircle(cx, cy, r * 0.55f, edge)
                return
            }
            c.save()
            c.rotate(heading, cx, cy)
            path.reset()
            path.moveTo(cx, cy - r)
            path.lineTo(cx + r * 0.75f, cy + r * 0.85f)
            path.lineTo(cx, cy + r * 0.40f)
            path.lineTo(cx - r * 0.75f, cy + r * 0.85f)
            path.close()
            c.drawPath(path, fill)
            c.drawPath(path, edge)
            c.restore()
        }
    }

    private companion object {
        const val ZOOM_MIN = 8
        const val ZOOM_MAX = 17
        const val TICK_MS = 700L
        val DIR_NAMES = setOf("mapes", "mapas", "maps")
        var factoryReady = false
    }
}
