package cat.sergibonet.diagnosi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Avís sonor de velocitat: quatre pips forts cada 10 segons mentre la velocitat del GPS
 * sigui igual o superior al llindar dels ajustaments.
 *
 * El so és una ona quadrada de 2,6 kHz a l'amplitud màxima (el pip de sistema era molt fluix).
 * Beta 4: ja no es demana el focus d'àudio. A la unitat la música no baixava, i amb el canal
 * multimèdia el reproductor s'aturava i no es reprenia. El canal per defecte és Navegació,
 * que és el que se sent millor per sobre de la música; es pot canviar als ajustaments.
 */
object SpeedAlert {
    const val CH_MEDIA = 0
    const val CH_ALARM = 1
    const val CH_NAV = 2
    val CHANNEL_NAMES = arrayOf("Multimèdia", "Alarma", "Navegació")

    private const val RATE = 22050
    private const val FREQ_HZ = 2600.0
    private const val PIP_MS = 160
    private const val GAP_MS = 100
    private const val PIPS = 4

    private val logic = AlertLogic()
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null

    private val pcm: ShortArray by lazy { buildPcm() }

    /** Es manté per compatibilitat amb les crides existents; ja no cal cap recurs del sistema. */
    @Suppress("UNUSED_PARAMETER")
    fun init(ctx: Context) {
    }

    fun onFix(elapsedMs: Long, kmh: Double) {
        if (logic.onFix(elapsedMs, kmh, Prefs.alertOn, Prefs.alertKmh)) play()
    }

    /** Ona quadrada a tota l'amplitud, amb rampes de 2 ms per evitar el clic. */
    private fun buildPcm(): ShortArray {
        val pip = RATE * PIP_MS / 1000
        val gap = RATE * GAP_MS / 1000
        val out = ShortArray(PIPS * pip + (PIPS - 1) * gap)
        val ramp = RATE * 2 / 1000
        var pos = 0
        for (n in 0 until PIPS) {
            for (i in 0 until pip) {
                val sq = if (sin(2.0 * PI * FREQ_HZ * i / RATE) >= 0.0) 1.0 else -1.0
                val env = min(1.0, min(i, pip - 1 - i).toDouble() / ramp)
                out[pos++] = (sq * env * 32000.0).toInt().toShort()
            }
            if (n < PIPS - 1) pos += gap
        }
        return out
    }

    private fun attrs(): AudioAttributes {
        val b = AudioAttributes.Builder()
        when (Prefs.alertChannel) {
            CH_ALARM -> b.setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            CH_NAV -> b.setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            else -> b.setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        }
        return b.build()
    }

    /** Quatre pips forts. També es fa servir al botó "Prova el so". */
    fun play() {
        stopNow()
        val a = attrs()
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(a)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setVolume(1.0f)
            t.play()
            track = t
            val totalMs = (pcm.size * 1000L / RATE) + 350L
            handler.postDelayed({ stopNow() }, totalMs)
        } catch (e: Exception) {
            // si el so falla, no passa res: l'avís és un extra
            stopNow()
        }
    }

    private fun stopNow() {
        handler.removeCallbacksAndMessages(null)
        try {
            track?.stop()
        } catch (e: Exception) {
        }
        track?.release()
        track = null
    }

    fun release() = stopNow()
}
