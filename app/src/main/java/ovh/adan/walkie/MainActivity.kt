package ovh.adan.walkie

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Pantalla para el PoC (240x320). La frecuencia va en un display de 7 segmentos azul neón;
 * tocándola se abre el submenú de sintonía (frecuencia, paso, desplazamiento, subtono, ancho).
 * PTT, SCAN y cambio de canal van por las teclas físicas.
 */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var mode: TextView
    private lateinit var status: TextView
    private lateinit var display: FrameLayout
    private lateinit var ghost: TextView
    private lateinit var freq: TextView
    private lateinit var info: TextView
    private lateinit var warn: TextView
    private lateinit var vol: TextView
    private lateinit var sq: TextView
    private lateinit var power: Button
    private var seg: Typeface? = null
    private var blinkOn = true
    private val ui = Handler(Looper.getMainLooper())

    private val steps = longArrayOf(5_000, 6_250, 10_000, 12_500, 25_000, 100_000, 1_000_000)
    private val dp get() = resources.displayMetrics.density

    private val NEON = Color.rgb(30, 175, 235)
    private val NEON_TX = Color.rgb(255, 70, 60)
    private val GHOST = Color.rgb(3, 10, 15)          // segmentos apagados: apenas se intuyen
    private val BG = Color.rgb(8, 8, 12)

    /** Latido del punto MHz/kHz cuando el SCAN se para en un canal con señal. */
    private val blink = object : Runnable {
        override fun run() {
            blinkOn = !(Walkie.scanning && Walkie.hold) || !blinkOn
            paintFreq()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        seg = runCatching { Typeface.createFromAsset(assets, "dseg7.ttf") }.getOrNull()
        setContentView(build())
        if (getSharedPreferences("walkie", MODE_PRIVATE).getBoolean("on", true)) startService(Intent(this, RadioService::class.java))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Radio.listener = { refresh() }
        ui.post(blink)
        refresh()
    }

    override fun onPause() {
        Radio.listener = null
        ui.removeCallbacks(blink)
        super.onPause()
    }

    // ---------- pantalla ----------
    private fun build(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(6), px(2), px(6), px(4)) }

        mode = text(13f, Color.rgb(255, 190, 70)).apply { typeface = Typeface.DEFAULT_BOLD }
        status = text(12f, Color.LTGRAY).apply { gravity = Gravity.END }
        col.addView(row(mode, status))

        // display de 7 segmentos: "8888" apagados detrás y la frecuencia encima, con brillo
        ghost = TextView(this).apply { setTextColor(GHOST); gravity = Gravity.CENTER; typeface = seg; includeFontPadding = false }
        freq = TextView(this).apply {
            setTextColor(NEON); gravity = Gravity.CENTER; typeface = seg; includeFontPadding = false
            setShadowLayer(2.5f, 0f, 0f, glow(NEON))
        }
        display = FrameLayout(this).apply {
            setPadding(0, px(6), 0, px(6))
            addView(ghost, FrameLayout.LayoutParams(-1, -2)); addView(freq, FrameLayout.LayoutParams(-1, -2))
            setOnClickListener { tune() }
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) paintFreq() }
        }
        col.addView(display)

        info = text(12f, Color.rgb(150, 190, 210)).apply { gravity = Gravity.CENTER }
        warn = text(11f, Color.rgb(255, 90, 90)).apply { gravity = Gravity.CENTER }
        col.addView(info); col.addView(warn)

        // las filas de botones se reparten todo el alto que sobra (dedos grandes, pantalla pequeña)
        val grow = { LinearLayout.LayoutParams(-1, 0, 1f) }
        col.addView(row(button("VFO") { toVfo() }, button("PMR") { pmrMenu() }, button("Repet.") { rptMenu() }), grow())
        col.addView(row(button("Memorias") { memories() }, button("Guardar") { save() }), grow())
        vol = text(14f, Color.WHITE).apply { gravity = Gravity.CENTER }
        sq = text(14f, Color.WHITE).apply { gravity = Gravity.CENTER }
        col.addView(row(button("−") { volume(-1) }, vol, button("+") { volume(+1) }), grow())
        col.addView(row(button("−") { squelch(-1) }, sq, button("+") { squelch(+1) }), grow())
        power = button("") { togglePower() }
        col.addView(row(power, button("Salir") { quit() }), grow())
        return ScrollView(this).apply {
            setBackgroundColor(BG); isFillViewport = true
            addView(col, FrameLayout.LayoutParams(-1, -1))
        }
    }

    /** Pinta la frecuencia ajustando el tamaño al ancho; el punto se apaga en el latido del SCAN. */
    private fun paintFreq() {
        if (!::freq.isInitialized) return
        val c = store.current()
        val s = Channel.mhzShow(if (Radio.tx) c.txHz else c.rxHz)
        val g = s.replace(Regex("[0-9]"), "8")
        val w = display.width - px(4)
        if (w > 0) {
            val p = Paint().apply { typeface = seg; textSize = 100f }
            val size = (100f * w / p.measureText(g)).coerceAtMost(px(60).toFloat())
            freq.setTextSize(TypedValue.COMPLEX_UNIT_PX, size); ghost.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
        val color = if (Radio.tx) NEON_TX else NEON
        freq.setTextColor(color); freq.setShadowLayer(2.5f, 0f, 0f, glow(color))
        ghost.text = g
        val sp = SpannableString(s)
        val dot = s.indexOf('.')
        if (!blinkOn && dot >= 0) sp.setSpan(ForegroundColorSpan(Color.TRANSPARENT), dot, dot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        freq.text = sp
    }

    private fun refresh() {
        val c = store.current()
        mode.text = (if (Walkie.scanning) "SCAN · " else "") + Walkie.label(store)
        status.text = when {
            Radio.tx -> "● TX"
            Radio.busy -> "▮▮▮ SEÑAL"
            Walkie.scanning -> "buscando…"
            else -> Radio.status
        }
        status.setTextColor(when { Radio.tx -> NEON_TX; Radio.busy -> Color.rgb(80, 230, 120); else -> Color.LTGRAY })
        if (!(Walkie.scanning && Walkie.hold)) blinkOn = true
        paintFreq()
        val tone = if (c.rxTone == c.txTone) Channel.toneName(c.rxTone) else "RX ${Channel.toneName(c.rxTone)} / TX ${Channel.toneName(c.txTone)}"
        info.text = (if (c.offsetHz != 0L) "TX ${Channel.mhzShow(c.txHz)} (${shiftTxt(c.offsetHz)})" else "Símplex") +
            "\n$tone · ${if (c.wide) "25 kHz" else "12,5 kHz"}"
        warn.text = listOfNotNull(
            if (Walkie.adnRunning(this)) "ADN PoC está en marcha: la tecla PTT transmitiría también por la red." else null,
            if (store.pmr in 1..16) "PMR446: máx. 0,5 W PRA con antena de serie." else null,
        ).joinToString("\n")
        warn.visibility = if (warn.text.isEmpty()) View.GONE else View.VISIBLE
        vol.text = "Volumen ${store.volume}"
        sq.text = "Silenciador ${store.squelch}"
        // el texto dice el estado y su color se ve de un vistazo; tocarlo cambia al otro
        power.text = if (Radio.on) "Módulo ON" else "Módulo OFF"
        power.setTextColor(if (Radio.on) Color.rgb(60, 200, 90) else Color.rgb(230, 70, 60))
    }

    private fun shiftTxt(off: Long) = (if (off > 0) "+" else "−") +
        String.format(Locale("es"), "%.4f", Math.abs(off) / 1e6).trimEnd('0').trimEnd(',')

    // ---------- submenú de sintonía (al tocar la frecuencia) ----------
    private fun tune() {
        val cur = store.current()
        val inPmr = store.pmr in 1..16
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), px(4), px(10), 0) }
        val f = EditText(this).apply {
            textSize = 15f; hint = Channel.mhzShow(cur.rxHz); setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        box.addView(field("MHz", f))
        val step = spinner(steps.map { if (it >= 1_000_000) "${it / 1_000_000} MHz" else String.format(Locale("es"), "%.2f kHz", it / 1000.0) })
            .apply { setSelection(steps.indexOf(store.stepHz).coerceAtLeast(0)) }
        box.addView(field("Paso", step))
        val dir = spinner(listOf("Símplex", "− baja", "+ sube")).apply {
            setSelection(if (cur.offsetHz < 0) 1 else if (cur.offsetHz > 0) 2 else 0); isEnabled = !inPmr
        }
        val off = EditText(this).apply {
            textSize = 14f; setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; isEnabled = !inPmr
            setText(Channel.mhz(if (cur.offsetHz != 0L) Math.abs(cur.offsetHz) else store.shiftHz).trimEnd('0').trimEnd('.'))
        }
        box.addView(field("Despl.", dir, off))
        val tone = spinner(Channel.CTCSS.indices.map { Channel.toneName(it) }).apply { setSelection(maxOf(cur.rxTone, cur.txTone)) }
        box.addView(field("Subtono", tone))
        var wide = cur.wide
        val bw = Button(this).apply {
            textSize = 12f; isEnabled = !inPmr
            fun upd() { text = if (wide) "25 kHz (ancho)" else "12,5 kHz (estrecho)" }
            upd(); setOnClickListener { wide = !wide; upd() }
        }
        box.addView(field("Ancho", bw))

        val d = AlertDialog.Builder(this).setTitle("Sintonía").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Aceptar", null).setNegativeButton("Cancelar", null).create()
        fun accept() {
            val txt = f.text.toString()
            val hz = if (txt.isBlank()) cur.rxHz else Channel.parseMhz(txt)
            if (hz == null || hz !in Channel.MIN_HZ..Channel.MAX_HZ) { toast("Frecuencia entre 400 y 470 MHz"); return }
            store.stepHz = steps[step.selectedItemPosition]
            val absOff = Channel.parseMhz(off.text.toString())?.takeIf { it in 1..69_000_000L } ?: store.shiftHz
            store.shiftHz = absOff
            val o = when (dir.selectedItemPosition) { 1 -> -absOff; 2 -> absOff; else -> 0L }
            val t = tone.selectedItemPosition
            hideKeyboard(f)
            d.dismiss()
            edit { it.copy(rxHz = hz, offsetHz = if (inPmr) 0L else o, rxTone = t, txTone = t, wide = if (inPmr) false else wide) }
        }
        f.setOnEditorActionListener { _, _, _ -> accept(); true }
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { accept() } }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        f.requestFocus()
    }

    // ---------- acciones ----------
    /** Cambios sobre el canal actual. En PMR/repetidor, si solo cambia el subtono, se guarda en ese canal;
     *  si cambia otra cosa, pasa al VFO (como en un walkie). */
    private fun edit(f: (Channel) -> Channel) {
        Walkie.stopScan()
        val cur = store.current()
        val c = f(cur).copy(name = "")
        if (c == cur.copy(name = "")) { refresh(); return }
        if (!c.valid()) { toast("Fuera de banda (400-470 MHz)"); refresh(); return }
        val preset = store.pmr in 1..16 || store.rpt in 1..Channel.RPT_COUNT
        if (preset && c.copy(rxTone = cur.rxTone, txTone = cur.txTone) == cur.copy(name = "")) {
            if (store.pmr in 1..16) store.pmrTone = c.rxTone else store.setRptTone(store.rpt, c.rxTone)
            Radio.apply(store.current()); refresh(); return
        }
        store.clearPresets()
        store.vfo = c; store.memIndex = -1
        Radio.apply(c); refresh()
    }

    private fun toVfo() { Walkie.stopScan(); store.clearPresets(); store.memIndex = -1; Radio.apply(store.current()); refresh() }

    private fun volume(d: Int) { store.volume = store.volume + d; Radio.setVolume(store.volume); refresh() }

    private fun squelch(d: Int) {
        store.squelch = store.squelch + d
        Radio.squelch = store.squelch
        Radio.applySquelch(store.current()); refresh()
    }

    private fun togglePower() {
        val p = getSharedPreferences("walkie", MODE_PRIVATE)
        val i = Intent(this, RadioService::class.java)
        if (Radio.on) { Walkie.stopScan(); p.edit().putBoolean("on", false).apply(); stopService(i) }
        else { p.edit().putBoolean("on", true).apply(); startService(i) }
    }

    /** Sale del todo: apaga el módulo, para el servicio y cierra el proceso. Al volver a abrir,
     *  el módulo se enciende o no según como estaba (no se toca la preferencia «on»). */
    private fun quit() {
        Walkie.stopScan()
        stopService(Intent(this, RadioService::class.java))
        finish()
        ui.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
    }

    private fun memories() {
        val mems = store.memories
        if (mems.isEmpty()) { toast("No hay memorias. Pon un canal y pulsa Guardar."); return }
        val items = mems.mapIndexed { i, m -> "M${i + 1} ${m.name}  ${Channel.mhzShow(m.rxHz)}" + if (m.offsetHz != 0L) " ${shiftTxt(m.offsetHz)}" else "" }
        val d = AlertDialog.Builder(this).setTitle("Memorias (mantén pulsado para borrar)")
            .setItems(items.toTypedArray()) { _, i -> Walkie.stopScan(); store.clearPresets(); store.memIndex = i; Radio.apply(store.current()); refresh() }
            .setNegativeButton("Cerrar", null).create()
        d.show()
        d.listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, i, _ ->
            AlertDialog.Builder(this).setMessage("¿Borrar ${items[i]}?").setPositiveButton("Borrar") { _, _ ->
                val l = store.memories.toMutableList(); l.removeAt(i); store.memories = l
                if (store.memIndex == i) store.memIndex = -1 else if (store.memIndex > i) store.memIndex--
                d.dismiss(); refresh()
            }.setNegativeButton("No", null).show()
            true
        }
    }

    /** Los 16 canales PMR446 y un único subtono para TX y RX. */
    private fun pmrMenu() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), px(4), px(10), 0) }
        val tone = spinner(Channel.CTCSS.indices.map { Channel.toneName(it) }).apply { setSelection(store.pmrTone) }
        box.addView(field("Subtono", tone))
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1,
                (1..16).map { "PMR $it   ${Channel.mhzShow(Channel.pmrHz(it))}" })
        }
        box.addView(list, LinearLayout.LayoutParams(-1, px(200)))
        val d = AlertDialog.Builder(this).setTitle("PMR446").setView(box).setNegativeButton("Cerrar", null).create()
        list.setOnItemClickListener { _, _, i, _ ->
            Walkie.stopScan(); store.pmrTone = tone.selectedItemPosition; store.rpt = 0; store.pmr = i + 1
            Radio.apply(store.current()); refresh(); d.dismiss()
        }
        d.show()
    }

    /** Canales de repetidor UHF; cada uno recuerda su subtono (se cambia tocando la frecuencia). */
    private fun rptMenu() {
        val items = (1..Channel.RPT_COUNT).map {
            val t = store.rptTone(it)
            "${Channel.rptName(it)}  ${Channel.mhz(Channel.rptHz(it))}" + if (t > 0) "  · ${Channel.toneName(t)}" else ""
        }
        AlertDialog.Builder(this).setTitle("Repetidores UHF (−7,6 MHz)")
            .setItems(items.toTypedArray()) { _, i -> Walkie.stopScan(); store.pmr = 0; store.rpt = i + 1; Radio.apply(store.current()); refresh() }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun save() {
        val c = store.current()
        val name = EditText(this).apply { setSingleLine(); hint = "Nombre (p. ej. Repe Aitana)"; setText(c.name) }
        AlertDialog.Builder(this).setTitle("Guardar ${Channel.mhzShow(c.rxHz)}").setView(name)
            .setPositiveButton("Guardar") { _, _ ->
                val l = store.memories.toMutableList(); l.add(c.copy(name = name.text.toString().trim()))
                store.memories = l; store.clearPresets(); store.memIndex = l.size - 1; refresh()
            }.setNegativeButton("Cancelar", null).show()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> { volume(+1); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { volume(-1); true }
        231, 232 -> true   // botones + y −: los atiende el servicio por su aviso (también con pantalla apagada)
        KeyEvent.KEYCODE_DPAD_UP -> { Walkie.stopScan(); Walkie.step(store, +1); refresh(); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { Walkie.stopScan(); Walkie.step(store, -1); refresh(); true }
        else -> super.onKeyDown(code, e)
    }

    override fun onKeyUp(code: Int, e: KeyEvent): Boolean = if (code == 231 || code == 232) true else super.onKeyUp(code, e)

    // ---------- utilidades ----------
    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Resplandor suave: el mismo color a media transparencia. */
    private fun glow(c: Int) = Color.argb(70, Color.red(c), Color.green(c), Color.blue(c))

    private fun px(v: Int) = (v * dp).toInt()
    private fun text(sp: Float, color: Int) = TextView(this).apply { textSize = sp; setTextColor(color) }
    private fun label(s: String) = text(13f, Color.LTGRAY).apply { text = s; setPadding(px(2), 0, px(4), 0) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun button(s: String, f: () -> Unit) = Button(this).apply {
        text = s; textSize = 14f; minHeight = 0; minimumHeight = px(38); setPadding(px(2), 0, px(2), 0)
        setOnClickListener { f() }
    }

    /** Fila horizontal; los botones ocupan todo el alto de la fila. */
    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, if (it is Button) -1 else -2, 1f)) }
    }

    /** Desplegable con letra pequeña (el de serie es enorme para 240x320). */
    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, items) {
            override fun getView(pos: Int, v: View?, parent: android.view.ViewGroup) =
                (super.getView(pos, v, parent) as TextView).apply { textSize = 13f; setSingleLine() }
            override fun getDropDownView(pos: Int, v: View?, parent: android.view.ViewGroup) =
                (super.getDropDownView(pos, v, parent) as TextView).apply { textSize = 15f; setPadding(px(10), px(8), px(10), px(8)) }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    /** Fila «etiqueta + controles» con la etiqueta estrecha. */
    private fun field(name: String, vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(label(name).apply { textSize = 12f; setSingleLine() }, LinearLayout.LayoutParams(px(60), LinearLayout.LayoutParams.WRAP_CONTENT))
        v.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }
}
