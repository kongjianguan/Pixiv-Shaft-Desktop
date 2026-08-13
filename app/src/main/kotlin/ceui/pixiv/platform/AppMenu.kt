package ceui.pixiv.platform

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Puts "我的" and "设置" items into the macOS application menu — the first
 * menu on the menu bar, titled with the app name next to the Apple logo.
 *
 * The JVM application menu (About/Hide/Quit) is created by the runtime at
 * launch; AWT/Swing/Compose cannot add items to it (verified: neither a
 * java.awt.MenuBar nor a Swing JMenuBar gets merged into it). The only way
 * is to reach the AppKit NSMenu directly via JNA.
 *
 * Two platform constraints shape the implementation:
 *  - The main menu may only be modified on the main thread; AppKit raises
 *    NSInternalInconsistencyException otherwise.
 *  - A menu item's action must be an Objective-C method, not a C function.
 *
 * Both are solved with a tiny private ObjC class (PixivShaftMenuExecutor)
 * whose three methods (javaProfileClicked: / javaSettingsClicked: /
 * javaInstallTask:) are implemented by JNA Callback stubs via class_addMethod.
 * From any Java thread we submit work with
 * performSelectorOnMainThread:withObject:waitUntilDone:, which runs the
 * selector on the AppKit main thread.
 *
 * Failure is non-fatal: any Throwable is swallowed and the app runs without
 * the app-menu items.
 */
object AppMenu {

    /** ObjC method signature (void, self, _cmd, sender). */
    private const val OBJC_VOID_1ARG = "v@:@"
    /** NSEventModifierFlagCommand = 1 << 20 */
    private const val MODIFIER_CMD = 1L shl 20

    private interface MenuActionCallback : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?, sender: Pointer?)
    }

    private val profileAction = AtomicReference<(() -> Unit)?>(null)
    private val settingsAction = AtomicReference<(() -> Unit)?>(null)

    // Strong refs: JNA keeps the native closure alive only while the Callback
    // object is reachable. The executor target is retained by the menu items.
    private var profileCallback: MenuActionCallback? = null
    private var settingsCallback: MenuActionCallback? = null
    private var taskCallback: MenuActionCallback? = null
    private var executor: Pointer? = null
    // 已成功插入的菜单项，重试前先移除，避免两次 insertItem: 之间失败造成重复项。
    // 只有 insertItem: 成功后才记录，指针才指向被菜单 retain 的合法对象。
    private var insertedProfileItem: Pointer? = null
    private var insertedSettingsItem: Pointer? = null
    @Volatile private var installed = false
    /** javaInstallTask: 回调在 AppKit 主线程置位；waitUntilDone 保证 install() 能同步读到结果 */
    private val installSucceeded = AtomicBoolean(false)

    val isInstalled: Boolean get() = installed

    private val objcLib by lazy { NativeLibrary.getInstance("objc") }

    private fun sel(name: String): Pointer =
        objcLib.getFunction("sel_registerName").invokePointer(arrayOf<Any>(name))!!

    private fun cls(name: String): Pointer =
        objcLib.getFunction("objc_getClass").invokePointer(arrayOf<Any>(name))!!

    private fun msgSend(receiver: Pointer?, selector: String, vararg args: Any?): Pointer =
        objcLib.getFunction("objc_msgSend")
            .invokePointer(arrayOf<Any?>(receiver, sel(selector), *args))!!

    private fun msgSendVoid(receiver: Pointer?, selector: String, vararg args: Any?) {
        objcLib.getFunction("objc_msgSend")
            .invokeVoid(arrayOf<Any?>(receiver, sel(selector), *args))
    }

    private fun makeNSString(s: String): Pointer {
        val ns = msgSend(cls("NSString"), "alloc")
        return msgSend(ns, "initWithUTF8String:", s)
    }

    /** Idempotent. Installs the items on the AppKit main thread. */
    fun install(onProfile: () -> Unit, onSettings: () -> Unit) {
        if (installed) return
        profileAction.set(onProfile)
        settingsAction.set(onSettings)
        try {
            ensureExecutor()
            installSucceeded.set(false)
            // The executor's javaInstallTask: runs on the AppKit main thread,
            // where modifying the main menu is allowed.
            msgSendVoid(
                executor,
                "performSelectorOnMainThread:withObject:waitUntilDone:",
                sel("javaInstallTask:"),
                null,
                true,
            )
            // 只有回调真正插入成功才算 installed；失败（如 mainMenu 尚为 nil）时保留
            // 可重试状态，调用方可稍后再调 install() 一次
            if (installSucceeded.get()) {
                installed = true
            } else {
                println("AppMenu: failed to install app menu items (main menu not ready or insertion failed)")
            }
        } catch (t: Throwable) {
            // Not macOS, or the AppKit/JNA internals changed: run without the
            // app-menu items rather than breaking startup.
            println("AppMenu: failed to install app menu items: $t")
        }
    }

    private fun ensureExecutor() {
        if (executor != null) return
        val allocate = objcLib.getFunction("objc_allocateClassPair")
        val register = objcLib.getFunction("objc_registerClassPair")
        val addMethod = objcLib.getFunction("class_addMethod")

        val execClass = allocate.invokePointer(
            arrayOf<Any>(cls("NSObject"), "PixivShaftMenuExecutor", 0)
        )!!

        val cbProfile = object : MenuActionCallback {
            override fun invoke(self: Pointer?, cmd: Pointer?, sender: Pointer?) {
                profileAction.get()?.invoke()
            }
        }
        val cbSettings = object : MenuActionCallback {
            override fun invoke(self: Pointer?, cmd: Pointer?, sender: Pointer?) {
                settingsAction.get()?.invoke()
            }
        }
        val cbTask = object : MenuActionCallback {
            override fun invoke(self: Pointer?, cmd: Pointer?, sender: Pointer?) {
                try {
                    insertItems()
                    installSucceeded.set(true)
                } catch (_: Throwable) {
                }
            }
        }
        profileCallback = cbProfile
        settingsCallback = cbSettings
        taskCallback = cbTask

        addMethod.invokePointer(
            arrayOf<Any>(execClass, sel("javaProfileClicked:"),
                CallbackReference.getFunctionPointer(cbProfile), OBJC_VOID_1ARG))
        addMethod.invokePointer(
            arrayOf<Any>(execClass, sel("javaSettingsClicked:"),
                CallbackReference.getFunctionPointer(cbSettings), OBJC_VOID_1ARG))
        addMethod.invokePointer(
            arrayOf<Any>(execClass, sel("javaInstallTask:"),
                CallbackReference.getFunctionPointer(cbTask), OBJC_VOID_1ARG))
        register.invokePointer(arrayOf<Any>(execClass))

        executor = msgSend(msgSend(execClass, "alloc"), "init")
    }

    /** Runs on the AppKit main thread. */
    private fun insertItems() {
        val nsApp = msgSend(cls("NSApplication"), "sharedApplication")
        val mainMenu = msgSend(nsApp, "mainMenu")
        val appMenuItem = msgSend(mainMenu, "itemAtIndex:", 0)
        val appMenu = msgSend(appMenuItem, "submenu")

        // 上一次重试可能已在两次 insertItem: 之间失败（我的 已插入而 设置 未插入），
        // 先移除已插入的残留项，使重试幂等、不产生重复菜单项。
        insertedProfileItem?.let { msgSendVoid(appMenu, "removeItem:", it) }
        insertedSettingsItem?.let { msgSendVoid(appMenu, "removeItem:", it) }
        insertedProfileItem = null
        insertedSettingsItem = null

        val itemProfile = newMenuItem("我的", sel("javaProfileClicked:"), "4")
        val itemSettings = newMenuItem("设置", sel("javaSettingsClicked:"), ",")
        msgSendVoid(itemProfile, "setTarget:", executor)
        msgSendVoid(itemSettings, "setTarget:", executor)

        // Insert right after "About".
        msgSendVoid(appMenu, "insertItem:atIndex:", itemProfile, 1)
        insertedProfileItem = itemProfile
        msgSendVoid(appMenu, "insertItem:atIndex:", itemSettings, 2)
        insertedSettingsItem = itemSettings
    }

    private fun newMenuItem(title: String, action: Pointer, keyEquivalent: String): Pointer {
        val item = msgSend(cls("NSMenuItem"), "alloc")
        val itemInit = msgSend(
            item,
            "initWithTitle:action:keyEquivalent:",
            makeNSString(title),
            action,
            makeNSString(keyEquivalent),
        )
        msgSendVoid(itemInit, "setKeyEquivalentModifierMask:", MODIFIER_CMD)
        return itemInit
    }
}
