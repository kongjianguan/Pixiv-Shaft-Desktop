package ceui.pixiv.platform

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage

/**
 * Manages macOS system tray icon with Show/Exit menu.
 * Call [setup] after window creation, [remove] before exit.
 *
 * Uses a template image: black on transparent.
 * macOS automatically tints it to match light/dark menu bar.
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null

    fun setup(onShow: () -> Unit, onExit: () -> Unit) {
        if (!SystemTray.isSupported()) return

        // Enable template image rendering globally for macOS tray icons.
        // This makes the icon automatically tint to match light/dark menu bar.
        enableTemplateImages()

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

    /**
     * Enable template image rendering for all AWT tray icons on macOS.
     *
     * AWT's macOS implementation has a static flag `useTemplateImages` in
     * [sun.lwawt.macosx.CTrayIcon]. When true, every tray icon is rendered as
     * a template image: black pixels are tinted by the system to match the
     * current menu bar appearance (white on dark menu bar, dark on light menu
     * bar), and transparent areas stay transparent.
     *
     * The previous per-icon approach (calling [NSImage setTemplate:]) no longer
     * works on modern JDKs because the peer no longer exposes the native NSImage
     * field. Changing the static flag is the reliable way.
     */
    private fun enableTemplateImages() {
        try {
            // 1. Set the system property that CTrayIcon's static initializer reads.
            //    This must happen before the class is loaded to take effect.
            System.setProperty("apple.awt.enableTemplateImages", "true")

            // 2. Load the class; if it wasn't loaded before, the static initializer
            //    will now pick up the property above.
            val clazz = Class.forName("sun.lwawt.macosx.CTrayIcon")

            // 3. Fallback: if the class was already loaded with the wrong value,
            //    forcibly update the static field. This requires stripping FINAL.
            val field = clazz.getDeclaredField("useTemplateImages")
            field.isAccessible = true
            if (!field.getBoolean(null)) {
                val modifiersField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
                modifiersField.isAccessible = true
                modifiersField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
                field.setBoolean(null, true)
            }
        } catch (_: Throwable) {
            // Not on macOS, or internal API changed — fall back to non-template icon.
        }
    }

    private fun createTrayImage(): Image {
        // Render at a high resolution so macOS can scale down smoothly.
        // Menu bar icons are displayed at ~18x18 points (36x36 px on Retina);
        // starting from 128x128 gives the system enough pixels for crisp edges.
        val size = 128
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        // Black filled circle (macOS will tint it)
        g.color = Color.BLACK
        // Leave a ~12 px margin so the circle isn't crammed against the edges.
        val margin = 19
        g.fillOval(margin, margin, size - margin * 2, size - margin * 2)

        // Cut out "P" using Clear composite
        g.composite = AlphaComposite.Clear
        // Use a heavy-weight font so the transparent "P" cutout looks bold.
        // TextAttribute.WEIGHT_HEAVY is more explicit than Font.BOLD and survives
        // fallback when "SF Pro Text" isn't installed.
        val baseFont = Font("SF Pro Text", Font.BOLD, 72)
        g.font = baseFont.deriveFont(
            java.util.Collections.singletonMap<TextAttribute, Any>(
                TextAttribute.WEIGHT,
                TextAttribute.WEIGHT_HEAVY
            )
        )
        val frc = g.fontRenderContext
        val gv = g.font.createGlyphVector(frc, "P")
        val outline = gv.outline
        val bounds = outline.bounds

        val x = (size - bounds.width) / 2 - bounds.x
        val y = (size - bounds.height) / 2 - bounds.y

        // 几何居中后 P 看起来略偏左，+4 是视觉修正。
        // 字形的视觉重心与 bounding box 中心不一定重合，这种小偏移对模板图标渲染影响可以忽略。
        g.fill(gv.getOutline(x.toFloat() + 4, y.toFloat()))

        g.dispose()
        return img
    }
}
