package io.github.neronguyenvn.nerochat.user.infra.database.repository

import io.github.neronguyenvn.nerochat.user.infra.database.model.RefreshTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, Long> {

    /**
     * Deletes stored refresh tokens matching both the user and token hash.
     * Return deleted count.
     */
    @Modifying
    @Query("""
        DELETE FROM RefreshTokenEntity rt WHERE rt.userId = :userId AND rt.hashedToken = :hashedToken
    """)
    fun deleteByUserIdAndHashedToken(userId: UUID, hashedToken: String): Long

    /**
     * Deletes stored refresh tokens whose expiration timestamp is before [cutoff].
     * Return deleted count.
     */
    @Modifying
    @Query("""
        DELETE FROM RefreshTokenEntity rt WHERE rt.expiredAt < :cutoff
    """)
    fun deleteExpiredTokensBefore(cutoff: Instant): Int

    /** Deletes all stored refresh tokens for the user. */
    fun deleteByUserId(userId: UUID)
}
