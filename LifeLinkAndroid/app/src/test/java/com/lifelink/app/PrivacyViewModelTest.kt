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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = PrivacyViewModel(repository)
        store.put("privacy", viewModel)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

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

    @Test
    fun failed_open_does_not_send_messages_or_contact_details_to_the_previous_chat() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        repository.open = { _, _ -> Result.failure(IllegalStateException("Access denied")) }

        viewModel.onAction(PrivacyAction.OpenConversation("request-new", "donor-new"))
        viewModel.onAction(PrivacyAction.SendMessage("Hello"))
        viewModel.onAction(PrivacyAction.ShareContact("phone", "555-0100"))

        assertNoConversationContent()
        assertFalse(viewModel.state.value.chatLoading)
        assertEquals("Access denied", viewModel.state.value.message)
        assertTrue(repository.sends.isEmpty())
        assertTrue(repository.shares.isEmpty())
        assertEquals(listOf("chat-old"), repository.messageReads)
        assertEquals(listOf("chat-old"), repository.shareReads)
    }

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

    @Test
    fun successful_retry_replaces_the_error_and_routes_writes_to_the_new_chat() = runTest {
        viewModel.openConversation("request-old", "donor-old")
        repository.open = { _, _ -> Result.failure(IllegalStateException("Try again")) }
        viewModel.openConversation("request-new", "donor-new")
        assertEquals("Try again", viewModel.state.value.message)
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
    val sends = mutableListOf<Pair<String, String>>()
    val shares = mutableListOf<Triple<String, String, String>>()
    var loadedMessages = listOf(chatMessage("chat-old"))
    var loadedShares = listOf(contactShare("chat-old"))
    var failHistory = false
    var open: suspend (String, String) -> Result<Conversation> = { requestId, donorId ->
        Result.success(conversation("chat-old", requestId, donorId))
    }

    override suspend fun openConversation(requestId: String, donorId: String): Result<Conversation> {
        opens += requestId to donorId
        return open(requestId, donorId)
    }

    override suspend fun messages(conversationId: String): Result<List<ChatMessage>> {
        messageReads += conversationId
        return if (failHistory) Result.failure(IllegalStateException("History unavailable")) else Result.success(loadedMessages)
    }

    override suspend fun contactShares(conversationId: String): Result<List<ContactShare>> {
        shareReads += conversationId
        return if (failHistory) Result.failure(IllegalStateException("Shares unavailable")) else Result.success(loadedShares)
    }

    override suspend fun sendMessage(conversationId: String, body: String): Result<ChatMessage> {
        sends += conversationId to body
        return Result.success(chatMessage(conversationId).copy(body = body))
    }

    override suspend fun shareContact(conversationId: String, field: String, value: String): Result<ContactShare> {
        shares += Triple(conversationId, field, value)
        return Result.success(contactShare(conversationId).copy(field = field, value = value))
    }

    override suspend fun donorMap(): Result<DonorMap> = error("Unexpected map request")

    override suspend fun setMapVisibility(mapVisible: Boolean, exactLocationSharingEnabled: Boolean): Result<DonorMapVisibility> =
        error("Unexpected visibility change")

    override suspend fun activateLocationShare(requestId: String, donorId: String): Result<Unit> = error("Unexpected location share")

    override suspend fun revokeLocationShare(requestId: String, donorId: String): Result<Unit> = error("Unexpected location revocation")

    override suspend fun matchedDonorLocation(requestId: String, donorId: String): Result<MatchedDonorLocation> =
        error("Unexpected location request")

    override suspend fun reportParticipant(conversationId: String, reason: String): Result<Unit> = error("Unexpected report")

    override suspend fun blockParticipant(conversationId: String): Result<Unit> = error("Unexpected block")
}

private fun conversation(id: String, requestId: String, donorId: String) =
    Conversation(id, requestId, donorId, "requester", createdAt = "2026-10-01T12:00:00Z")

private fun chatMessage(conversationId: String) =
    ChatMessage("message-$conversationId", conversationId, "requester", "Test message", "2026-10-01T12:00:00Z")

private fun contactShare(conversationId: String) =
    ContactShare("share-$conversationId", conversationId, "requester", "email", "test@example.invalid", "2026-10-01T12:00:00Z")
