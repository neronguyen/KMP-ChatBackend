package io.github.neronguyenvn.nerochat.integration

import io.github.neronguyenvn.nerochat.ChatApp
import io.github.neronguyenvn.nerochat.notification.infra.messagequeue.NotificationUserEventListener
import io.github.neronguyenvn.nerochat.notification.service.EmailService
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.util.Properties

@SpringBootTest(
    classes = [ChatApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("integration")
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractContainerIntegrationTest {

    @MockitoBean
    protected lateinit var javaMailSender: JavaMailSender

    @MockitoSpyBean
    protected lateinit var notificationUserEventListener: NotificationUserEventListener

    @MockitoSpyBean
    protected lateinit var emailService: EmailService

    @BeforeEach
    fun stubMimeMessageCreation() {
        `when`(javaMailSender.createMimeMessage()).thenAnswer {
            MimeMessage(Session.getInstance(Properties()))
        }
    }

    private class RedisContainer(imageName: String) : GenericContainer<RedisContainer>(imageName)

    companion object {
        @JvmField
        @field:ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("integration/init-schemas.sql")

        @JvmField
        @field:ServiceConnection
        val redis: GenericContainer<*> = RedisContainer("redis:7-alpine")
            .withExposedPorts(6379)

        @JvmField
        @field:ServiceConnection
        val rabbitmq: RabbitMQContainer = RabbitMQContainer("rabbitmq:3-management-alpine")
    }
}
