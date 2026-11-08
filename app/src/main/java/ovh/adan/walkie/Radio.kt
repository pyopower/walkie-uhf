package ovh.adan.walkie

import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Manejo del módulo. Todo lo del puerto serie va en un hilo propio; la pantalla y el servicio
 * se enteran de los cambios con [listener] (siempre en el hilo principal).
 *
 * Formato comprobado en el T298S: AT+DMOSETGROUP=ancho,TX,RX,subtonoRX,silenciador,subtonoTX.
 * El silenciador funciona (1-8). Con el cable USB conectado entra ruido (QRM) y lo abre.
 */
object Radio {
    private const val TAG = "WALKIE"
    private const val TTY = "/dev/ttyMT0"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var fd = -1

    @Volatile var on = false; private set
    @Volatile var tx = false; private set
    @Volatile var status = "Apagado"; private set
    @Volatile var squelch = 3
    /** Silenciador abierto (hay señal). Se lee del pin 136 (salida SQ del módulo, 0 = señal). */
    @Volatile var busy = false; private set
    private val poller = Executors.newSingleThreadScheduledExecutor()
    init {
        poller.scheduleAtFixedRate({
            val b = on && !tx && readBusy()
            if (b != busy) { busy = b; changed() }
        }, 500, 150, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Lee el estado del pin en /sys (MediaTek, legible sin root): "136:4110100-1", DIN es la 3ª cifra. */
    fun readBusy(): Boolean = try {
        java.io.File("/sys/devices/virtual/misc/mtgpio/pin").useLines { lines ->
            val l = lines.map { it.trim() }.firstOrNull { it.startsWith("136:") }
            l != null && l.length > 6 && l[6] == '0'
        }
    } catch (e: Exception) { false }
    var listener: (() -> Unit)? = null
    var serviceListener: (() -> Unit)? = null

    fun notifyChanged(msg: String?) = changed(msg)

    private fun changed(msg: String? = null) {
        if (msg != null) { status = msg; Log.i(TAG, msg) }
        main.post { listener?.invoke(); serviceListener?.invoke() }
    }

    private fun send(line: String, tries: Int = 3): String? {
        repeat(tries) {
            val r = Native.cmd(fd, line, 80, 600) ?: ""
            Log.i(TAG, "$line -> ${r.trim()}")
            if (r.contains(":")) return r.substringAfter("+").trim()
        }
        return null
    }

    private fun groupCmd(c: Channel) = "AT+DMOSETGROUP=${if (c.wide) 1 else 0},${Channel.mhzCmd(c.txHz)}," +
        "${Channel.mhzCmd(c.rxHz)},${c.rxTone},$squelch,${c.txTone}"

    /** Enciende el módulo y deja puesto el canal: primero el grupo (con subtono calla) y luego el volumen. */
    fun powerOn(c: Channel, volume: Int) = io.execute {
        if (on) return@execute
        if (Native.power(1) < 0) { changed("No se puede abrir el módulo"); return@execute }
        fd = Native.open(TTY)
        if (fd < 0) { Native.power(0); changed("No se puede abrir $TTY"); return@execute }
        on = true
        applyNow(c, 8)
        setVolumeNow(volume, 8)
        changed()
    }

    fun powerOff() = io.execute {
        if (!on) return@execute
        Native.ptt(0); tx = false
        Native.power(0)
        Native.close(fd); fd = -1
        on = false
        changed("Apagado")
    }

    fun apply(c: Channel) = io.execute { if (on) applyNow(c, 3) }

    /**
     * Cambio de nivel del silenciador. Si el silenciador ya está abierto, el módulo no siempre lo
     * vuelve a evaluar con el nivel nuevo (al subir de 1 a 8 con QRM se queda abierto). Saltar un
     * momento a 1 MHz de distancia y volver le obliga a evaluarlo de cero.
     */
    fun applySquelch(c: Channel) = io.execute {
        if (!on || !c.valid()) return@execute
        val d = if (c.rxHz + 1_000_000L <= Channel.MAX_HZ) 1_000_000L else -1_000_000L
        send(groupCmd(Channel(rxHz = c.rxHz + d, rxTone = 0, txTone = 0, wide = c.wide)), 2)
        Thread.sleep(30)
        applyNow(c, 3)
    }

    /** Para el escáner: cambia de canal y espera a que termine (sin mensajes en pantalla). */
    fun applyQuiet(c: Channel): Boolean = try {
        io.submit<Boolean> { on && c.valid() && send(groupCmd(c), 2)?.endsWith(":0") == true }.get()
    } catch (e: Exception) { false }

    private fun applyNow(c: Channel, tries: Int) {
        if (!c.valid()) { changed("Fuera de banda (400-470 MHz)"); return }
        val r = send(groupCmd(c), tries)
        changed(when {
            r == null -> "El módulo no contesta"
            r.endsWith(":0") -> "Listo"
            else -> "El módulo rechaza el canal"
        })
    }

    fun setVolume(v: Int) = io.execute { if (on) setVolumeNow(v, 3) }

    private fun setVolumeNow(v: Int, tries: Int) { send("AT+DMOSETVOLUME=${v.coerceIn(1, 8)}", tries) }

    // --- PTT: directo, sin pasar por la cola del puerto serie ---
    private val tot = Runnable { if (tx) { pttOff(); changed("Tiempo máximo de TX") } }

    // Pitido local al soltar el PTT (no hay al apretar: necesitaría ~0,3 s de retraso,
    // porque el PTT corta el altavoz antes de que salga). Solo suena en el altavoz: el audio de
    // Android no llega a la transmisión del módulo (probado en el aire).
    // Lleva 100 ms de silencio delante: el amplificador tarda en despertar y se comía el pitido.
    private const val RATE = 22050
    private const val LEAD_MS = 100
    private fun pcm(freq: Double, ms: Int, amp: Int): ShortArray {
        val lead = RATE * LEAD_MS / 1000; val n = RATE * ms / 1000
        val a = ShortArray(lead + n)
        for (i in 0 until n) {
            val env = minOf(1.0, minOf(i, n - i) / (RATE * 0.005))   // rampa de 5 ms, sin chasquidos
            a[lead + i] = (Math.sin(2 * Math.PI * freq * i / RATE) * amp * env).toInt().toShort()
        }
        return a
    }
    private fun track(freq: Double, ms: Int, amp: Int): AudioTrack? = try {
        val d = pcm(freq, ms, amp)
        AudioTrack(AudioManager.STREAM_SYSTEM, RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, d.size * 2, AudioTrack.MODE_STATIC).apply { write(d, 0, d.size) }
    } catch (e: Exception) { null }
    private val beepRx by lazy { track(600.0, 40, 400) }
    private fun beep(t: AudioTrack?) {
        try { t ?: return; t.stop(); t.reloadStaticData(); t.play() } catch (_: Exception) {}
    }
    fun pttOn(c: Channel, totSec: Int): Boolean {
        if (!on || tx || !c.valid()) return false
        if (Native.ptt(1) < 0) { changed("PTT falló"); return false }
        tx = true
        main.removeCallbacks(tot); main.postDelayed(tot, totSec * 1000L)
        changed("TX ${Channel.mhzShow(c.txHz)}")
        return true
    }

    fun pttOff() {
        main.removeCallbacks(tot)
        Native.ptt(0)
        if (tx) { tx = false; beep(beepRx); changed("Listo") }
    }
}
