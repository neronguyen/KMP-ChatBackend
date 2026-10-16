package io.github.neronguyenvn.nerochat.chat.infra.database.model

import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@OptIn(ExperimentalUuidApi::class)
@Entity
@Table(
    name = "chat_messages",
    schema = "chat_service",
    indexes = [
        Index(
            name = "idx_chat_message_chat_room_id_created_at",
            columnList = "chat_room_id,created_at DESC,id DESC"
        )
    ]
)
class ChatMessageEntity(
    @Id
    @Column(nullable = false, updatable = false)
    @JvmField
    final var id: UUID = Uuid.generateV7().toJavaUuid(),

    @Column(nullable = false)
    var content: String,

    @Column(name = "chat_room_id", nullable = false, updatable = false)
    var roomId: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false, insertable = false, updatable = false)
    var chatRoom: ChatRoomEntity? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false, updatable = false)
    var sender: ChatParticipantEntity,

    @Column(nullable = false, updatable = false)
    var createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
) : Persistable<UUID> {

    @Transient
    private var isNewEntity: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNewEntity

    @PrePersist
    private fun truncateCreatedAtToMillis() {
        createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS)
    }

    @PostPersist
    @PostLoad
    private fun markNotNew() {
        isNewEntity = false
    }
}

@OptIn(ExperimentalTime::class)
fun ChatMessageEntity.asExternalModel() = ChatMessage(
    id = ChatMessageId(id.toString()),
    roomId = ChatRoomId(roomId.toString()),
    content = content,
    sender = sender.asExternalModel(),
    createdAt = createdAt.toKotlinInstant()
)
