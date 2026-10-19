package io.github.neronguyenvn.nerochat.api.validation

import io.github.neronguyenvn.nerochat.contract.validation.Validatable
import org.springframework.stereotype.Component
import org.springframework.validation.Errors
import org.springframework.validation.Validator
import org.springframework.web.bind.WebDataBinder
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.InitBinder

@Component
class ContractRequestValidator : Validator {

    override fun supports(clazz: Class<*>): Boolean =
        Validatable::class.java.isAssignableFrom(clazz)

    override fun validate(target: Any, errors: Errors) {
        val validatable = target as? Validatable<*> ?: return
        for (error in validatable.validate()) {
            errors.rejectValue(
                error.field,
                error::class.simpleName ?: "ValidationFailed",
                error.message,
            )
        }
    }
}

@ControllerAdvice
class ContractValidationAdvice(
    private val contractRequestValidator: ContractRequestValidator,
) {

    @InitBinder
    fun initBinder(binder: WebDataBinder) {
        val target = binder.target ?: return
        if (contractRequestValidator.supports(target.javaClass)) {
            binder.addValidators(contractRequestValidator)
        }
    }
}
