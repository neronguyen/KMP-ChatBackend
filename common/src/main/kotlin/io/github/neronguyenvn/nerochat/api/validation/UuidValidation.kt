package io.github.neronguyenvn.nerochat.api.validation

import io.github.neronguyenvn.nerochat.contract.validation.UuidValidator
import io.github.neronguyenvn.nerochat.domain.type.ChatMessageId
import io.github.neronguyenvn.nerochat.domain.type.ChatRoomId
import io.github.neronguyenvn.nerochat.domain.type.UserId

/**
 * Thrown when client-supplied input fails canonical UUID format validation at the HTTP boundary.
 */
class InvalidUuidException(
    val parameterName: String,
    val invalidValue: String,
) : RuntimeException("Parameter '$parameterName' must be a valid UUID, but was '$invalidValue'") {
    val clientMessage: String
        get() = "Parameter '$parameterName' must be a valid UUID"
}

/**
 * Validates that [value] conforms to canonical 8-4-4-4-12 UUID format via [UuidValidator.isValidUuid].
 *
 * @throws InvalidUuidException if [value] is not a valid UUID string.
 */
fun requireValidUuid(parameterName: String, value: String) {
    if (!UuidValidator.isValidUuid(value)) {
        throw InvalidUuidException(parameterName = parameterName, invalidValue = value)
    }
}

/** Validates that this [ChatRoomId] contains a valid UUID string. */
fun ChatRoomId.requireValidUuid(parameterName: String = "roomId"): ChatRoomId = apply {
    requireValidUuid(parameterName, value)
}

/** Validates that this [ChatMessageId] contains a valid UUID string. */
fun ChatMessageId.requireValidUuid(parameterName: String = "messageId"): ChatMessageId = apply {
    requireValidUuid(parameterName, value)
}

/** Validates that this [UserId] contains a valid UUID string. */
fun UserId.requireValidUuid(parameterName: String = "userId"): UserId = apply {
    requireValidUuid(parameterName, value)
}
