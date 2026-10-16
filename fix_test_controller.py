import re

with open('chat/src/test/kotlin/io/github/neronguyenvn/nerochat/chat/api/controller/ChatRoomControllerTest.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'whenever\(chatRoomService\.getChatMessages\(roomId, null\)\)\.thenReturn\(expectedResponse\)'
)
replacement = 'whenever(chatRoomService.getChatMessages(roomId, null, null)).thenReturn(expectedResponse)'
content = pattern.sub(replacement, content)

pattern = re.compile(
    r'whenever\(chatRoomService\.getChatMessages\(roomId, beforeTime\)\)\.thenReturn\(expectedResponse\)'
)
replacement = 'whenever(chatRoomService.getChatMessages(roomId, beforeTime, null)).thenReturn(expectedResponse)'
content = pattern.sub(replacement, content)

with open('chat/src/test/kotlin/io/github/neronguyenvn/nerochat/chat/api/controller/ChatRoomControllerTest.kt', 'w') as f:
    f.write(content)
