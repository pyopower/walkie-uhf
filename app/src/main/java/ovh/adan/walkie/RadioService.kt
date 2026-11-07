package ovh.adan.walkie

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder

/**
 * Mantiene el módulo encendido con la pantalla apagada y atiende la tecla PTT y la rueda,
 * que en el T298S llegan como avisos del sistema (los mismos que usa ADN PoC Lite).
 */
class RadioService : Service() {
    private lateinit var store: Store
    private lateinit var nm: NotificationManager

    private val keys = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (i.action) {
                PTT_DOWN -> { Walkie.stopScan(); Radio.pttOn(store.current(), store.totSec) }
                PTT_UP -> Radio.pttOff()
                KNOB_INC -> { Walkie.stopScan(); Walkie.step(store, +1) }
                KNOB_DEC -> { Walkie.stopScan(); Walkie.step(store, -1) }
                BTN_PLUS_DOWN -> sideDown(+1)
                BTN_PLUS_UP -> sideUp(+1)
                BTN_MINUS_DOWN -> sideDown(-1)
                BTN_MINUS_UP -> sideUp(-1)
            }
        }
    }

    // --- botones + y − bajo el PTT: corta = paso/canal, mantener = SCAN, con SCAN en marcha = parar ---
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sideDir = 0
    private var sideConsumed = false
    private val longPress = Runnable {
        sideConsumed = true
        Walkie.startScan(store, sideDir)
    }

    private fun sideDown(dir: Int) {
        if (sideDir != 0) return            // ya hay uno pulsado
        sideDir = dir
        sideConsumed = Walkie.scanning
        if (Walkie.scanning) { Walkie.stopScan(); return }
        handler.postDelayed(longPress, 800)
    }

    private fun sideUp(dir: Int) {
        if (dir != sideDir) return
        handler.removeCallbacks(longPress)
        if (!sideConsumed) Walkie.step(store, dir)
        sideDir = 0
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        startForeground(1, note())
        registerReceiver(keys, IntentFilter().apply {
            addAction(PTT_DOWN); addAction(PTT_UP); addAction(KNOB_INC); addAction(KNOB_DEC)
            addAction(BTN_PLUS_DOWN); addAction(BTN_PLUS_UP); addAction(BTN_MINUS_DOWN); addAction(BTN_MINUS_UP)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        })
        Radio.serviceListener = { nm.notify(1, note()); txLed() }
        Radio.squelch = store.squelch
        Radio.powerOn(store.current(), store.volume)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        Radio.serviceListener = null
        if (ledOn) Native.led(0, 0)
        Walkie.stopScan()
        unregisterReceiver(keys)
        Radio.pttOff()
        Radio.powerOff()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun note(): Notification {
        val c = store.current()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), 0)
        val b = Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_walkie)
            .setContentTitle(if (Radio.tx) "TX ${Channel.mhzShow(c.txHz)}" else "Walkie ${Channel.mhzShow(c.rxHz)}")
            .setContentText(Walkie.label(store) + " · " + Radio.status)
            .setContentIntent(open)
            .setOngoing(true)
        return b.build()
    }

    // LED rojo del frontal durante la TX (/dev/led_custom, como la app de fábrica).
    private var ledOn = false
    private fun txLed() {
        if (Radio.tx == ledOn) return
        ledOn = Radio.tx
        Native.led(0, if (ledOn) 1 else 0)
    }

    companion object {
        const val PTT_DOWN = "android.intent.action.PTT.down"
        const val PTT_UP = "android.intent.action.PTT.up"
        const val KNOB_INC = "android.intent.channel.inc.down"
        const val KNOB_DEC = "android.intent.channel.dec.down"
        // botones + y − bajo el PTT del T298S (teclas ZRK_ONE/ZRK_TWO, códigos 251/252)
        const val BTN_PLUS_DOWN = "android.intent.zrk.button1.down"
        const val BTN_PLUS_UP = "android.intent.zrk.button1.up"
        const val BTN_MINUS_DOWN = "android.intent.zrk.button2.down"
        const val BTN_MINUS_UP = "android.intent.zrk.button2.up"
    }
}

/** Lógica compartida entre la pantalla y el servicio. */
object Walkie {
    // ---------- escáner ----------
    @Volatile var scanning = false; private set
    /** El escáner está parado en un canal con señal (o esperando los 5 s tras ella). */
    @Volatile var hold = false; private set
    private var scanThread: Thread? = null

    /**
     * Recorre el modo actual: PMR (16), repetidores (32), memorias o VFO (de scanLo a scanHi con el paso).
     * Se para en cuanto se abre el silenciador y sigue 5 s después de que acabe la señal.
     */
    fun startScan(store: Store, dir: Int = +1) {
        if (scanning || !Radio.on) return
        if (store.pmr !in 1..16 && store.rpt == 0 && store.memIndex >= 0 && store.memories.size < 2) return
        scanning = true
        Radio.notifyChanged("SCAN")
        scanThread = Thread {
            var lastSignal = 0L
            while (scanning) {
                if (Radio.tx) { Thread.sleep(200); continue }
                if (Radio.readBusy()) {
                    lastSignal = System.currentTimeMillis()
                    if (!hold) { hold = true; Radio.notifyChanged(null) }
                    Thread.sleep(150); continue
                }
                if (lastSignal != 0L && System.currentTimeMillis() - lastSignal < 5000) { Thread.sleep(150); continue }
                lastSignal = 0L
                if (hold) { hold = false; Radio.notifyChanged(null) }
                synchronized(this) {
                    if (!scanning) return@Thread   // parado mientras esperaba: quedarse en este canal
                    next(store, dir, scan = true)
                }
                if (!Radio.applyQuiet(store.current())) { Thread.sleep(100); continue }
                Radio.notifyChanged(null)
                Thread.sleep(220)   // tiempo para que el silenciador reaccione en el canal nuevo
                if (Radio.readBusy()) lastSignal = System.currentTimeMillis()
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopScan() {
        if (!scanning) return
        synchronized(this) { scanning = false; hold = false }
        scanThread = null
        Radio.notifyChanged("Listo")
    }

    /** Rueda o flechas: en memoria pasa a la siguiente; en VFO sube o baja un paso. */
    fun step(store: Store, dir: Int) {
        next(store, dir, scan = false)
        Radio.apply(store.current())
    }

    /** Avanza al canal siguiente del modo actual (en VFO, si es escáner, dentro de scanLo..scanHi). */
    private fun next(store: Store, dir: Int, scan: Boolean) {
        val mems = store.memories
        if (store.pmr in 1..16) {
            store.pmr = (store.pmr - 1 + dir + 16) % 16 + 1
        } else if (store.rpt in 1..Channel.RPT_COUNT) {
            val n = Channel.RPT_COUNT
            store.rpt = (store.rpt - 1 + dir + n) % n + 1
        } else if (store.memIndex >= 0 && mems.isNotEmpty()) {
            store.memIndex = ((store.memIndex + dir) % mems.size + mems.size) % mems.size
        } else {
            val v = store.vfo
            var rx = v.rxHz + dir * store.stepHz
            if (scan) { if (rx > store.scanHi) rx = store.scanLo else if (rx < store.scanLo) rx = store.scanHi }
            store.vfo = v.copy(rxHz = rx.coerceIn(Channel.MIN_HZ, Channel.MAX_HZ))
        }
    }

    fun label(store: Store): String {
        if (store.pmr in 1..16) return "PMR ${store.pmr}"
        if (store.rpt in 1..Channel.RPT_COUNT) return "Repetidor ${Channel.rptName(store.rpt)}"
        val i = store.memIndex
        val m = store.memories.getOrNull(i) ?: return "VFO"
        return "M${i + 1}" + if (m.name.isNotBlank()) " ${m.name}" else ""
    }

    /** ¿Está ADN PoC en marcha? Atiende la misma tecla PTT y transmitiría por la red a la vez. */
    @Suppress("DEPRECATION")
    fun adnRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(200).any { it.service.packageName.startsWith("systems.adn.poc") }
    }
}
