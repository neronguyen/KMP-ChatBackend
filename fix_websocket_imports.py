with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/api/websocket/ChatWebSocketHandler.kt', 'r') as f:
    content = f.read()

if 'import kotlinx.serialization.encodeToString' not in content:
    content = content.replace('import kotlinx.serialization.json.Json', 'import kotlinx.serialization.json.Json\nimport kotlinx.serialization.encodeToString')

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/api/websocket/ChatWebSocketHandler.kt', 'w') as f:
    f.write(content)
