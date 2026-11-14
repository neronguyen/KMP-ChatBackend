package io.github.neronguyenvn.nerochat.user.service.job

import io.github.neronguyenvn.nerochat.user.infra.database.repository.RefreshTokenRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant

class RefreshTokenPurgeJobTest {

    private val refreshTokenRepository: RefreshTokenRepository = mock(RefreshTokenRepository::class.java)
    private val job = RefreshTokenPurgeJob(refreshTokenRepository)

    @Test
    fun `purgeExpiredRefreshTokens deletes tokens expired before current instant`() {
        val before = Instant.now()
        val cutoffCaptor = ArgumentCaptor.forClass(Instant::class.java)
        `when`(refreshTokenRepository.deleteExpiredTokensBefore(cutoffCaptor.capture() ?: Instant.EPOCH))
            .thenReturn(3)

        job.purgeExpiredRefreshTokens()

        val after = Instant.now()
        verify(refreshTokenRepository).deleteExpiredTokensBefore(cutoffCaptor.value)
        assertFalse(cutoffCaptor.value.isBefore(before))
        assertFalse(cutoffCaptor.value.isAfter(after))
    }
}
