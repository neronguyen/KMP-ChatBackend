package io.github.neronguyenvn.nerochat.domain.util

import io.github.neronguyenvn.nerochat.contract.validation.EmailValidator

fun normalizeEmail(email: String): String = EmailValidator.normalize(email)
