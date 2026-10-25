package io.github.neronguyenvn.nerochat.domain.type

import kotlinx.serialization.Serializable
import java.util.*

@JvmInline
@Serializable
value class ChatMessageId(val value: String) {

    /**
     * Converts [value] to a Java [UUID].
     *
     * Client-supplied IDs must be validated at the API boundary before domain conversion.
     * Throws [IllegalArgumentException] as a defensive fallback if called with an invalid UUID string.
     */
    fun asUUID(): UUID = UUID.fromString(value)
}
