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
                PTT_DOWN -> Radio.pttOn(store.current(), store.totSec)
                PTT_UP -> Radio.pttOff()
                KNOB_INC -> Walkie.step(store, +1)
                KNOB_DEC -> Walkie.step(store, -1)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        startForeground(1, note())
        registerReceiver(keys, IntentFilter().apply {
            addAction(PTT_DOWN); addAction(PTT_UP); addAction(KNOB_INC); addAction(KNOB_DEC)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        })
        Radio.serviceListener = { nm.notify(1, note()) }
        Radio.powerOn(store.current(), store.volume)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        Radio.serviceListener = null
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
        if (Radio.tx) b.setLights(0xFFFF0000.toInt(), 1000, 0)   // LED rojo fijo mientras transmite
        return b.build()
    }

    companion object {
        const val PTT_DOWN = "android.intent.action.PTT.down"
        const val PTT_UP = "android.intent.action.PTT.up"
        const val KNOB_INC = "android.intent.channel.inc.down"
        const val KNOB_DEC = "android.intent.channel.dec.down"
    }
}

/** Lógica compartida entre la pantalla y el servicio. */
object Walkie {
    /** Rueda o flechas: en memoria pasa a la siguiente; en VFO sube o baja un paso. */
    fun step(store: Store, dir: Int) {
        val mems = store.memories
        if (store.pmr in 1..16) {
            store.pmr = (store.pmr - 1 + dir + 16) % 16 + 1
        } else if (store.memIndex >= 0 && mems.isNotEmpty()) {
            store.memIndex = ((store.memIndex + dir) % mems.size + mems.size) % mems.size
        } else {
            val v = store.vfo
            val rx = (v.rxHz + dir * store.stepHz).coerceIn(Channel.MIN_HZ, Channel.MAX_HZ)
            store.vfo = v.copy(rxHz = rx)
        }
        Radio.apply(store.current())
    }

    fun label(store: Store): String {
        if (store.pmr in 1..16) return "PMR ${store.pmr}"
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
