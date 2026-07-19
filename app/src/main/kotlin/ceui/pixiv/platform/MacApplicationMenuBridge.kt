package ceui.pixiv.platform

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

object MacApplicationMenuBridge {

    private const val profileMenuTag = 0x5052564C
    private const val settingsMenuTag = 0x53455454
    private const val profileActionSelectorName = "pixivProfileMenuAction:"
    private const val settingsActionSelectorName = "pixivSettingsMenuAction:"
    private const val targetClassName = "PixivShaftMenuTarget"
    private const val commandModifierMask = 1L shl 20

    private val profileAction = AtomicReference<(() -> Unit)?>(null)
    private val settingsAction = AtomicReference<(() -> Unit)?>(null)
    private var actionCallback: MenuActionCallback? = null
    private var targetObject: Pointer? = null
    private var profileMenu: Pointer? = null
    private var profileMenuItem: Pointer? = null
    private var settingsMenu: Pointer? = null
    private var settingsMenuItem: Pointer? = null

    private val profileActionSelector by lazy { selector(profileActionSelectorName) }
    private val settingsActionSelector by lazy { selector(settingsActionSelectorName) }

    fun installProfileItem(onClick: () -> Unit): Boolean {
        if (!isMacOs()) return false

        profileAction.set(onClick)
        if (profileMenuItem != null) return true
        return installItem(
            title = "我的",
            keyEquivalent = "4",
            tag = profileMenuTag,
            actionSelectorName = profileActionSelectorName,
        ) { menu, item ->
            profileMenu = menu
            profileMenuItem = item
        }
    }

    fun installSettingsItem(onClick: () -> Unit): Boolean {
        if (!isMacOs()) return false

        settingsAction.set(onClick)
        if (settingsMenuItem != null) return true
        return installItem(
            title = "设置",
            keyEquivalent = "",
            tag = settingsMenuTag,
            actionSelectorName = settingsActionSelectorName,
        ) { menu, item ->
            settingsMenu = menu
            settingsMenuItem = item
        }
    }

    fun removeProfileItem() {
        removeItem(profileMenu, profileMenuItem)
        profileMenu = null
        profileMenuItem = null
        profileAction.set(null)
    }

    fun removeSettingsItem() {
        removeItem(settingsMenu, settingsMenuItem)
        settingsMenu = null
        settingsMenuItem = null
        settingsAction.set(null)
    }

    private fun installItem(
        title: String,
        keyEquivalent: String,
        tag: Int,
        actionSelectorName: String,
        onInstalled: (Pointer, Pointer) -> Unit,
    ): Boolean = try {
        val applicationMenu = findApplicationMenu() ?: return false
        val target = ensureTarget()
        val actionSelector = selector(actionSelectorName)
        val itemClass = getClass("NSMenuItem") ?: return false
        val item = sendPointer(itemClass, "alloc") ?: return false
        val initializedItem = sendPointer(
            item,
            "initWithTitle:action:keyEquivalent:",
            nsString(title),
            actionSelector,
            nsString(keyEquivalent),
        ) ?: return false

        sendVoid(initializedItem, "setTarget:", target)
        if (keyEquivalent.isNotEmpty()) {
            sendVoid(initializedItem, "setKeyEquivalentModifierMask:", commandModifierMask)
        }
        sendVoid(initializedItem, "setTag:", tag.toLong())

        val insertIndex = firstSeparatorIndex(applicationMenu)
        sendVoid(applicationMenu, "insertItem:atIndex:", initializedItem, insertIndex.toLong())
        onInstalled(applicationMenu, initializedItem)
        true
    } catch (_: Throwable) {
        false
    }

    private fun removeItem(menu: Pointer?, item: Pointer?) {
        if (menu == null || item == null) return
        try {
            sendVoid(menu, "removeItem:", item)
        } catch (_: Throwable) {
        }
    }

    private fun findApplicationMenu(): Pointer? {
        val application = sendPointer(getClass("NSApplication"), "sharedApplication") ?: return null
        val mainMenu = sendPointer(application, "mainMenu") ?: return null
        val item = sendPointer(mainMenu, "itemAtIndex:", 0L) ?: return null
        return sendPointer(item, "submenu")
    }

    private fun firstSeparatorIndex(menu: Pointer): Int {
        val itemCount = sendLong(menu, "numberOfItems").toInt()
        for (index in 1 until itemCount) {
            val item = sendPointer(menu, "itemAtIndex:", index.toLong()) ?: continue
            if (sendLong(item, "isSeparatorItem") != 0L) return index
        }
        return minOf(1, itemCount)
    }

    private fun ensureTarget(): Pointer {
        targetObject?.let { return it }

        val objc = objcLibrary
        val baseClass = getClass("NSObject") ?: error("NSObject is unavailable")
        val existingTargetClass = getClass(targetClassName)
        val targetClass = existingTargetClass ?: run {
            objc.getFunction("objc_allocateClassPair")
                .invokePointer(arrayOf(baseClass, targetClassName, 0L))
                ?: error("Unable to allocate Objective-C menu target class")
        }

        if (actionCallback == null) {
            actionCallback = object : MenuActionCallback {
                override fun invoke(receiver: Pointer, selector: Pointer, sender: Pointer?) {
                    val action = when (selector) {
                        profileActionSelector -> profileAction.get()
                        settingsActionSelector -> settingsAction.get()
                        else -> null
                    } ?: return
                    SwingUtilities.invokeLater(action)
                }
            }
        }

        val callbackPointer = CallbackReference.getFunctionPointer(actionCallback)
        objc.getFunction("class_addMethod").invokeInt(
            arrayOf(targetClass, profileActionSelector, callbackPointer, "v@:@")
        )
        objc.getFunction("class_addMethod").invokeInt(
            arrayOf(targetClass, settingsActionSelector, callbackPointer, "v@:@")
        )
        if (existingTargetClass == null) {
            objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(targetClass))
        }

        val allocatedObject = sendPointer(targetClass, "alloc") ?: error("Unable to allocate menu target")
        val initializedObject = sendPointer(allocatedObject, "init") ?: error("Unable to initialize menu target")
        targetObject = initializedObject
        return initializedObject
    }

    private fun nsString(value: String): Pointer {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val memory = Memory((bytes.size + 1).toLong())
        memory.write(0, bytes, 0, bytes.size)
        memory.setByte(bytes.size.toLong(), 0.toByte())
        val stringClass = getClass("NSString") ?: error("NSString is unavailable")
        return sendPointer(stringClass, "stringWithUTF8String:", memory)
            ?: error("Unable to create NSString")
    }

    private fun getClass(name: String): Pointer? =
        objcLibrary.getFunction("objc_getClass").invokePointer(arrayOf(name))

    private fun selector(name: String): Pointer =
        objcLibrary.getFunction("sel_registerName").invokePointer(arrayOf(name))
            ?: error("Unable to register selector: $name")

    private fun sendPointer(receiver: Pointer?, selectorName: String, vararg arguments: Any): Pointer? {
        if (receiver == null) return null
        return sendPointer(receiver, selector(selectorName), *arguments)
    }

    private fun sendPointer(receiver: Pointer, selector: Pointer, vararg arguments: Any): Pointer? {
        val callArguments = buildCallArguments(receiver, selector, arguments)
        return objcLibrary.getFunction("objc_msgSend").invokePointer(callArguments)
    }

    private fun sendVoid(receiver: Pointer, selectorName: String, vararg arguments: Any) {
        val callArguments = buildCallArguments(receiver, selector(selectorName), arguments)
        objcLibrary.getFunction("objc_msgSend").invokeVoid(callArguments)
    }

    private fun buildCallArguments(
        receiver: Pointer,
        selector: Pointer,
        arguments: Array<out Any>,
    ): Array<Any> = Array(2 + arguments.size) { index ->
        when (index) {
            0 -> receiver
            1 -> selector
            else -> arguments[index - 2]
        }
    }

    private fun sendLong(receiver: Pointer, selectorName: String): Long =
        objcLibrary.getFunction("objc_msgSend")
            .invokeLong(arrayOf(receiver, selector(selectorName)))

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").contains("mac", ignoreCase = true)

    private val objcLibrary by lazy { NativeLibrary.getInstance("objc") }

    private interface MenuActionCallback : Callback {
        fun invoke(receiver: Pointer, selector: Pointer, sender: Pointer?)
    }
}
