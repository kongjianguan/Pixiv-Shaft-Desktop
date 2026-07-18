package ceui.pixiv.ui.screen.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ceui.pixiv.ui.novel.NovelReaderStyle
import ceui.pixiv.ui.novel.NovelReaderThemePreset

@Composable
fun NovelReaderSettingsPanel(
    style: NovelReaderStyle,
    themeId: String,
    onThemeChange: (String) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onParagraphSpacingChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 380.dp, max = 520.dp).heightIn(max = 680.dp),
            shape = MaterialTheme.shapes.large,
            color = style.background,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("阅读设置", style = MaterialTheme.typography.headlineSmall, color = style.text)
                    TextButton(onClick = onDismiss) { Text("完成") }
                }

                Text("字号：${style.fontSizeSp} sp", color = style.text)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = { onFontSizeChange(style.fontSizeSp - 1) },
                        enabled = style.fontSizeSp > 14,
                    ) { Text("A−") }
                    Slider(
                        value = style.fontSizeSp.toFloat(),
                        onValueChange = { onFontSizeChange(it.toInt()) },
                        valueRange = 14f..30f,
                        steps = 15,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onFontSizeChange(style.fontSizeSp + 1) },
                        enabled = style.fontSizeSp < 30,
                    ) { Text("A+") }
                }

                Text("行距：${"%.1f".format(style.lineSpacing)} 倍", color = style.text)
                Slider(
                    value = style.lineSpacing,
                    onValueChange = onLineSpacingChange,
                    valueRange = 1.2f..2.6f,
                    steps = 6,
                )

                Text("段落间距：${style.paragraphSpacing.value.toInt()} dp", color = style.text)
                Slider(
                    value = style.paragraphSpacing.value,
                    onValueChange = { onParagraphSpacingChange(it.toInt()) },
                    valueRange = 4f..28f,
                    steps = 5,
                )

                Text("阅读主题", color = style.text)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NovelReaderThemePreset.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { preset ->
                                FilterChip(
                                    selected = themeId == preset.id,
                                    onClick = { onThemeChange(preset.id) },
                                    label = { Text(preset.label) },
                                )
                            }
                        }
                    }
                }

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("完成")
                }
            }
        }
    }
}
