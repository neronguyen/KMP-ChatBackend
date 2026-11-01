package io.github.neronguyenvn.nerochat.user.infra.database.model

import io.github.neronguyenvn.nerochat.user.domain.model.AuthToken
import io.github.neronguyenvn.nerochat.user.domain.model.AuthTokenType
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "auth_tokens",
    schema = "user_service",
    indexes = [
        Index(name = "idx_auth_tokens_user_type", columnList = "user_id, tokenType")
    ]
)
class AuthTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(nullable = false, length = 16)
    var token: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var tokenType: AuthTokenType,

    @Column(nullable = false)
    var expiredAt: Instant,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity,

    @Column(nullable = false)
    var attempts: Int = 0,

    var usedAt: Instant? = null,

    @CreationTimestamp
    var createdAt: Instant = Instant.now(),
) {
    /** Returns whether a usage timestamp has been set. */
    fun isUsed() = usedAt != null

    /** Returns true only when the current instant is strictly after the expiration. */
    fun isExpired() = Instant.now().isAfter(expiredAt)
}

/**
 * Maps this entity to an email-verification token with its domain user.
 *
 * @throws IllegalStateException if the token type differs or the user has no assigned ID.
 */
fun AuthTokenEntity.asEmailVerificationToken(): AuthToken.EmailVerification {
    if (tokenType != AuthTokenType.EmailVerification) error("Invalid token type")
    return AuthToken.EmailVerification(
        token = token,
        user = user.asExternalModel()
    )
}

/**
 * Maps this entity to a password-reset token with its domain user.
 *
 * @throws IllegalStateException if the token type differs or the user has no assigned ID.
 */
fun AuthTokenEntity.asPasswordResetToken(): AuthToken.PasswordReset {
    if (tokenType != AuthTokenType.PasswordReset) error("Invalid token type")
    return AuthToken.PasswordReset(
        token = token,
        user = user.asExternalModel()
    )
}
