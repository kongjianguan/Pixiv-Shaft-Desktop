package ceui.pixiv.platform

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges macOS trackpad pinch (NSEvent.NSMagnify) into the JVM via the official
 * NSEvent local monitor API, without touching NSWindow's isa (which crashes AppKit's KVO).
 *
 * Compose Desktop 1.7.x / skiko-awt only registers Mouse/MouseMotion/MouseWheel listeners;
 * AWT drops NSEventTypeMagnify entirely. We install a local event monitor with
 * [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskMagnify handler:^...] that
 * forwards each pinch frame's magnification to a Kotlin handler.
 *
 * The hard part: the handler takes an Objective-C block, not a C function pointer.
 * JNA can't make blocks natively, so we hand-assemble a global Block_layout struct in
 * Memory (isa=_NSConcreteGlobalBlock, invoke=JNA Callback stub). Global blocks need no
 * copy/dispose helpers and never get freed, which is exactly what we want for a single
 * app-lifetime monitor.
 */
object TrackpadGestureBridge {

    private val magnifyHandler = AtomicReference<((Double) -> Unit)?>(null)
    private val scrollHandler = AtomicReference<((ScrollEvent) -> Boolean)?>(null)
    // Owner token so only the page the user is actually looking at can register/clear
    // the single global handler. Without this, HorizontalPager preloaded adjacent pages would each overwrite (and on dispose null out) the handler.
    private val handlerLock = Any()
    private var handlerOwner: Any? = null
    private var scrollHandlerOwner: Any? = null
    @Volatile private var installed = false

    // Strong refs to prevent GC of the native-backed callback and block memory.
    private var blockCallback: EventBlockCallback? = null
    private var blockMemory: Memory? = null
    private var blockDescriptor: Memory? = null
    @Volatile private var monitorObject: Pointer? = null

    /** Idempotent. Returns true once the NSEvent monitor is live. */
    fun install(): Boolean {
        if (installed) return true
        try {
            val objc = objcLib
            val foundation = NativeLibrary.getInstance("Foundation")

            // --- Build a no-capture global block by hand ---
            // struct Block_layout { void* isa; int flags; int reserved; void(*invoke)(...); void* descriptor; }
            val block = Memory(32L)
            val descriptor = Memory(16L) // { uintptr_t reserved; uintptr_t size; }

            // isa = *_NSConcreteGlobalBlock (the symbol holds the class pointer)
            val concreteGlobalBlockSym = foundation.getGlobalVariableAddress("_NSConcreteGlobalBlock")
            val globalBlockClass = concreteGlobalBlockSym.getPointer(0)

            // invoke = JNA-generated stub backed by our Callback
            val cb = object : EventBlockCallback {
                override fun invoke(block: Pointer, event: Pointer): Pointer? = onEvent(event)
            }
            blockCallback = cb
            val invokePtr = CallbackReference.getFunctionPointer(cb)

            // flags = BLOCK_IS_GLOBAL (1 << 28). No copy/dispose, no signature.
            block.setPointer(0, globalBlockClass)
            block.setInt(8, 0x10000000)
            block.setInt(12, 0)
            block.setPointer(16, invokePtr)
            block.setPointer(24, descriptor)

            descriptor.setLong(0, 0L)         // reserved
            descriptor.setLong(8, 32L)        // size = sizeof(Block_layout)

            blockMemory = block
            blockDescriptor = descriptor

            // Listen for magnify and scroll-wheel events. Scroll events include the
            // native gesture and momentum phases that Compose Desktop discards.
            val nsEventClass = objc.getFunction("objc_getClass")
                .invokePointer(arrayOf<Any>("NSEvent")) ?: run {
                return false
            }
            // NSEventTypeScrollWheel = 22, NSEventTypeMagnify = 30.
            val mask: Long = (1L shl 22) or (1L shl 30)
            val sel = selRegister("addLocalMonitorForEventsMatchingMask:handler:")
            // objc_msgSend returns an opaque monitor object we must keep alive.
            val monitor = objc.getFunction("objc_msgSend")
                .invokePointer(arrayOf<Any>(nsEventClass, sel, mask, block)) ?: run {
                return false
            }
            monitorObject = monitor
            installed = true
        } catch (t: Throwable) {
            // JNA/ObjC bridge 初始化失败（如缺少 native 库或不支持的平台），
            // 直接抛出，由调用方决定是否降级。
            throw t
        }
        return installed
    }

    fun setMagnifyHandler(token: Any, h: ((Double) -> Unit)?) {
        synchronized(handlerLock) {
            if (h != null) {
                handlerOwner = token
                magnifyHandler.set(h)
            } else if (handlerOwner === token) {
                handlerOwner = null
                magnifyHandler.set(null)
            }
        }
    }

    fun setScrollHandler(token: Any, h: ((ScrollEvent) -> Boolean)?) {
        synchronized(handlerLock) {
            if (h != null) {
                scrollHandlerOwner = token
                scrollHandler.set(h)
            } else if (scrollHandlerOwner === token) {
                scrollHandlerOwner = null
                scrollHandler.set(null)
            }
        }
    }

    /** Block invoke signature: NSEvent* (^)(NSEvent*). First arg is the block itself. */
    interface EventBlockCallback : Callback {
        fun invoke(block: Pointer, event: Pointer): Pointer?
    }

    data class ScrollEvent(
        val deltaX: Double,
        val deltaY: Double,
        val phase: Long,
        val momentumPhase: Long,
    )

    object Phase {
        const val NONE = 0L
        const val BEGAN = 1L shl 0
        const val CHANGED = 1L shl 2
        const val ENDED = 1L shl 3
        const val CANCELLED = 1L shl 4

        fun has(value: Long, flag: Long): Boolean = value and flag != 0L
        fun isFinished(value: Long): Boolean = has(value, ENDED) || has(value, CANCELLED)
    }

    // Returning null consumes an event before it reaches AWT/Compose.
    private fun onEvent(event: Pointer): Pointer? {
        return try {
            val msgSend = objcLib.getFunction("objc_msgSend")
            when (msgSend.invokeLong(arrayOf<Any>(event, selRegister("type")))) {
                30L -> {
                    val h = magnifyHandler.get()
                    if (h != null) {
                        val mag = msgSend.invokeDouble(arrayOf<Any>(event, selRegister("magnification")))
                        h.invoke(mag)
                    }
                    event
                }
                22L -> {
                    val h = scrollHandler.get() ?: return event
                    val scrollEvent = ScrollEvent(
                        deltaX = msgSend.invokeDouble(arrayOf<Any>(event, selRegister("scrollingDeltaX"))),
                        deltaY = msgSend.invokeDouble(arrayOf<Any>(event, selRegister("scrollingDeltaY"))),
                        phase = msgSend.invokeLong(arrayOf<Any>(event, selRegister("phase"))),
                        momentumPhase = msgSend.invokeLong(arrayOf<Any>(event, selRegister("momentumPhase"))),
                    )
                    if (h.invoke(scrollEvent)) null else event
                }
                else -> event
            }
        } catch (_: Throwable) {
            event
        }
    }

    // Kept public for the existing magnify bridge tests/callers.
    fun onMagnify(block: Pointer, event: Pointer): Pointer {
        val h = magnifyHandler.get()
        if (h != null) {
            try {
                val mag = objcLib.getFunction("objc_msgSend")
                    .invokeDouble(arrayOf<Any>(event, selRegister("magnification")))
                h.invoke(mag)
            } catch (_: Throwable) {
            }
        }
        return event
    }

    // ---- libobjc helpers ----

    private val objcLib by lazy { NativeLibrary.getInstance("objc") }
    private fun selRegister(name: String): Pointer =
        objcLib.getFunction("sel_registerName").invokePointer(arrayOf<Any>(name))!!
}
