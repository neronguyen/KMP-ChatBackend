package io.github.neronguyenvn.nerochat.integration

import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatParticipantEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.DirectPairKey
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatParticipantRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatRoomRepository
import io.github.neronguyenvn.nerochat.chat.service.ChatRoomService
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DirectRoomConcurrencyIntegrationTest : AbstractContainerIntegrationTest() {

    @Autowired
    private lateinit var chatParticipantRepository: ChatParticipantRepository

    @Autowired
    private lateinit var chatRoomRepository: ChatRoomRepository

    @Autowired
    private lateinit var chatRoomService: ChatRoomService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `concurrent createDirectChatRoom calls in opposite directions converge to single PostgreSQL row`() {
        val userAId = UUID.randomUUID()
        val userBId = UUID.randomUUID()

        chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = userAId,
                email = "usera-$userAId@example.com",
                displayName = "User A",
            )
        )
        chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = userBId,
                email = "userb-$userBId@example.com",
                displayName = "User B",
            )
        )

        val executor = Executors.newFixedThreadPool(2)
        val readyLatch = CountDownLatch(2)
        val startGate = CountDownLatch(1)

        val futureAtoB = executor.submit<ChatRoom> {
            readyLatch.countDown()
            assertTrue(startGate.await(5, TimeUnit.SECONDS))
            chatRoomService.createDirectChatRoom(
                creatorId = UserId(userAId),
                targetUserId = UserId(userBId),
                message = "Hi",
            )
        }

        val futureBtoA = executor.submit<ChatRoom> {
            readyLatch.countDown()
            assertTrue(startGate.await(5, TimeUnit.SECONDS))
            chatRoomService.createDirectChatRoom(
                creatorId = UserId(userBId),
                targetUserId = UserId(userAId),
                message = "Hello",
            )
        }

        try {
            assertTrue(readyLatch.await(5, TimeUnit.SECONDS))
            startGate.countDown()

            val roomAtoB = futureAtoB.get(15, TimeUnit.SECONDS)
            val roomBtoA = futureBtoA.get(15, TimeUnit.SECONDS)

            assertEquals(ChatRoomType.DIRECT, roomAtoB.type)
            assertEquals(roomAtoB.id, roomBtoA.id, "Both concurrent callers must converge to the same direct room ID")
        } finally {
            executor.shutdownNow()
        }

        val directPairKey = DirectPairKey.of(userAId, userBId).value
        val persistedRoomCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_service.chat_rooms WHERE direct_pair_key = ?",
            Long::class.java,
            directPairKey,
        )
        assertEquals(1L, persistedRoomCount)
        assertNotNull(chatRoomRepository.findDirectChatRoomBetween(directPairKey))
    }
}
