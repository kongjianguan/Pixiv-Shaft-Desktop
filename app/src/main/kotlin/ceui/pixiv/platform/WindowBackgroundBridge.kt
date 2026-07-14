package ceui.pixiv.platform

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Frame

/**
 * Sets the native NSWindow's background colour to dark, preventing the white
 * flash in newly-exposed areas during live resize when AWT [Frame.ignoreRepaint]
 * is enabled.
 */
object WindowBackgroundBridge {

    fun setDark(frame: Frame) {
        try {
            val ptr = getNSWindowPtr(frame)
            if (ptr != 0L) {
                setDarkBackground(Pointer(ptr))
                // Also set the content view's layer background as a fallback
                setLayerBackground(Pointer(ptr))
            }
        } catch (_: Throwable) {
            // Best effort
        }
    }

    private fun getNSWindowPtr(frame: Frame): Long {
        val peer = frame.javaClass.getMethod("getPeer").invoke(frame)
        return peer.javaClass.getMethod("getNSWindowPtr").invoke(peer) as Long
    }

    private fun setDarkBackground(nsWindow: Pointer) {
        val objc = NativeLibrary.getInstance("objc")
        fun sel(name: String): Pointer =
            objc.getFunction("sel_registerName").invokePointer(arrayOf<Any>(name))!!

        val nsColorClass = objc.getFunction("objc_getClass").invokePointer(arrayOf<Any>("NSColor"))!!
        val color = objc.getFunction("objc_msgSend")
            .invokePointer(arrayOf<Any>(nsColorClass, sel("controlBackgroundColor")))!!

        objc.getFunction("objc_msgSend")
            .invoke(arrayOf<Any>(nsWindow, sel("setBackgroundColor:"), color))
    }

    private fun setLayerBackground(nsWindow: Pointer) {
        val objc = NativeLibrary.getInstance("objc")
        fun sel(name: String): Pointer =
            objc.getFunction("sel_registerName").invokePointer(arrayOf<Any>(name))!!

        // contentView.layer.backgroundColor
        val contentView = objc.getFunction("objc_msgSend")
            .invokePointer(arrayOf<Any>(nsWindow, sel("contentView"))) ?: return
        objc.getFunction("objc_msgSend")
            .invoke(arrayOf<Any>(contentView, sel("setWantsLayer:"), 1.toByte()))
        val layer = objc.getFunction("objc_msgSend")
            .invokePointer(arrayOf<Any>(contentView, sel("layer"))) ?: return

        // CGColor: use system controlBackgroundColor via NSColor.CGColor
        val nsColorClass = objc.getFunction("objc_getClass").invokePointer(arrayOf<Any>("NSColor"))!!
        val color = objc.getFunction("objc_msgSend")
            .invokePointer(arrayOf<Any>(nsColorClass, sel("controlBackgroundColor")))!!
        val cgColor = objc.getFunction("objc_msgSend")
            .invokePointer(arrayOf<Any>(color, sel("CGColor")))!!

        objc.getFunction("objc_msgSend")
            .invoke(arrayOf<Any>(layer, sel("setBackgroundColor:"), cgColor))
    }
}
