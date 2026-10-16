import re

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatMessageService.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'    fun sendMessage\([\s\S]*?    \): ChatMessage \{[\s\S]*?        return savedMessage\.asExternalModel\(\)\n    \}'
)

replacement = """    fun sendMessage(
        roomId: ChatRoomId,
        senderId: UserId,
        content: String,
        messageId: ChatMessageId? = null
    ): Pair<ChatMessage, Boolean> {
        val targetMessageId = messageId?.asUUID() ?: UUID.randomUUID()

        // Check for idempotency
        val existingMessage = chatMessageRepository.findByIdOrNull(targetMessageId)
        if (existingMessage != null) {
            if (existingMessage.roomId != roomId.asUUID() || existingMessage.sender.userId != senderId.asUUID()) {
                throw ConflictException("Message ID already exists for a different room or sender")
            }
            return Pair(existingMessage.asExternalModel(), false)
        }

        chatRoomRepository.findChatRoomById(roomId.asUUID(), senderId.asUUID())
            ?: throw ChatRoomNotFoundException()

        val sender = chatParticipantRepository.findByIdOrNull(senderId.asUUID())
            ?: throw ChatParticipantNotFoundException(senderId)

        val entityToSave = ChatMessageEntity(
            id = targetMessageId,
            roomId = roomId.asUUID(),
            sender = sender,
            content = content.trim(),
        )

        val savedMessage = chatMessageRepository.saveAndFlush(entityToSave)
        savedMessage.markAsSaved()

        eventPublisher.publish(
            event = ChatEvent.NewMessage(
                roomId = roomId,
                senderId = UserId(sender.userId),
                message = savedMessage.content
            )
        )

        return Pair(savedMessage.asExternalModel(), true)
    }"""

new_content = pattern.sub(replacement, content)

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatMessageService.kt', 'w') as f:
    f.write(new_content)
