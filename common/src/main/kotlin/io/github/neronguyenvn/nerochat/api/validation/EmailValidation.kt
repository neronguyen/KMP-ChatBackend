package io.github.neronguyenvn.nerochat.api.validation

import io.github.neronguyenvn.nerochat.contract.validation.EmailValidator

/**
 * Thrown when client-supplied input fails canonical email format validation at the HTTP boundary.
 */
class InvalidEmailException(
    val parameterName: String,
    val invalidValue: String,
) : RuntimeException("Parameter '$parameterName' must be a valid email, but was '$invalidValue'") {
    val clientMessage: String
        get() = "Parameter '$parameterName' must be a valid email"
}

/**
 * Validates that [value] conforms to canonical email format via [EmailValidator.isValidEmail].
 *
 * @throws InvalidEmailException if [value] is not a valid email string.
 */
fun requireValidEmail(parameterName: String, value: String) {
    if (!EmailValidator.isValidEmail(value)) {
        throw InvalidEmailException(parameterName = parameterName, invalidValue = value)
    }
}

/** Validates that this [String] contains a valid email format. */
fun String.requireValidEmail(parameterName: String = "email"): String = apply {
    requireValidEmail(parameterName, this)
}
