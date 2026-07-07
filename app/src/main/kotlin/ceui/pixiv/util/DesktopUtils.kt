package ceui.pixiv.util

import java.awt.Desktop
import java.net.URI

fun openInBrowser(url: String) {
    try {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(URI(url))
        }
    } catch (e: Exception) {
        println("Failed to open browser: ${e.message}")
    }
}
