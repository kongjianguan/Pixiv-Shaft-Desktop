package ceui.pixiv.platform

import java.awt.Color
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Manages macOS system tray icon with Show/Exit menu.
 * Call [setup] after window creation, [remove] before exit.
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null

    fun setup(onShow: () -> Unit, onExit: () -> Unit) {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()

        val popup = PopupMenu().apply {
            add(MenuItem("Show").apply {
                addActionListener { onShow() }
            })
            addSeparator()
            add(MenuItem("Exit").apply {
                addActionListener { onExit() }
            })
        }

        val image = createTrayImage()
        trayIcon = TrayIcon(image, "Pixiv Shaft", popup).apply {
            isImageAutoSize = true
            addActionListener { onShow() }
        }

        tray.add(trayIcon)
    }

    fun remove() {
        val icon = trayIcon ?: return
        if (SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon)
        }
        trayIcon = null
    }

    private fun createTrayImage(): Image {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0x00, 0x96, 0xC7) // Shaft blue
        g.fillOval(0, 0, size, size)
        g.color = Color.WHITE
        g.drawString("P", 4, 13)
        g.dispose()
        return img
    }
}
