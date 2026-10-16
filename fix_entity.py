import re

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/infra/database/model/ChatMessageEntity.kt', 'r') as f:
    content = f.read()

pattern = re.compile(
    r'    @Id\n    @Column\(name = "id", nullable = false, updatable = false\)\n    private var id: UUID,'
)
replacement = """    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,"""
content = pattern.sub(replacement, content)

pattern2 = re.compile(
    r'    override fun getId\(\): UUID = id\n\n'
)
content = pattern2.sub('', content)

pattern3 = re.compile(
    r'    fun markAsSaved\(\) \{\n        this\.isNew = false\n    \}'
)
replacement3 = """    fun markAsSaved() {
        this.isNew = false
    }

    @jakarta.persistence.PostLoad
    fun markNotNew() {
        this.isNew = false
    }"""
content = pattern3.sub(replacement3, content)

with open('chat/src/main/kotlin/io/github/neronguyenvn/nerochat/chat/infra/database/model/ChatMessageEntity.kt', 'w') as f:
    f.write(content)
