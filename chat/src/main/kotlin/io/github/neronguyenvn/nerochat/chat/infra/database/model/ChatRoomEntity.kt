package io.github.neronguyenvn.nerochat.chat.infra.database.model

import io.github.neronguyenvn.nerochat.chat.domain.model.ChatMessage
import io.github.neronguyenvn.nerochat.chat.domain.model.ChatRoom
import io.github.neronguyenvn.nerochat.contract.chat.ChatRoomType
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinInstant

@Entity
@Table(
    name = "chat_rooms",
    schema = "chat_service",
    indexes = [
        Index(
            name = "idx_chat_room_direct_pair_key",
            columnList = "direct_pair_key",
            unique = true
        )
    ]
)
class ChatRoomEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: ChatRoomType,

    @Column(nullable = true)
    var name: String? = null,

    @Column(name = "direct_pair_key", unique = true, nullable = true)
    var directPairKey: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    var creator: ChatParticipantEntity,

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "chat_room_participants_cross_ref",
        schema = "chat_service",
        joinColumns = [JoinColumn(name = "chat_room_id")],
        inverseJoinColumns = [JoinColumn(name = "user_id")],
        indexes = [
            // Answers efficiently:
            // Who is in chat room X?
            Index(
                name = "idx_chat_room_participant_chat_room_id_user_id",
                columnList = "chat_room_id,user_id",
                unique = true
            ),
            // Answers efficiently:
            // What chat rooms is user X in?
            Index(
                name = "idx_chat_room_participant_user_id_chat_room_id",
                columnList = "user_id,chat_room_id",
                unique = true
            ),
        ]
    )
    var participants: Set<ChatParticipantEntity> = emptySet(),

    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
)

@OptIn(ExperimentalTime::class)
fun ChatRoomEntity.asExternalModel(lastMessage: ChatMessage?) = ChatRoom(
    id = ChatRoomId(id?.toString() ?: error("ChatRoomId have to be generated")),
    type = type,
    name = name,
    creator = creator.asExternalModel(),
    participants = participants.map { it.asExternalModel() }.toSet(),
    lastMessage = lastMessage,
    lastActivityAt = lastMessage?.createdAt ?: createdAt.toKotlinInstant(),
    createdAt = createdAt.toKotlinInstant()
)
