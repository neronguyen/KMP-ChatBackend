import re

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatRoomService.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'    @Cacheable\([\s\S]*?    \)\n    fun getChatMessages\(\n        roomId: ChatRoomId,\n        before: Instant\?,\n    \): ChatMessageSliceDto \{\n        val slice = chatMessageRepository\.findByRoomIdBefore\(\n            roomId = roomId\.asUUID\(\),\n            before = before,\n            pageable = PageRequest\.of\(0, DEFAULT_PAGE_SIZE\)\n        \)'
)

replacement = """    @Cacheable(
        value = [CacheNames.MESSAGES],
        key = "#roomId",
        condition = "#before == null",
        sync = true
    )
    fun getChatMessages(
        roomId: ChatRoomId,
        before: Instant?,
        beforeId: UUID?,
    ): ChatMessageSliceDto {
        val slice = chatMessageRepository.findByRoomIdBefore(
            roomId = roomId.asUUID(),
            before = before,
            beforeId = beforeId,
            pageable = PageRequest.of(0, DEFAULT_PAGE_SIZE)
        )"""

content = pattern.sub(replacement, content)

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatRoomService.kt', 'w') as f:
    f.write(content)
