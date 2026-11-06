package cat.sergibonet.diagnosi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper

/**
 * Servei en primer pla: manté el GPS i el viatge en marxa encara que
 * l'app no sigui a la pantalla (per exemple, mentre mires Maps).
 */
class GpsService : Service() {

    private var lm: LocationManager? = null
    private var registered = false

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            GpsHub.onLocation(location)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            GpsHub.onSatellites(used, status.satelliteCount)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        SpeedAlert.init(this)
        GpsHub.initStorage(this)
        GpsHub.restore()
        try {
            goForeground()
        } catch (e: Exception) {
            stopSelf()
            return
        }
        startUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "GPS del tauler", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Federicu Tauler")
            .setContentText("GPS i viatge actius")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun startUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper()
            )
            manager.registerGnssStatusCallback(mainExecutor, gnssCallback)
            registered = true
            GpsHub.serviceRunning = true
        } catch (e: SecurityException) {
            stopSelf()
        } catch (e: IllegalArgumentException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (registered) {
            lm?.removeUpdates(locListener)
            lm?.unregisterGnssStatusCallback(gnssCallback)
            registered = false
        }
        GpsHub.serviceRunning = false
        SpeedAlert.release()
        GpsHub.persist()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "gps"
        const val NOTIF_ID = 1
    }
}
