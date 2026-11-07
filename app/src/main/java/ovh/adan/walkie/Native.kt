package ovh.adan.walkie

/** Llamadas al driver del módulo y al puerto serie (cpp/radio.c). */
object Native {
    init { System.loadLibrary("walkie") }
    @JvmStatic external fun power(on: Int): Int
    @JvmStatic external fun ptt(on: Int): Int
    @JvmStatic external fun open(path: String): Int
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun cmd(fd: Int, line: String, idleMs: Int, maxMs: Int): String?
}
