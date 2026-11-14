package io.github.neronguyenvn.nerochat.user.infra.database.repository

import io.github.neronguyenvn.nerochat.domain.type.UserId
import io.github.neronguyenvn.nerochat.user.infra.database.model.RefreshTokenEntity
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(
    classes = [RefreshTokenRepositoryTest.TestConfig::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:user_test_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS user_service",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    ]
)
@Transactional
class RefreshTokenRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [RefreshTokenEntity::class])
    @EnableJpaRepositories(basePackageClasses = [RefreshTokenRepository::class])
    class TestConfig {
        @Bean
        fun redissonClient(): RedissonClient = mock(RedissonClient::class.java)
    }

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `deleteExpiredTokensBefore deletes only tokens expired before cutoff`() {
        val now = Instant.now()
        val userId = UserId(UUID.randomUUID())

        val expired = refreshTokenRepository.saveAndFlush(
            RefreshTokenEntity(
                userId = userId,
                hashedToken = "expired-hash",
                expiredAt = now.minus(1, ChronoUnit.DAYS),
            )
        )
        val active = refreshTokenRepository.saveAndFlush(
            RefreshTokenEntity(
                userId = userId,
                hashedToken = "active-hash",
                expiredAt = now.plus(7, ChronoUnit.DAYS),
            )
        )
        entityManager.clear()

        val deletedCount = refreshTokenRepository.deleteExpiredTokensBefore(now)
        entityManager.flush()
        entityManager.clear()

        assertEquals(1, deletedCount)
        val remaining = refreshTokenRepository.findAll()
        assertEquals(listOf(active.id), remaining.map { it.id })
        assertEquals("active-hash", remaining.first().hashedToken)
        assertEquals(expired.userId, remaining.first().userId)
    }
}
