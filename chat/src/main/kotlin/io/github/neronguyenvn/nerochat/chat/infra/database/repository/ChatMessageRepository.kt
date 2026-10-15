package io.github.neronguyenvn.nerochat.chat.infra.database.repository

import io.github.neronguyenvn.nerochat.chat.infra.database.model.ChatMessageEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ChatMessageRepository : JpaRepository<ChatMessageEntity, UUID> {

    @Query(
        """
        SELECT m
        FROM ChatMessageEntity m
        JOIN FETCH m.sender
        WHERE m.roomId = :roomId
        AND (:before IS NULL OR m.createdAt < :before) 
        ORDER BY m.createdAt DESC
    """
    )
    fun findByRoomIdBefore(
        roomId: UUID,
        before: Instant?,
        pageable: Pageable
    ): Slice<ChatMessageEntity>

    @Query(
        """
        SELECT m
        FROM ChatMessageEntity m
        LEFT JOIN FETCH m.sender
        WHERE m.roomId IN :roomIds
        AND (m.createdAt, m.id) = (
            SELECT m2.createdAt, m2.id
            FROM ChatMessageEntity m2
            WHERE m2.roomId = m.roomId
            ORDER BY m2.createdAt DESC 
            LIMIT 1
        )
    """
    )
    fun findLatestMessagesByRoomIds(roomIds: Set<UUID>): List<ChatMessageEntity>
}
