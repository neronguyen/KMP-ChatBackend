import re

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/infra/database/model/ChatMessageEntity.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'    @Transient\n    private var isNew = true\n\n    override fun getId\(\): UUID = id\n\n'
)
replacement = """    @Transient
    private var isNew = true

    override fun getId(): UUID = id

"""
content = pattern.sub(replacement, content)

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/infra/database/model/ChatMessageEntity.kt', 'w') as f:
    f.write(content)
