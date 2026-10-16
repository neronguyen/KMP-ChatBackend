import re

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/api/controller/ChatRoomController.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'    @GetMapping\("/\{roomId\}/messages"\)\n    fun getChatMessages\(\n        @PathVariable\("roomId"\) roomId: ChatRoomId,\n        @RequestParam\("before", required = false\) before: Instant\? = null,\n    \): ChatMessageSliceDto \{\n        chatRoomService\.getChatRoomById\(roomId, requesterId\)\n            \?: throw ForbiddenException\(\)\n\n        return chatRoomService\.getChatMessages\(\n            roomId = roomId,\n            before = before,\n        \)\n    \}'
)

replacement = """    @GetMapping("/{roomId}/messages")
    fun getChatMessages(
        @PathVariable("roomId") roomId: ChatRoomId,
        @RequestParam("before", required = false) before: Instant? = null,
        @RequestParam("beforeId", required = false) beforeId: UUID? = null,
    ): ChatMessageSliceDto {
        chatRoomService.getChatRoomById(roomId, requesterId)
            ?: throw ForbiddenException()

        return chatRoomService.getChatMessages(
            roomId = roomId,
            before = before,
            beforeId = beforeId,
        )
    }"""

content = pattern.sub(replacement, content)

if 'import java.util.UUID' not in content:
    content = content.replace('import java.time.Instant', 'import java.time.Instant\nimport java.util.UUID')


with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/api/controller/ChatRoomController.kt', 'w') as f:
    f.write(content)
