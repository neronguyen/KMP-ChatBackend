package io.github.neronguyenvn.nerochat.chat.api.websocket

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: ChatWebSocketHandler
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry
            .addHandler(handler, "/ws/chat")
            .setAllowedOriginPatterns("*")
    }

    @Bean(ChatWebSocketHandler.HEARTBEAT_SCHEDULER_BEAN_NAME)
    fun webSocketHeartbeatScheduler(): TaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("ws-heartbeat-")
        isDaemon = true
    }
}
