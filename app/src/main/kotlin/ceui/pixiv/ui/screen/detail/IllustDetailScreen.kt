package ceui.pixiv.ui.screen.detail

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen

/**
 * Stub — will be replaced by Task 6 with the real detail implementation.
 */
class IllustDetailScreen(private val illustId: Long) : Screen {

    @Composable
    override fun Content() {
        Text("Detail page for illust #$illustId")
    }
}
