import re

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/infra/database/model/ChatMessageEntity.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'    @Id\n    @Column\(name = "id", nullable = false, updatable = false\)\n    val id: UUID,'
)
replacement = """    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("getCustomId")
    val id: UUID,"""
content = pattern.sub(replacement, content)

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/infra/database/model/ChatMessageEntity.kt', 'w') as f:
    f.write(content)
