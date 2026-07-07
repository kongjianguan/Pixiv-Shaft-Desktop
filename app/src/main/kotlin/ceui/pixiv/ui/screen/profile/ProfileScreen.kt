package ceui.pixiv.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.settings.SettingsScreen
import ceui.pixiv.ui.state.UiState

class ProfileScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { ProfileScreenModel() }
        val profileState by screenModel.profileState.collectAsState()
        val profileDetailState by screenModel.profileDetailState.collectAsState()
        val bookmarksState by screenModel.bookmarksState.collectAsState()
        val history by screenModel.history.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var selectedTab by remember { mutableStateOf(0) }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { screenModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Header: avatar + name + stats + settings button
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (val s = profileState) {
                            is UiState.Loading -> {
                                UserAvatar(url = null, size = 64)
                            }
                            is UiState.Error -> {
                                UserAvatar(url = null, size = 64)
                            }
                            is UiState.Success -> {
                                val user = s.data.profile
                                UserAvatar(
                                    url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium,
                                    size = 64
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.name ?: "Unknown",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "@${user.pixiv_id ?: user.account ?: user.user_id}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    if (user.is_premium == true) {
                                        Text(
                                            text = "Premium",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { navigator.push(SettingsScreen()) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }

                // Stats row
                item {
                    profileDetailState.let { state ->
                        if (state is UiState.Success) {
                            val profile = state.data
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text("Illusts: ${profile.total_illusts}", style = MaterialTheme.typography.labelMedium)
                                Text("Bookmarks: ${profile.total_illust_bookmarks_public}", style = MaterialTheme.typography.labelMedium)
                            }
                            if (!profile.job.isNullOrEmpty() || !profile.region.isNullOrEmpty() || !profile.twitter_account.isNullOrEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (!profile.job.isNullOrEmpty()) {
                                        Text("Job: ${profile.job}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (!profile.region.isNullOrEmpty()) {
                                        Text("Region: ${profile.region}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (!profile.twitter_account.isNullOrEmpty()) {
                                        Text("Twitter: @${profile.twitter_account}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                // Tab row: Bookmarks / History
                item {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Bookmarks") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("History") }
                        )
                    }
                }

                // Tab content
                when (selectedTab) {
                    0 -> {
                        when (val s = bookmarksState) {
                            is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                            is UiState.Error -> item {
                                ErrorView(s.message, {}, Modifier.height(200.dp))
                            }
                            is UiState.Success -> {
                                if (s.data.isEmpty()) {
                                    item { Text("No bookmarks", modifier = Modifier.padding(16.dp)) }
                                } else {
                                    items(s.data, key = { it.id }) { illust ->
                                        IllustCard(
                                            illust = illust,
                                            onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        if (history.isEmpty()) {
                            item { Text("No browse history", modifier = Modifier.padding(16.dp)) }
                        } else {
                            items(history, key = { it.id }) { illust ->
                                IllustCard(
                                    illust = illust,
                                    onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
