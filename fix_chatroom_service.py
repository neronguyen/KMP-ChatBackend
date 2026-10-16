import re

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatRoomService.kt', 'r') as f:
    content = f.read()

# Fix the duplicate ChatMessageEntity block created by sed
pattern = re.compile(r'val initialMessage = chatMessageRepository\.save\(\n\s*ChatMessageEntity\(\n\s*id = UUID\.randomUUID\(\),\n\s*ChatMessageEntity\(')
replacement = 'val initialMessage = chatMessageRepository.save(\n            ChatMessageEntity(\n                id = UUID.randomUUID(),'

content = pattern.sub(replacement, content)

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/service/ChatRoomService.kt', 'w') as f:
    f.write(content)
