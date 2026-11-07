package ovh.adan.walkie

import android.content.Context
import java.util.Locale

/** Un canal: frecuencia de recepción, desplazamiento de repetidor y subtonos (índice 0 = sin subtono). */
data class Channel(
    val name: String = "",
    val rxHz: Long = 435_000_000L,
    val offsetHz: Long = 0L,
    val rxTone: Int = 13,
    val txTone: Int = 13,
    val wide: Boolean = false,
) {
    val txHz get() = rxHz + offsetHz

    fun valid() = rxHz in MIN_HZ..MAX_HZ && txHz in MIN_HZ..MAX_HZ

    fun encode() = listOf(name.replace('|', ' ').replace('\n', ' '), rxHz, offsetHz, rxTone, txTone,
        if (wide) 1 else 0).joinToString("|")

    companion object {
        const val MIN_HZ = 400_000_000L
        const val MAX_HZ = 470_000_000L

        fun decode(s: String): Channel? = runCatching {
            val p = s.split("|")
            Channel(p[0], p[1].toLong(), p[2].toLong(), p[3].toInt(), p[4].toInt(), p[5] == "1")
        }.getOrNull()

        /** Tabla CTCSS del módulo: el índice es lo que va en el comando (13 = 103,5 Hz, comprobado). */
        val CTCSS = doubleArrayOf(0.0, 67.0, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4,
            100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3, 131.8, 136.5, 141.3, 146.2, 151.4,
            156.7, 162.2, 167.9, 173.8, 179.9, 186.2, 192.8, 203.5, 210.7, 218.1, 225.7, 233.6, 241.8, 250.3)

        fun toneName(i: Int) = if (i <= 0) "Sin subtono" else String.format(Locale.US, "%.1f Hz", CTCSS[i])

        fun mhz(hz: Long) = String.format(Locale.US, "%.4f", hz / 1e6)

        /** Para el módulo: 4 decimales, o 5 si hace falta (PMR 446,00625; el módulo los acepta). */
        fun mhzCmd(hz: Long) = String.format(Locale.US, if (hz % 100 == 0L) "%.4f" else "%.5f", hz / 1e6)

        /** Para la pantalla: 5 decimales solo si hacen falta. */
        fun mhzShow(hz: Long) = if (hz % 100 == 0L) mhz(hz) else mhzCmd(hz)

        /** PMR446: 16 canales de 12,5 kHz desde 446,00625 MHz. */
        fun pmrHz(n: Int) = 446_006_250L + (n - 1) * 12_500L

        fun pmr(n: Int, tone: Int) = Channel("PMR $n", pmrHz(n), 0L, tone, tone, false)

        /** Repetidores UHF (IARU R1 / reglamento español): salida 438,650-439,425 cada 25 kHz,
         *  entrada 7,6 MHz por debajo. n = 1..32 → R70..R101. */
        const val RPT_COUNT = 32
        fun rptHz(n: Int) = 438_650_000L + (n - 1) * 25_000L
        fun rptName(n: Int) = "R${69 + n}"
        fun rpt(n: Int, tone: Int) = Channel(rptName(n), rptHz(n), -7_600_000L, tone, tone, false)

        /** "435", "435.5", "435,500" o "435500" → Hz (null si no se entiende). */
        fun parseMhz(text: String): Long? {
            val t = text.trim().replace(',', '.')
            if (t.isEmpty()) return null
            val v = t.toDoubleOrNull() ?: return null
            val mhz = if (!t.contains('.') && v >= 100_000) v / 1000.0 else v
            return Math.round(mhz * 1e6 / 50.0) * 50L   // redondeo a 50 Hz
        }
    }
}

/** Memorias, VFO y ajustes en SharedPreferences. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("walkie", Context.MODE_PRIVATE)

    var vfo: Channel
        get() = p.getString("vfo", null)?.let { Channel.decode(it) } ?: Channel()
        set(v) { p.edit().putString("vfo", v.encode()).apply() }

    var memories: List<Channel>
        get() = (p.getString("mems", "") ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull { Channel.decode(it) }
        set(v) { p.edit().putString("mems", v.joinToString("\n") { it.encode() }).apply() }

    /** -1 = VFO; si no, índice de la memoria en uso. */
    var memIndex: Int
        get() = p.getInt("mem", -1)
        set(v) { p.edit().putInt("mem", v).apply() }

    var volume: Int
        get() = p.getInt("vol", 1)
        set(v) { p.edit().putInt("vol", v.coerceIn(1, 8)).apply() }

    /** Nivel del silenciador del módulo (1 = más sensible, 8 = solo señales fuertes). */
    var squelch: Int
        get() = p.getInt("sq", 3)
        set(v) { p.edit().putInt("sq", v.coerceIn(1, 8)).apply() }

    var stepHz: Long
        get() = p.getLong("step", 12_500L)
        set(v) { p.edit().putLong("step", v).apply() }

    var shiftHz: Long
        get() = p.getLong("shift", 7_600_000L)
        set(v) { p.edit().putLong("shift", v).apply() }

    var totSec: Int
        get() = p.getInt("tot", 180)
        set(v) { p.edit().putInt("tot", v).apply() }

    /** Canal PMR en uso (1-16) o 0 si no se está en PMR. Manda sobre memoria y VFO. */
    var pmr: Int
        get() = p.getInt("pmr", 0)
        set(v) { p.edit().putInt("pmr", v).apply() }

    /** Subtono de PMR: el mismo en TX y RX. */
    var pmrTone: Int
        get() = p.getInt("pmrTone", 13)
        set(v) { p.edit().putInt("pmrTone", v).apply() }

    /** Canal de repetidor en uso (1-32) o 0. */
    var rpt: Int
        get() = p.getInt("rpt", 0)
        set(v) { p.edit().putInt("rpt", v).apply() }

    /** Subtono de cada canal de repetidor (TX y RX); se queda guardado el último que pusiste. 0 = ninguno. */
    fun rptTone(n: Int) = p.getInt("rptTone_$n", 0)
    fun setRptTone(n: Int, t: Int) { p.edit().putInt("rptTone_$n", t).apply() }

    /** Sale de PMR y de repetidores (vuelve a memoria o VFO). */
    fun clearPresets() { pmr = 0; rpt = 0 }

    /** El canal que está sonando ahora: PMR, repetidor, la memoria elegida o el VFO. */
    fun current(): Channel = when {
        pmr in 1..16 -> Channel.pmr(pmr, pmrTone)
        rpt in 1..Channel.RPT_COUNT -> Channel.rpt(rpt, rptTone(rpt))
        else -> memories.getOrNull(memIndex) ?: vfo
    }
}
