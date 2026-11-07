package dev.mapmirror

/** A link to the display. send() is called from a single encoder thread. */
interface Transport {
    val label: String
    /** True on a link that needs lower quality and frame rate, such as BLE. */
    val slow: Boolean get() = false
    fun start()
    fun isReady(): Boolean
    /** Sends one JPEG frame (data[0 until len]). Blocks until the display has it. */
    fun send(data: ByteArray, len: Int): Boolean
    fun close()
}
