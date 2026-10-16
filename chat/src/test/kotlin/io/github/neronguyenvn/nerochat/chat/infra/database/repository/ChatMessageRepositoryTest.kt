package io.github.neronguyenvn.nerochat.chat.infra.database.repository

import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoomType
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatMessageEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatParticipantEntity
import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatRoomEntity
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@SpringBootTest(
    classes = [ChatMessageRepositoryTest.TestConfig::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:chat_test_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS chat_service",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
@Transactional
class ChatMessageRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [ChatMessageEntity::class])
    @EnableJpaRepositories(basePackageClasses = [ChatMessageRepository::class])
    class TestConfig

    @Autowired
    private lateinit var chatMessageRepository: ChatMessageRepository

    @Autowired
    private lateinit var chatRoomRepository: ChatRoomRepository

    @Autowired
    private lateinit var chatParticipantRepository: ChatParticipantRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    private lateinit var sender: ChatParticipantEntity
    private lateinit var otherSender: ChatParticipantEntity
    private lateinit var room: ChatRoomEntity

    @BeforeEach
    fun setUp() {
        sender = chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = UUID.randomUUID(),
                email = "sender@example.com",
                displayName = "Sender"
            )
        )
        otherSender = chatParticipantRepository.saveAndFlush(
            ChatParticipantEntity(
                userId = UUID.randomUUID(),
                email = "other@example.com",
                displayName = "Other"
            )
        )
        room = chatRoomRepository.saveAndFlush(
            ChatRoomEntity(
                type = ChatRoomType.DIRECT,
                creator = sender,
                participants = setOf(sender, otherSender)
            )
        )
        entityManager.clear()
    }

    @Test
    fun `saveAndFlush inserts new message with client-assigned UUID and preserves the exact UUID`() {
        val clientAssignedId = UUID.randomUUID()
        val messageEntity = ChatMessageEntity(
            id = clientAssignedId,
            roomId = room.id!!,
            sender = sender,
            content = "Message with client UUID"
        )

        val saved = chatMessageRepository.saveAndFlush(messageEntity)
        assertEquals(clientAssignedId, saved.id)
        assertFalse(saved.isNew)

        entityManager.clear()

        val reloaded = chatMessageRepository.findByIdOrNull(clientAssignedId)
        assertNotNull(reloaded)
        assertEquals(clientAssignedId, reloaded?.id)
        assertEquals("Message with client UUID", reloaded?.content)
        assertEquals(sender.userId, reloaded?.sender?.userId)
        assertEquals(room.id, reloaded?.roomId)
        assertFalse(reloaded!!.isNew)
    }

    @Test
    fun `saveAndFlush inserts new message with default application-generated UUID when id is omitted`() {
        val messageEntity = ChatMessageEntity(
            roomId = room.id!!,
            sender = sender,
            content = "Message with default UUID"
        )

        val generatedId = messageEntity.id
        val saved = chatMessageRepository.saveAndFlush(messageEntity)
        assertEquals(generatedId, saved.id)

        entityManager.clear()

        val reloaded = chatMessageRepository.findByIdOrNull(generatedId)
        assertNotNull(reloaded)
        assertEquals("Message with default UUID", reloaded?.content)
    }

    @Test
    fun `saveAndFlush with duplicate ID fails with DataIntegrityViolationException instead of overwriting existing row`() {
        val messageId = UUID.randomUUID()
        chatMessageRepository.saveAndFlush(
            ChatMessageEntity(
                id = messageId,
                roomId = room.id!!,
                sender = sender,
                content = "Original content"
            )
        )
        entityManager.clear()

        val conflictingMessage = ChatMessageEntity(
            id = messageId,
            roomId = room.id!!,
            sender = otherSender,
            content = "Overwritten content"
        )

        assertThrows<DataIntegrityViolationException> {
            chatMessageRepository.saveAndFlush(conflictingMessage)
        }
    }
}
