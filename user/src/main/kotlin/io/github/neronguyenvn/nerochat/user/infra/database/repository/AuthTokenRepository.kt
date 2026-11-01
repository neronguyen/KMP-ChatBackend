package io.github.neronguyenvn.nerochat.user.infra.database.repository

import io.github.neronguyenvn.nerochat.user.domain.model.AuthTokenType
import io.github.neronguyenvn.nerochat.user.infra.database.model.AuthTokenEntity
import io.github.neronguyenvn.nerochat.user.infra.database.model.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthTokenRepository : JpaRepository<AuthTokenEntity, UUID> {

    /** Finds the latest active token for [user] and [tokenType]. */
    fun findFirstByUserAndTokenTypeAndUsedAtIsNullOrderByCreatedAtDesc(
        user: UserEntity,
        tokenType: AuthTokenType
    ): AuthTokenEntity?

    /** Sets the usage timestamp on all of the user's verification tokens, including used or expired ones. */
    fun invalidateEmailVerificationTokens(user: UserEntity) {
        invalidateActiveTokens(user, AuthTokenType.EmailVerification)
    }

    /** Sets the usage timestamp on all of the user's reset tokens, including used or expired ones. */
    fun invalidatePasswordResetTokens(user: UserEntity) {
        invalidateActiveTokens(user, AuthTokenType.PasswordReset)
    }

    /** Deletes tokens with an expiration strictly before [now], regardless of type or usage. */
    fun deleteByExpiredAtBefore(now: Instant)

    /** Sets the usage timestamp to database current time for every matching token, including used or expired ones. */
    @Modifying
    @Query(
        """
    UPDATE AuthTokenEntity entity
    SET entity.usedAt = CURRENT_TIMESTAMP
    WHERE entity.user = :user AND entity.tokenType = :tokenType
"""
    )
    fun invalidateActiveTokens(user: UserEntity, tokenType: AuthTokenType)
}
