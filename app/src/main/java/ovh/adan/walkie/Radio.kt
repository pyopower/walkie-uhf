package ovh.adan.walkie

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Manejo del módulo. Todo lo del puerto serie va en un hilo propio; la pantalla y el servicio
 * se enteran de los cambios con [listener] (siempre en el hilo principal).
 *
 * Formato comprobado en el T298S: AT+DMOSETGROUP=ancho,TX,RX,subtonoRX,silenciador,subtonoTX.
 * El silenciador de portadora NO cierra en este módulo: sin subtono de RX suena el ruido.
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
    var listener: (() -> Unit)? = null
    var serviceListener: (() -> Unit)? = null

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
        "${Channel.mhzCmd(c.rxHz)},${c.rxTone},8,${c.txTone}"

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

    private fun applyNow(c: Channel, tries: Int) {
        if (!c.valid()) { changed("Fuera de banda (400-470 MHz)"); return }
        val r = send(groupCmd(c), tries)
        changed(when {
            r == null -> "El módulo no contesta"
            r.endsWith(":0") -> if (c.rxTone == 0) "Sin subtono de RX: sonará el ruido" else "Listo"
            else -> "El módulo rechaza el canal"
        })
    }

    fun setVolume(v: Int) = io.execute { if (on) setVolumeNow(v, 3) }

    private fun setVolumeNow(v: Int, tries: Int) { send("AT+DMOSETVOLUME=${v.coerceIn(1, 8)}", tries) }

    // --- PTT: directo, sin pasar por la cola del puerto serie ---
    private val tot = Runnable { if (tx) { pttOff(); changed("Tiempo máximo de TX") } }

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
        if (tx) { tx = false; changed("Listo") }
    }
}
