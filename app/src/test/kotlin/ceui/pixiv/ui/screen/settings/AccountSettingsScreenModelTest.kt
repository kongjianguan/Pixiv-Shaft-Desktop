package ceui.pixiv.ui.screen.settings

import ceui.loxia.KUserState
import ceui.loxia.ProfileBean
import ceui.loxia.SelfProfile
import ceui.loxia.User
import ceui.loxia.UserDetailResponse
import ceui.pixiv.testutil.fakeApi
import ceui.pixiv.testutil.fakeClient
import ceui.pixiv.testutil.resumeSuspend
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.SelfUserIdResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSettingsScreenModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        SelfUserIdResolver.clear()
    }

    @AfterEach
    fun tearDown() {
        SelfUserIdResolver.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `loads self profile and profile detail`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getSelfProfile" -> resumeSuspend(
                    args,
                    SelfProfile(
                        profile = User(
                            id = 7L,
                            user_id = 7L,
                            name = "Me",
                            pixiv_id = "me",
                            is_premium = true,
                        ),
                        user_state = KUserState(),
                    ),
                )
                "getUserDetail" -> resumeSuspend(
                    args,
                    UserDetailResponse(
                        profile = ProfileBean(
                            total_illusts = 12,
                            total_novels = 3,
                            total_follow_users = 8,
                        ),
                    ),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }

        val model = AccountSettingsScreenModel(client = fakeClient(api))

        awaitUntil { model.profileDetailState.value is UiState.Success }

        val self = (model.profileState.value as UiState.Success).data
        val detail = (model.profileDetailState.value as UiState.Success).data
        assertEquals("Me", self.profile.name)
        assertEquals(12, detail.total_illusts)
        assertEquals(3, detail.total_novels)
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }
}
