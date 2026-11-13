package com.lifelink.app

import androidx.lifecycle.ViewModelStore
import com.lifelink.app.domain.ChatMessage
import com.lifelink.app.domain.ContactShare
import com.lifelink.app.domain.Conversation
import com.lifelink.app.domain.DonorMap
import com.lifelink.app.domain.DonorMapVisibility
import com.lifelink.app.domain.MatchedDonorLocation
import com.lifelink.app.domain.PrivacyRepository
import com.lifelink.app.feature.privacy.PrivacyAction
import com.lifelink.app.feature.privacy.PrivacyViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val repository = ConversationRepositoryFake()
    private lateinit var viewModel: PrivacyViewModel

    /** Installs the test dispatcher and retains the view model for lifecycle cleanup. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = PrivacyViewModel(repository)
        store.put("privacy", viewModel)
    }

    /** Cancels view-model work and restores the main dispatcher after each test. */
    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    /** Prevents an older conversation-list response from replacing a newer successful refresh. */
    @Test
    fun stale_conversation_list_result_cannot_overwrite_newer_result() = runTest {
        val olderResponse = CompletableDeferred<Result<List<Conversation>>>()
        var calls = 0
        repository.conversationList = {
            calls++
            if (calls == 1) {
                olderResponse.await()
            } else {
                Result.success(listOf(conversation("chat-new", "request-new", "donor-new")))
            }
        }

        viewModel.onAction(PrivacyAction.LoadConversations)
        viewModel.onAction(PrivacyAction.LoadConversations)

        assertEquals(listOf("chat-new"), viewModel.state.value.conversations.map { it.conversationId })
        assertFalse(viewModel.state.value.conversationsLoading)

        olderResponse.complete(
            Result.success(listOf(conversation("chat-stale", "request-stale", "donor-stale"))),
        )

        assertEquals(listOf("chat-new"), viewModel.state.value.conversations.map { it.conversationId })
        assertFalse(viewModel.state.value.conversationsLoading)
        assertNull(viewModel.state.value.conversationsError)
    }

    /** Prevents an older failed conversation-list request from surfacing an error after a newer success. */
    @Test
    fun stale_conversation_list_failure_cannot_replace_newer_success() = runTest {
        val olderResponse = CompletableDeferred<Result<List<Conversation>>>()
        var calls = 0
        repository.conversationList = {
            calls++
            if (calls == 1) {
                olderResponse.await()
            } else {
                Result.success(listOf(conversation("chat-new", "request-new", "donor-new")))
            }
        }

        viewModel.onAction(PrivacyAction.LoadConversations)
        viewModel.onAction(PrivacyAction.LoadConversations)
        olderResponse.complete(Result.failure(IllegalStateException("Stale network failure")))

        assertEquals(listOf("chat-new"), viewModel.state.value.conversations.map { it.conversationId })
        assertFalse(viewModel.state.value.conversationsLoading)
        assertNull(viewModel.state.value.conversationsError)
    }

    /** Prevents an older, slower map refresh from overwriting the latest successful result. */
    @Test
    fun stale_map_refresh_result_cannot_overwrite_newer_result() = runTest {
        val olderResponse = CompletableDeferred<Result<DonorMap>>()
        var mapCalls = 0
        repository.map = {
            mapCalls++
            if (mapCalls == 1) {
                olderResponse.await()
            } else {
                Result.success(DonorMap("new", approximateOnly = true, areas = emptyList()))
            }
        }

        viewModel.onAction(PrivacyAction.LoadMap)
        viewModel.onAction(PrivacyAction.LoadMap)

        assertEquals("new", viewModel.state.value.map?.generatedAt)
        assertFalse(viewModel.state.value.mapLoading)

        olderResponse.complete(Result.success(DonorMap("stale", approximateOnly = true, areas = emptyList())))

        assertEquals("new", viewModel.state.value.map?.generatedAt)
        assertFalse(viewModel.state.value.mapLoading)
        assertNull(viewModel.state.value.mapError)
    }

    /** Prevents a stale failed refresh from replacing a newer successful map with an error state. */
    @Test
    fun stale_map_refresh_failure_cannot_replace_newer_success() = runTest {
        val olderResponse = CompletableDeferred<Result<DonorMap>>()
        var mapCalls = 0
        repository.map = {
            mapCalls++
            if (mapCalls == 1) {
                olderResponse.await()
            } else {
                Result.success(DonorMap("new", approximateOnly = true, areas = emptyList()))
            }
        }

        viewModel.onAction(PrivacyAction.LoadMap)
        viewModel.onAction(PrivacyAction.LoadMap)
        olderResponse.complete(Result.failure(IllegalStateException("Stale network failure")))

        assertEquals("new", viewModel.state.value.map?.generatedAt)
        assertFalse(viewModel.state.value.mapLoading)
        assertNull(viewModel.state.value.mapError)
    }

    /** Removes old donor pins when the latest refresh fails instead of displaying stale locations. */
    @Test
    fun failed_map_refresh_clears_previously_loaded_donor_areas() = runTest {
        repository.map = {
            Result.success(DonorMap("previous", approximateOnly = true, areas = emptyList()))
        }
        viewModel.onAction(PrivacyAction.LoadMap)
        assertEquals("previous", viewModel.state.value.map?.generatedAt)

        repository.map = { Result.failure(IllegalStateException("Network unavailable")) }
        viewModel.onAction(PrivacyAction.LoadMap)

        assertNull(viewModel.state.value.map)
        assertFalse(viewModel.state.value.mapLoading)
        assertEquals("The donor map could not be loaded.", viewModel.state.value.mapError)
    }

    /** Clears the old donor map immediately and refreshes it after a donor opts out of visibility. */
    @Test
    fun hiding_donor_clears_stale_map_while_refreshing_and_then_loads_fresh_map() = runTest {
        val previousMap = DonorMap("before-hide", approximateOnly = true, areas = emptyList())
        repository.map = { Result.success(previousMap) }
        viewModel.onAction(PrivacyAction.LoadMap)
        assertEquals("before-hide", viewModel.state.value.map?.generatedAt)

        val refreshedMap = CompletableDeferred<Result<DonorMap>>()
        var mapCalls = 0
        repository.map = {
            mapCalls++
            refreshedMap.await()
        }

        viewModel.onAction(PrivacyAction.SetMapVisibility(mapVisible = false, exactLocationSharingEnabled = false))

        assertEquals(1, mapCalls)
        assertNull(viewModel.state.value.map)
        assertTrue(viewModel.state.value.mapLoading)
        assertEquals(false, viewModel.state.value.visibility?.mapVisible)

        refreshedMap.complete(Result.success(DonorMap("after-hide", approximateOnly = true, areas = emptyList())))

        assertEquals("after-hide", viewModel.state.value.map?.generatedAt)
        assertFalse(viewModel.state.value.mapLoading)
        assertNull(viewModel.state.value.mapError)
    }

    /** Verifies that opening a chat loads history and contact shares for the returned conversation ID. */
    @Test
    fun open_action_loads_the_requested_conversation_messages_and_contact_shares() = runTest {
        viewModel.onAction(PrivacyAction.OpenConversation("request-old", "donor-old"))

        assertEquals(listOf("request-old" to "donor-old"), repository.opens)
        assertEquals("chat-old", viewModel.state.value.conversationId)
        assertEquals(repository.loadedMessages, viewModel.state.value.messages)
        assertEquals(repository.loadedShares, viewModel.state.value.contactShares)
        assertEquals(listOf("chat-old"), repository.messageReads)
        assertEquals(listOf("chat-old"), repository.shareReads)
        assertFalse(viewModel.state.value.chatLoading)
        assertNull(viewModel.state.value.message)
    }

    /** Keeps prior chat data and write targets cleared while a deferred open is pending and after it fails. */
    @Test
    fun opening_another_chat_clears_old_content_and_disables_writes_while_waiting() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        viewModel.onAction(PrivacyAction.ShareContact("phone", "555-0100"))
        assertTrue(viewModel.state.value.messages.isNotEmpty())
        assertTrue(viewModel.state.value.contactShares.isNotEmpty())
        assertEquals("Your phone was shared and recorded.", viewModel.state.value.message)
        repository.shares.clear()
        val pending = CompletableDeferred<Result<Conversation>>()
        repository.open = { _, _ -> pending.await() }

        viewModel.openConversation("request-new", "donor-new")

        assertTrue(viewModel.state.value.chatLoading)
        assertNoConversationContent()
        assertNull(viewModel.state.value.message)
        viewModel.onAction(PrivacyAction.SendMessage("Must not reach the previous donor"))
        viewModel.onAction(PrivacyAction.ShareContact("email", "test@example.invalid"))
        assertTrue(repository.sends.isEmpty())
        assertTrue(repository.shares.isEmpty())

        pending.complete(Result.failure(IllegalStateException("No longer matched")))
        assertFalse(viewModel.state.value.chatLoading)
        assertNoConversationContent()
    }

    /** Ensures a failed switch cannot send messages or shared contact details to the previous recipient. */
    @Test
    fun failed_open_does_not_send_messages_or_contact_details_to_the_previous_chat() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        repository.open = { _, _ -> Result.failure(IllegalStateException("Access denied")) }

        viewModel.onAction(PrivacyAction.OpenConversation("request-new", "donor-new"))
        viewModel.onAction(PrivacyAction.SendMessage("Hello"))
        viewModel.onAction(PrivacyAction.ShareContact("phone", "555-0100"))

        assertNoConversationContent()
        assertFalse(viewModel.state.value.chatLoading)
        assertEquals("The conversation could not be opened.", viewModel.state.value.message)
        assertTrue(repository.sends.isEmpty())
        assertTrue(repository.shares.isEmpty())
        assertEquals(listOf("chat-old"), repository.messageReads)
        assertEquals(listOf("chat-old"), repository.shareReads)
    }

    /** Checks the fallback error text when the first open fails without an exception message. */
    @Test
    fun failed_first_open_without_an_error_message_uses_the_fallback() = runTest {
        repository.open = { _, _ -> Result.failure(IllegalStateException()) }

        viewModel.openConversation("request-new", "donor-new")

        assertNoConversationContent()
        assertFalse(viewModel.state.value.chatLoading)
        assertEquals("The conversation could not be opened.", viewModel.state.value.message)
        assertTrue(repository.messageReads.isEmpty())
        assertTrue(repository.shareReads.isEmpty())
    }

    /** Checks that a retry clears the error, replaces history, and directs writes to the new conversation. */
    @Test
    fun successful_retry_replaces_the_error_and_routes_writes_to_the_new_chat() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        repository.open = { _, _ -> Result.failure(IllegalStateException("Try again")) }
        viewModel.openConversation("request-new", "donor-new")
        assertEquals("The conversation could not be opened.", viewModel.state.value.message)
        repository.open = { requestId, donorId -> Result.success(conversation("chat-new", requestId, donorId)) }
        repository.loadedMessages = listOf(chatMessage("chat-new"))
        repository.loadedShares = listOf(contactShare("chat-new"))

        viewModel.openConversation("request-new", "donor-new")

        assertEquals("chat-new", viewModel.state.value.conversationId)
        assertEquals(repository.loadedMessages, viewModel.state.value.messages)
        assertEquals(repository.loadedShares, viewModel.state.value.contactShares)
        assertNull(viewModel.state.value.message)
        assertFalse(viewModel.state.value.chatLoading)
        viewModel.onAction(PrivacyAction.SendMessage("New recipient"))
        viewModel.onAction(PrivacyAction.ShareContact("email", "new@example.invalid"))
        assertEquals(listOf("chat-new" to "New recipient"), repository.sends)
        assertEquals(listOf(Triple("chat-new", "email", "new@example.invalid")), repository.shares)
    }

    /** Keeps failed history reads empty after switching chats instead of exposing the previous content. */
    @Test
    fun opening_a_chat_with_failed_history_reads_does_not_restore_previous_content() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        repository.open = { requestId, donorId -> Result.success(conversation("chat-new", requestId, donorId)) }
        repository.failHistory = true

        viewModel.openConversation("request-new", "donor-new")

        assertEquals("chat-new", viewModel.state.value.conversationId)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertTrue(viewModel.state.value.contactShares.isEmpty())
        assertFalse(viewModel.state.value.chatLoading)
        assertEquals(listOf("chat-old", "chat-new"), repository.messageReads)
        assertEquals(listOf("chat-old", "chat-new"), repository.shareReads)
    }

    /** Ensures a failed send stops the progress state and exposes a retry-safe error. */
    @Test
    fun failed_send_stops_progress_and_reports_the_failure() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        repository.sendFailure = IllegalStateException("server rejected message")

        viewModel.onAction(PrivacyAction.SendMessage("Hello"))

        assertFalse(viewModel.state.value.sending)
        assertEquals("Message could not be sent.", viewModel.state.value.message)
        assertNull(viewModel.state.value.lastSentMessage)
        assertEquals(listOf("chat-old" to "Hello"), repository.sends)
    }

    /** Publishes the server-confirmed message so the composer clears only after success. */
    @Test
    fun successful_send_exposes_the_confirmed_message() = runTest {
        viewModel.openConversation("request-old", "donor-old")

        viewModel.onAction(PrivacyAction.SendMessage("Hello"))

        assertFalse(viewModel.state.value.sending)
        assertEquals("Hello", viewModel.state.value.lastSentMessage?.body)
        assertEquals("message-chat-old", viewModel.state.value.lastSentMessage?.messageId)
        assertNull(viewModel.state.value.message)
    }

    /** Exposes a contact share only after the repository confirms persistence. */
    @Test
    fun duplicate_send_actions_while_a_send_is_in_flight_are_ignored() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        val sendGate = CompletableDeferred<Result<ChatMessage>>()
        repository.sendGate = sendGate

        viewModel.onAction(PrivacyAction.SendMessage("first message"))
        assertTrue(viewModel.state.value.sending)
        viewModel.onAction(PrivacyAction.SendMessage("duplicate message"))

        assertEquals(listOf("chat-old" to "first message"), repository.sends)
        sendGate.complete(Result.success(chatMessage("chat-old").copy(body = "first message")))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.sending)
        assertEquals("first message", viewModel.state.value.lastSentMessage?.body)
    }

    @Test
    fun duplicate_contact_shares_while_a_share_is_in_flight_are_ignored() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        val shareGate = CompletableDeferred<Result<ContactShare>>()
        repository.shareGate = shareGate

        viewModel.onAction(PrivacyAction.ShareContact("email", "person@example.invalid"))
        assertTrue(viewModel.state.value.sharingContact)
        viewModel.onAction(PrivacyAction.ShareContact("email", "person@example.invalid"))

        assertEquals(
            listOf(Triple("chat-old", "email", "person@example.invalid")),
            repository.shares,
        )
        shareGate.complete(
            Result.success(contactShare("chat-old").copy(field = "email", value = "person@example.invalid")),
        )
        advanceUntilIdle()

        assertFalse(viewModel.state.value.sharingContact)
        assertEquals("person@example.invalid", viewModel.state.value.lastSharedContact?.value)
    }

    @Test
    fun successful_contact_share_exposes_confirmed_record() = runTest {
        viewModel.openConversation("request-old", "donor-old")

        viewModel.onAction(PrivacyAction.ShareContact("phone", "555-0100"))

        assertEquals("phone", viewModel.state.value.lastSharedContact?.field)
        assertEquals("555-0100", viewModel.state.value.lastSharedContact?.value)
        assertEquals("requester", viewModel.state.value.lastSharedContact?.sharedBy)
        assertEquals("Your phone was shared and recorded.", viewModel.state.value.message)
        assertFalse(viewModel.state.value.sharingContact)
    }

    /** Leaves the contact form's values available when the server rejects a share. */
    @Test
    fun failed_contact_share_does_not_publish_a_confirmed_record() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        repository.shareFailure = IllegalStateException("server rejected contact share")

        viewModel.onAction(PrivacyAction.ShareContact("phone", "555-0100"))

        assertNull(viewModel.state.value.lastSharedContact)
        assertFalse(viewModel.state.value.sharingContact)
        assertEquals("Contact details could not be shared.", viewModel.state.value.message)
    }

    /** Keeps the last successful history visible when a later refresh fails, then recovers on retry. */
    @Test
    fun failed_refresh_preserves_history_and_successful_retry_clears_error() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        val previousMessages = viewModel.state.value.messages
        val previousShares = viewModel.state.value.contactShares
        repository.failHistory = true

        viewModel.onAction(PrivacyAction.RefreshConversation)

        assertEquals(previousMessages, viewModel.state.value.messages)
        assertEquals(previousShares, viewModel.state.value.contactShares)
        assertNotNull(viewModel.state.value.conversationRefreshError)

        repository.failHistory = false
        val refreshedMessage = chatMessage("chat-old").copy(messageId = "new-message", body = "Recovered")
        repository.loadedMessages = listOf(refreshedMessage)

        viewModel.onAction(PrivacyAction.RefreshConversation)

        assertEquals(listOf(refreshedMessage), viewModel.state.value.messages)
        assertNull(viewModel.state.value.conversationRefreshError)
    }

    /** Refreshes a visible conversation so messages sent by the other participant appear without reopening chat. */
    @Test
    fun refresh_action_reloads_current_conversation_messages_and_contact_shares() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        val incomingMessage = chatMessage("chat-old").copy(messageId = "incoming-2", body = "New message from the other participant")
        repository.loadedMessages = listOf(chatMessage("chat-old"), incomingMessage)
        repository.loadedShares = listOf(contactShare("chat-old"), contactShare("chat-old").copy(shareId = "share-2"))

        viewModel.onAction(PrivacyAction.RefreshConversation)

        assertEquals(repository.loadedMessages, viewModel.state.value.messages)
        assertEquals(repository.loadedShares, viewModel.state.value.contactShares)
        assertEquals(listOf("chat-old", "chat-old"), repository.messageReads)
        assertEquals(listOf("chat-old", "chat-old"), repository.shareReads)
        assertFalse(viewModel.state.value.chatLoading)
    }

    /** Persists a saved-message toggle through the repository and refreshes authoritative server state. */
    @Test
    fun saved_message_toggle_calls_remote_repository_and_updates_saved_list() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        val message = repository.loadedMessages.first()
        repository.loadedSavedMessages = listOf(message.copy(isSaved = true))

        viewModel.onAction(PrivacyAction.ToggleSavedMessage(message.messageId))

        assertEquals(listOf("chat-old" to message.messageId), repository.savedWrites)
        assertEquals(listOf(message.copy(isSaved = true)), viewModel.state.value.savedMessages)
        assertTrue(viewModel.state.value.messages.first().isSaved)
    }

    /** Asserts that the conversation ID, messages, and contact shares have all been cleared. */
    private fun assertNoConversationContent() {
        assertNull(viewModel.state.value.conversationId)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertTrue(viewModel.state.value.contactShares.isEmpty())
    }
}

private class ConversationRepositoryFake : PrivacyRepository {
    val opens = mutableListOf<Pair<String, String>>()
    val messageReads = mutableListOf<String>()
    val shareReads = mutableListOf<String>()
    val savedReads = mutableListOf<String>()
    val savedWrites = mutableListOf<Pair<String, String>>()
    val sends = mutableListOf<Pair<String, String>>()
    val shares = mutableListOf<Triple<String, String, String>>()
    var map: suspend () -> Result<DonorMap> = { error("Unexpected map request") }
    var visibility: suspend (Boolean, Boolean) -> Result<DonorMapVisibility> = { visible, exact ->
        Result.success(DonorMapVisibility(mapVisible = visible, exactLocationSharingEnabled = exact))
    }
    var loadedMessages = listOf(chatMessage("chat-old"))
    var loadedShares = listOf(contactShare("chat-old"))
    var loadedSavedMessages = emptyList<ChatMessage>()
    var failHistory = false
    var conversationList: suspend () -> Result<List<Conversation>> = { Result.success(emptyList()) }
    var sendFailure: Throwable? = null
    var sendGate: CompletableDeferred<Result<ChatMessage>>? = null
    var shareFailure: Throwable? = null
    var shareGate: CompletableDeferred<Result<ContactShare>>? = null
    var open: suspend (String, String) -> Result<Conversation> = { requestId, donorId ->
        Result.success(conversation("chat-old", requestId, donorId))
    }

    /** Records the requested pair and delegates to the configurable open result or suspended operation. */
    override suspend fun openConversation(requestId: String, donorId: String): Result<Conversation> {
        opens += requestId to donorId
        return open(requestId, donorId)
    }

    override suspend fun conversations(): Result<List<Conversation>> = conversationList()

    /** Records the history lookup and returns seeded messages or the configured failure. */
    override suspend fun messages(conversationId: String): Result<List<ChatMessage>> {
        messageReads += conversationId
        return if (failHistory) Result.failure(IllegalStateException("History unavailable")) else Result.success(loadedMessages)
    }

    /** Records the contact-share lookup and returns seeded shares or the configured failure. */
    override suspend fun contactShares(conversationId: String): Result<List<ContactShare>> {
        shareReads += conversationId
        return if (failHistory) Result.failure(IllegalStateException("Shares unavailable")) else Result.success(loadedShares)
    }

    /** Records the destination and body, then returns a synthetic message for that conversation. */
    override suspend fun sendMessage(conversationId: String, body: String): Result<ChatMessage> {
        sends += conversationId to body
        sendGate?.let { return it.await() }
        sendFailure?.let { return Result.failure(it) }
        return Result.success(chatMessage(conversationId).copy(body = body))
    }

    override suspend fun savedMessages(conversationId: String): Result<List<ChatMessage>> {
        savedReads += conversationId
        return Result.success(loadedSavedMessages)
    }

    override suspend fun saveMessage(conversationId: String, messageId: String): Result<Unit> {
        savedWrites += conversationId to messageId
        val target = loadedMessages.firstOrNull { it.messageId == messageId }
        if (target != null) loadedSavedMessages = (loadedSavedMessages + target.copy(isSaved = true)).distinctBy { it.messageId }
        loadedMessages = loadedMessages.map { if (it.messageId == messageId) it.copy(isSaved = true) else it }
        return Result.success(Unit)
    }

    override suspend fun unsaveMessage(conversationId: String, messageId: String): Result<Unit> {
        savedWrites += conversationId to messageId
        loadedSavedMessages = loadedSavedMessages.filterNot { it.messageId == messageId }
        loadedMessages = loadedMessages.map { if (it.messageId == messageId) it.copy(isSaved = false) else it }
        return Result.success(Unit)
    }

    /** Records the destination and contact field, then returns a synthetic share with those values. */
    override suspend fun shareContact(conversationId: String, field: String, value: String): Result<ContactShare> {
        shares += Triple(conversationId, field, value)
        shareGate?.let { return it.await() }
        shareFailure?.let { return Result.failure(it) }
        return Result.success(contactShare(conversationId).copy(field = field, value = value))
    }

    /** Returns the configured map response for map-loading regression tests. */
    override suspend fun donorMap(): Result<DonorMap> = map()

    /** Fails immediately if a conversation test unexpectedly changes map visibility. */
    override suspend fun setMapVisibility(mapVisible: Boolean, exactLocationSharingEnabled: Boolean): Result<DonorMapVisibility> =
        visibility(mapVisible, exactLocationSharingEnabled)

    /** Fails immediately if a conversation test unexpectedly activates location sharing. */
    override suspend fun activateLocationShare(requestId: String, donorId: String): Result<Unit> = error("Unexpected location share")

    /** Fails immediately if a conversation test unexpectedly revokes location sharing. */
    override suspend fun revokeLocationShare(requestId: String, donorId: String): Result<Unit> = error("Unexpected location revocation")

    /** Fails immediately if a conversation test unexpectedly requests a matched donor location. */
    override suspend fun matchedDonorLocation(requestId: String, donorId: String): Result<MatchedDonorLocation> =
        error("Unexpected location request")

    /** Fails immediately if a conversation test unexpectedly reports a participant. */
    override suspend fun reportParticipant(conversationId: String, reason: String): Result<Unit> = error("Unexpected report")

    /** Fails immediately if a conversation test unexpectedly blocks a participant. */
    override suspend fun blockParticipant(conversationId: String): Result<Unit> = error("Unexpected block")
}

/** Builds a deterministic conversation fixture for the supplied request and donor IDs. */
private fun conversation(id: String, requestId: String, donorId: String) =
    Conversation(id, requestId, donorId, "requester", createdAt = "2026-10-01T12:00:00Z")

/** Builds a deterministic message fixture whose ID identifies its conversation. */
private fun chatMessage(conversationId: String) =
    ChatMessage("message-$conversationId", conversationId, "requester", "Test message", "2026-10-01T12:00:00Z")

/** Builds a synthetic email-share fixture whose ID identifies its conversation. */
private fun contactShare(conversationId: String) =
    ContactShare("share-$conversationId", conversationId, "requester", "email", "test@example.invalid", "2026-10-01T12:00:00Z")
