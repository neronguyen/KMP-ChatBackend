with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatRoomService.kt', 'r') as f:
    content = f.read()

if 'import java.util.UUID' not in content:
    content = content.replace('import java.time.Instant', 'import java.time.Instant\nimport java.util.UUID')

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatRoomService.kt', 'w') as f:
    f.write(content)
