package ovh.adan.walkie

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Pantalla tipo walkie convencional, hecha con Views (Android 4.4). */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var mode: TextView
    private lateinit var freq: TextView
    private lateinit var info: TextView
    private lateinit var warn: TextView
    private lateinit var freqEdit: EditText
    private lateinit var shiftSpin: Spinner
    private lateinit var shiftEdit: EditText
    private lateinit var rxTone: Spinner
    private lateinit var txTone: Spinner
    private lateinit var wide: CheckBox
    private lateinit var stepSpin: Spinner
    private lateinit var vol: TextView
    private lateinit var ptt: Button
    private lateinit var power: Button
    private var loading = false

    private val steps = longArrayOf(5_000, 6_250, 10_000, 12_500, 25_000, 100_000, 1_000_000)
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        setContentView(build())
        if (getSharedPreferences("walkie", MODE_PRIVATE).getBoolean("on", true)) startService(Intent(this, RadioService::class.java))
        refresh()
    }

    override fun onResume() { super.onResume(); Radio.listener = { refresh() }; refresh() }
    override fun onPause() { Radio.listener = null; super.onPause() }

    // ---------- construcción de la pantalla ----------
    private fun build(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(8), px(4), px(8), px(8)) }
        status = text(13f, Color.LTGRAY)
        mode = text(15f, Color.rgb(255, 200, 80)).apply { typeface = Typeface.DEFAULT_BOLD }
        freq = text(34f, Color.WHITE).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER }
        info = text(13f, Color.LTGRAY).apply { gravity = Gravity.CENTER }
        warn = text(13f, Color.rgb(255, 90, 90))
        col.addView(row(mode, status.also { it.gravity = Gravity.END }))
        col.addView(freq); col.addView(info); col.addView(warn)

        ptt = Button(this).apply {
            text = "PTT"; textSize = 22f; minHeight = px(64)
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> if (!Radio.pttOn(store.current(), store.totSec))
                        toast(if (!Radio.on) "El módulo está apagado" else "No se puede transmitir aquí")
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Radio.pttOff()
                }
                false
            }
        }
        col.addView(ptt)

        freqEdit = EditText(this).apply {
            hint = "MHz (p. ej. 438.950)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> goFreq(); true }
        }
        col.addView(row(freqEdit, button("Ir") { goFreq() }), lp())

        stepSpin = spinner(steps.map { if (it >= 1_000_000) "${it / 1_000_000} MHz" else String.format(Locale.US, "%.2f kHz", it / 1000.0) }) {
            store.stepHz = steps[it]
        }
        col.addView(row(button("◀") { Walkie.step(store, -1); refresh() }, button("▶") { Walkie.step(store, +1); refresh() },
            label("Paso"), stepSpin))

        shiftSpin = spinner(listOf("Símplex", "− (baja)", "+ (sube)")) { applyShift() }
        shiftEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> applyShift(); true }
        }
        col.addView(row(label("Desplaz."), shiftSpin, shiftEdit, label("MHz")))

        val tones = Channel.CTCSS.indices.map { Channel.toneName(it) }
        rxTone = spinner(tones) { i -> edit { it.copy(rxTone = i) } }
        txTone = spinner(tones) { i -> edit { it.copy(txTone = i) } }
        col.addView(row(label("Subtono RX"), rxTone))
        col.addView(row(label("Subtono TX"), txTone))
        wide = CheckBox(this).apply {
            text = "Ancho (25 kHz)"; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, c -> if (!loading) edit { it.copy(wide = c) } }
        }
        col.addView(wide)

        vol = text(16f, Color.WHITE).apply { gravity = Gravity.CENTER }
        col.addView(row(button("Vol −") { volume(-1) }, vol, button("Vol +") { volume(+1) }))
        col.addView(row(button("VFO") { store.pmr = 0; store.memIndex = -1; Radio.apply(store.current()); refresh() },
            button("PMR") { pmrMenu() }, button("Memorias") { memories() }, button("Guardar") { save() }))
        power = button("Apagar") { togglePower() }
        col.addView(power)
        col.addView(text(12f, Color.GRAY).apply {
            text = "Banda 400-470 MHz. Sin subtono de RX el módulo no cierra el silenciador y suena el ruido. " +
                "Teclas de volumen = volumen del módulo (1-8). Rueda = canal o paso."
        })
        return ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 16, 20)); addView(col) }
    }

    // ---------- acciones ----------
    /** Cualquier cambio se hace sobre el VFO (si estabas en una memoria, se copia al VFO). */
    private fun edit(f: (Channel) -> Channel) {
        if (loading) return
        val cur = store.current()
        val c = f(cur).copy(name = "")
        if (c == cur.copy(name = "")) return
        // en PMR solo se puede cambiar el subtono (el mismo en TX y RX); lo demás pasa al VFO
        if (store.pmr in 1..16 && c.copy(rxTone = cur.rxTone, txTone = cur.txTone) == cur.copy(name = "")) {
            store.pmrTone = if (c.rxTone != cur.rxTone) c.rxTone else c.txTone
            Radio.apply(store.current()); refresh(); return
        }
        store.pmr = 0
        if (!c.valid()) { toast("Fuera de banda (400-470 MHz)"); refresh(); return }
        store.vfo = c; store.memIndex = -1
        Radio.apply(c); refresh()
    }

    private fun goFreq() {
        val hz = Channel.parseMhz(freqEdit.text.toString())
        if (hz == null || hz !in Channel.MIN_HZ..Channel.MAX_HZ) { toast("Frecuencia entre 400 y 470 MHz"); return }
        freqEdit.setText("")
        edit { it.copy(rxHz = hz) }
    }

    private fun applyShift() {
        if (loading) return
        val mhz = Channel.parseMhz(shiftEdit.text.toString())
        val abs = if (mhz != null && mhz in 1..69_000_000L) mhz else store.shiftHz
        store.shiftHz = abs
        val off = when (shiftSpin.selectedItemPosition) { 1 -> -abs; 2 -> abs; else -> 0L }
        edit { it.copy(offsetHz = off) }
    }

    private fun volume(d: Int) {
        store.volume = store.volume + d
        Radio.setVolume(store.volume); refresh()
    }

    private fun togglePower() {
        val p = getSharedPreferences("walkie", MODE_PRIVATE)
        val i = Intent(this, RadioService::class.java)
        if (Radio.on) { p.edit().putBoolean("on", false).apply(); stopService(i) }
        else { p.edit().putBoolean("on", true).apply(); startService(i) }
    }

    private fun memories() {
        val mems = store.memories
        if (mems.isEmpty()) { toast("No hay memorias. Pon un canal y pulsa Guardar."); return }
        val items = mems.mapIndexed { i, m -> "M${i + 1} ${m.name}  ${Channel.mhzShow(m.rxHz)}" + shiftTxt(m.offsetHz) }
        val d = AlertDialog.Builder(this).setTitle("Memorias (mantén pulsado para borrar)")
            .setItems(items.toTypedArray()) { _, i -> store.pmr = 0; store.memIndex = i; Radio.apply(store.current()); refresh() }
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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(4), px(12), 0) }
        val tone = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                Channel.CTCSS.indices.map { Channel.toneName(it) })
            setSelection(store.pmrTone)
        }
        box.addView(row(label("Subtono (TX y RX)"), tone))
        val items = (1..16).map { "PMR $it   ${Channel.mhzShow(Channel.pmrHz(it))}" }
        val list = android.widget.ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, items)
        }
        box.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(260)))
        val d = AlertDialog.Builder(this).setTitle("PMR446").setView(box).setNegativeButton("Cerrar", null).create()
        list.setOnItemClickListener { _, _, i, _ ->
            store.pmrTone = tone.selectedItemPosition
            store.pmr = i + 1
            Radio.apply(store.current()); refresh(); d.dismiss()
        }
        d.show()
    }

    private fun save() {
        val c = store.current()
        val name = EditText(this).apply { setSingleLine(); hint = "Nombre (p. ej. Repe Aitana)"; setText(c.name) }
        AlertDialog.Builder(this).setTitle("Guardar ${Channel.mhzShow(c.rxHz)}").setView(name)
            .setPositiveButton("Guardar") { _, _ ->
                val l = store.memories.toMutableList(); l.add(c.copy(name = name.text.toString().trim()))
                store.memories = l; store.pmr = 0; store.memIndex = l.size - 1; refresh()
            }.setNegativeButton("Cancelar", null).show()
    }

    // ---------- pintar ----------
    private fun shiftTxt(off: Long) = when {
        off > 0 -> " +" + Channel.mhz(off).trimEnd('0').trimEnd('.')
        off < 0 -> " −" + Channel.mhz(-off).trimEnd('0').trimEnd('.')
        else -> ""
    }

    private fun refresh() {
        loading = true
        val c = store.current()
        mode.text = Walkie.label(store)
        status.text = if (Radio.tx) "● TX" else Radio.status
        status.setTextColor(if (Radio.tx) Color.RED else Color.LTGRAY)
        freq.text = Channel.mhzShow(if (Radio.tx) c.txHz else c.rxHz)
        freq.setTextColor(if (Radio.tx) Color.RED else Color.WHITE)
        info.text = (if (c.offsetHz != 0L) "TX ${Channel.mhzShow(c.txHz)} (${shiftTxt(c.offsetHz).trim()})" else "Símplex") +
            " · RX ${Channel.toneName(c.rxTone)} · TX ${Channel.toneName(c.txTone)}"
        warn.text = listOfNotNull(
            if (Walkie.adnRunning(this)) "ADN PoC está en marcha: la tecla PTT transmitiría también por la red. Ciérrala." else null,
            if (store.pmr in 1..16) "PMR446: solo con la antena de serie (máx. 0,5 W PRA). La potencia del módulo no se puede bajar." else null,
            if (c.rxTone == 0) "Sin subtono de RX: el módulo no cierra el silenciador (ruido alto)." else null,
        ).joinToString("\n")
        warn.visibility = if (warn.text.isEmpty()) View.GONE else View.VISIBLE
        shiftSpin.put(if (c.offsetHz < 0) 1 else if (c.offsetHz > 0) 2 else 0)
        shiftEdit.setText(Channel.mhz(if (c.offsetHz != 0L) Math.abs(c.offsetHz) else store.shiftHz).trimEnd('0').trimEnd('.'))
        rxTone.put(c.rxTone); txTone.put(c.txTone)
        wide.isChecked = c.wide
        stepSpin.put(steps.indexOf(store.stepHz).coerceAtLeast(0))
        vol.text = "Vol ${store.volume}"
        power.text = if (Radio.on) "Apagar módulo" else "Encender módulo"
        ptt.isEnabled = Radio.on
        loading = false
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> { volume(+1); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { volume(-1); true }
        KeyEvent.KEYCODE_DPAD_UP -> { Walkie.step(store, +1); refresh(); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { Walkie.step(store, -1); refresh(); true }
        else -> super.onKeyDown(code, e)
    }

    // ---------- utilidades ----------
    private fun px(v: Int) = (v * dp).toInt()
    private fun lp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun text(sp: Float, color: Int) = TextView(this).apply { textSize = sp; setTextColor(color) }
    private fun label(s: String) = text(14f, Color.LTGRAY).apply { text = s; setPadding(px(4), 0, px(4), 0) }
    private fun button(s: String, f: () -> Unit) = Button(this).apply { text = s; textSize = 13f; setPadding(px(2), 0, px(2), 0); setOnClickListener { f() } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun Spinner.put(i: Int) { tag = i; setSelection(i) }

    private fun spinner(items: List<String>, onPick: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            // solo si lo cambia la persona: Android también avisa al pintar o al poner la selección por código
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (!loading && pos != tag) { tag = pos; onPick(pos) } }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
