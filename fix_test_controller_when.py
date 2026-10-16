import re

with open('chat/src/test/kotlin/io/github/neronguyenvn/nerochat/chat/api/controller/ChatRoomControllerTest.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'`when`\(chatRoomService\.getChatMessages\(chatRoomId, null\)\)\.thenReturn\(expectedResponse\)'
)
replacement = '`when`(chatRoomService.getChatMessages(chatRoomId, null, null)).thenReturn(expectedResponse)'
content = pattern.sub(replacement, content)

pattern = re.compile(
    r'verify\(chatRoomService\)\.getChatMessages\(chatRoomId, null\)'
)
replacement = 'verify(chatRoomService).getChatMessages(chatRoomId, null, null)'
content = pattern.sub(replacement, content)

with open('chat/src/test/kotlin/io/github/neronguyenvn/nerochat/chat/api/controller/ChatRoomControllerTest.kt', 'w') as f:
    f.write(content)
