package ceui.pixiv.ui.util

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.MarkedNovelItem
import ceui.lisa.models.NovelBean
import ceui.lisa.models.WatchlistMangaItem
import ceui.lisa.models.WatchlistNovelItem
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.pixiv.di.AppContainer
import ceui.pixiv.store.SettingsStore
import kotlinx.coroutines.launch

/** 作品是否 R18：x_restrict > 0 是作者显式标记（1=R-18、2=R-18G）。 */
fun isR18(item: Any?): Boolean = when (item) {
    is Illust -> (item.x_restrict ?: 0) > 0
    is Novel -> (item.x_restrict ?: 0) > 0
    is IllustsBean -> item.x_restrict > 0
    is NovelBean -> item.x_restrict > 0
    is WatchlistMangaItem -> (item.x_restrict ?: 0) > 0
    is WatchlistNovelItem -> (item.x_restrict ?: 0) > 0
    is MarkedNovelItem -> runCatching { isR18(item.novel) }.getOrDefault(false)
    else -> false
}

/**
 * R18 全局开关关闭时过滤掉 R18 作品（界面上不出现 R18 内容）。
 * 在各 ScreenModel 发布 UiState.Success 时调用，Pager 保留完整数据、只过滤发布出去的列表。
 * [showR18] 默认读全局设置；测试可显式传入，避免依赖 AppContainer 单例。
 */
fun <T> visibleItems(items: List<T>, showR18: Boolean = AppContainer.settingsStore.isShowR18): List<T> =
    if (showR18) items
    else items.filterNot { isR18(it) }

/**
 * 开关关闭时，原始列表中是否有被过滤掉的 R18 作品。
 * 供空态提示区分「真没有数据」与「数据被 R18 过滤隐藏」，避免误导用户。
 */
fun <T> hasHiddenR18(raw: List<T>, showR18: Boolean = AppContainer.settingsStore.isShowR18): Boolean =
    !showR18 && raw.any { isR18(it) }

/**
 * 小说列表发布前过滤：R18 开关 + 服务端 visible=false（列表接口间歇返回，
 * 详情页会 crash）。Recommend / Dynamic / Ranking 的小说流共用同一规则。
 */
fun visibleNovels(items: List<Novel>, showR18: Boolean = AppContainer.settingsStore.isShowR18): List<Novel> =
    visibleItems(items.filter { it.visible != false }, showR18)

/**
 * 小说标记列表发布前过滤：标记接口内嵌的是 [NovelBean]，其 visible=false 同样不能进入
 * 详情页；缺少内嵌小说数据的条目也作为不可展示内容跳过。
 */
fun visibleMarkedNovels(
    items: List<MarkedNovelItem>,
    showR18: Boolean = AppContainer.settingsStore.isShowR18,
): List<MarkedNovelItem> = visibleItems(
    items.filter { item ->
        runCatching {
            item.novel_marker
            item.novel.isVisible
        }.getOrDefault(false)
    },
    showR18,
)

/**
 * 监听 R18 全局开关变化；切换后回调 [onChanged]，用于各 ScreenModel 即时重新过滤
 * 已加载的内容（Pager 保留完整数据，重新过一遍 visibleItems 即可）。
 * 必须在 ScreenModel 的 init 中调用（此时各状态字段已初始化）。
 */
fun ScreenModel.observeR18Toggle(
    onChanged: () -> Unit,
    settingsStore: SettingsStore = AppContainer.settingsStore,
) {
    screenModelScope.launch {
        settingsStore.isShowR18Flow.collect { onChanged() }
    }
}
