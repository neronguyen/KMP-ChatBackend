package io.github.neronguyenvn.nerochat.api.annotation

import io.github.neronguyenvn.nerochat.contract.error.ServerError
import kotlin.reflect.KClass

/**
 * Documents the contract [ServerError] types that an API endpoint can return.
 * Used for code documentation and OpenAPI / Postman generation.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiErrors(vararg val errors: KClass<out ServerError>)
