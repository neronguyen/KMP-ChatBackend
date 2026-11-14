package io.github.neronguyenvn.nerochat.user.service.job

import io.github.neronguyenvn.nerochat.user.infra.database.repository.RefreshTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class RefreshTokenPurgeJob(
    private val refreshTokenRepository: RefreshTokenRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun purgeExpiredRefreshTokens() {
        val deletedCount = refreshTokenRepository.deleteExpiredTokensBefore(Instant.now())
        logger.info("Purged {} expired refresh token(s)", deletedCount)
    }
}
