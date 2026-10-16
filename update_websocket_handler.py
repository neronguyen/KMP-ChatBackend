import re

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/api/websocket/ChatWebSocketHandler.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'        val savedMessage = chatMessageService\.sendMessage\([\s\S]*?\)\s*'
    r'        broadcastToChatRoom\([\s\S]*?OutgoingWsMessage\.NewMessage\(savedMessage\.asDto\(\)\)\s*'
    r'        \)'
)

replacement = """        val (savedMessage, isNew) = chatMessageService.sendMessage(
            roomId = incoming.roomId,
            senderId = senderId,
            content = incoming.content,
            messageId = incoming.messageId
        )

        // Only broadcast if the message was newly created to avoid echoing duplicates on idempotency retries
        if (isNew) {
            broadcastToChatRoom(
                roomId = incoming.roomId,
                outgoing = OutgoingWsMessage.NewMessage(savedMessage.asDto())
            )
        } else {
            // Send back only to the sender for acknowledgement
            val outgoing = OutgoingWsMessage.NewMessage(savedMessage.asDto())
            val payload = TextMessage(json.encodeToString(outgoing))
            sessionsById.values.filter { it.userId == senderId }.forEach { it.sendMessage(payload) }
        }"""

new_content = pattern.sub(replacement, content)

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/api/websocket/ChatWebSocketHandler.kt', 'w') as f:
    f.write(new_content)
