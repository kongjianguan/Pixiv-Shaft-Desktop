package ceui.pixiv.ui.screen.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.ProfileBean
import ceui.loxia.User
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.UiState

class UserDetailScreen(private val userId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { UserDetailScreenModel(userId) }
        val userState by screenModel.userState.collectAsState()
        val illustsState by screenModel.illustsState.collectAsState()
        val bookmarksState by screenModel.bookmarksState.collectAsState()
        val isFollowing by screenModel.isFollowing.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var selectedTab by remember { mutableStateOf(0) }

        val userName = (userState as? UiState.Success)?.data?.first?.name ?: "User #$userId"

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(userName) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Header
                item {
                    when (val s = userState) {
                        is UiState.Loading -> LoadingView(modifier = Modifier.height(120.dp))
                        is UiState.Error -> ErrorView(s.message, {}, Modifier.height(120.dp))
                        is UiState.Success -> {
                            val (user, profile) = s.data
                            UserHeader(
                                user = user,
                                profile = profile,
                                isFollowing = isFollowing,
                                onToggleFollow = { screenModel.toggleFollow(it) }
                            )
                        }
                    }
                }

                // Tabs
                item {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Illusts") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Bookmarks") }
                        )
                    }
                }

                // Tab content
                when (selectedTab) {
                    0 -> when (val s = illustsState) {
                        is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                        is UiState.Error -> item { ErrorView(s.message, {}, Modifier.height(200.dp)) }
                        is UiState.Success -> {
                            if (s.data.isEmpty()) {
                                item { EmptyView("No illusts", Modifier.height(200.dp)) }
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
                    1 -> when (val s = bookmarksState) {
                        is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                        is UiState.Error -> item { ErrorView(s.message, {}, Modifier.height(200.dp)) }
                        is UiState.Success -> {
                            if (s.data.isEmpty()) {
                                item { EmptyView("No bookmarks", Modifier.height(200.dp)) }
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
            }
        }
    }
}

@Composable
private fun UserHeader(
    user: User,
    profile: ProfileBean,
    isFollowing: Boolean?,
    onToggleFollow: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UserAvatar(url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium, size = 64)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = user.name ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                Text(text = "@${user.account ?: user.id}", style = MaterialTheme.typography.labelSmall)
                if (user.is_premium == true) {
                    Text("Premium", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (isFollowing != null) {
                if (isFollowing) {
                    Button(onClick = { onToggleFollow("public") }) { Text("Following") }
                } else {
                    OutlinedButton(onClick = { onToggleFollow("public") }) { Text("Follow") }
                }
            }
        }

        // Stats
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Illusts: ${profile.total_illusts}", style = MaterialTheme.typography.labelMedium)
            Text("Bookmarks: ${profile.total_illust_bookmarks_public}", style = MaterialTheme.typography.labelMedium)
            Text("Following: ${profile.total_follow_users}", style = MaterialTheme.typography.labelMedium)
        }

        // Optional info
        if (!profile.job.isNullOrEmpty() || !profile.region.isNullOrEmpty() || !profile.twitter_account.isNullOrEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!profile.job.isNullOrEmpty()) Text("Job: ${profile.job}", style = MaterialTheme.typography.labelSmall)
                if (!profile.region.isNullOrEmpty()) Text("Region: ${profile.region}", style = MaterialTheme.typography.labelSmall)
                if (!profile.twitter_account.isNullOrEmpty()) Text("Twitter: @${profile.twitter_account}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
