package io.github.neronguyenvn.nerochat.chat.service

import io.github.neronguyenvn.nerochat.chat.domain.event.InternalChatEvent
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatMessageEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatParticipantEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.DirectPairKey
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatMessageRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatParticipantRepository
import io.github.neronguyenvn.nerochat.chat.infra.database.repository.ChatRoomRepository
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.domain.type.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SpringBootTest(
    classes = [ChatRoomServiceTransactionTest.TestConfig::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:chat_tx_test_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS chat_service",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    ]
)
class ChatRoomServiceTransactionTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [ChatMessageEntity::class])
    @EnableJpaRepositories(basePackageClasses = [ChatRoomRepository::class])
    @Import(DirectChatRoomCreationTxHelper::class, ChatRoomService::class)
    class TestConfig {
        @Bean
        internal fun eventRecorder(): EventRecorder = EventRecorder()
    }

    internal class EventRecorder {
        val committedRoomEvents = CopyOnWriteArrayList<InternalChatEvent.ChatRoomCreatedEvent>()

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        fun onRoomCreated(event: InternalChatEvent.ChatRoomCreatedEvent) {
            committedRoomEvents.add(event)
        }
    }

    @Autowired
    private lateinit var chatParticipantRepository: ChatParticipantRepository

    @Autowired
    private lateinit var chatRoomRepository: ChatRoomRepository

    @Autowired
    private lateinit var chatMessageRepository: ChatMessageRepository

    @Autowired
    private lateinit var directChatRoomCreationTxHelper: DirectChatRoomCreationTxHelper

    @Autowired
    private lateinit var chatRoomService: ChatRoomService

    @Autowired
    private lateinit var eventRecorder: EventRecorder

    private lateinit var userA: ChatParticipantEntity
    private lateinit var userB: ChatParticipantEntity

    @BeforeEach
    fun setUp() {
        eventRecorder.committedRoomEvents.clear()
        chatMessageRepository.deleteAll()
        chatRoomRepository.deleteAll()
        chatParticipantRepository.deleteAll()

        userA = chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = UUID.randomUUID(),
                email = "usera-${UUID.randomUUID()}@example.com",
                displayName = "User A",
            )
        )
        userB = chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = UUID.randomUUID(),
                email = "userb-${UUID.randomUUID()}@example.com",
                displayName = "User B",
            )
        )
    }

    @Test
    fun `createDirectChatRoom commits REQUIRES_NEW transaction and fires AFTER_COMMIT event`() {
        val created = chatRoomService.createDirectChatRoom(
            creatorId = UserId(userA.userId),
            targetUserId = UserId(userB.userId),
            message = "  Hello world  ",
        )

        assertEquals(ChatRoomType.DIRECT, created.type)
        assertEquals("Hello world", created.lastMessage?.content)
        assertEquals(1, eventRecorder.committedRoomEvents.size)
        assertEquals(created.id, eventRecorder.committedRoomEvents.first().chatRoom.id)
    }

    @Test
    fun `duplicate REQUIRES_NEW direct room insert rolls back cleanly without firing AFTER_COMMIT event`() {
        val first = chatRoomService.createDirectChatRoom(
            creatorId = UserId(userA.userId),
            targetUserId = UserId(userB.userId),
            message = "Winner message",
        )
        assertEquals(1, eventRecorder.committedRoomEvents.size)

        val directPairKey = DirectPairKey.of(UserId(userA.userId), UserId(userB.userId)).value

        assertThrows<DataIntegrityViolationException> {
            directChatRoomCreationTxHelper.createDirectChatRoom(
                directPairKey = directPairKey,
                creator = userB,
                target = userA,
                message = "Loser message",
            )
        }

        // Only the committed winner triggered AFTER_COMMIT
        assertEquals(1, eventRecorder.committedRoomEvents.size)

        // Fresh read cleanly recovers the committed winner room and initial message
        val recovered = chatRoomService.createDirectChatRoom(
            creatorId = UserId(userB.userId),
            targetUserId = UserId(userA.userId),
            message = "Loser message",
        )
        assertNotNull(recovered)
        assertEquals(first.id, recovered.id)
        assertEquals("Winner message", recovered.lastMessage?.content)
    }
}
