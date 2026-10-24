package io.github.neronguyenvn.nerochat.api.validation

import io.github.neronguyenvn.nerochat.contract.validation.Validatable
import io.github.neronguyenvn.nerochat.contract.validation.ValidationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.web.bind.WebDataBinder

class ContractRequestValidatorTest {

    private val validator = ContractRequestValidator()
    private val advice = ContractValidationAdvice(validator)

    private data class DummyError(
        override val field: String,
        override val message: String,
    ) : ValidationError

    private data class DummyValidatable(
        val name: String,
        val shouldFail: Boolean = false,
    ) : Validatable<DummyError> {
        override fun validate(): List<DummyError> =
            if (shouldFail) listOf(DummyError("name", "Name must not be empty")) else emptyList()
    }

    private data class NonValidatable(val text: String)

    @Test
    fun `supports returns true only for Validatable classes`() {
        assertTrue(validator.supports(DummyValidatable::class.java))
        assertFalse(validator.supports(NonValidatable::class.java))
        assertFalse(validator.supports(String::class.java))
    }

    @Test
    fun `validate populates binding errors when validation fails`() {
        val target = DummyValidatable(name = "", shouldFail = true)
        val errors = BeanPropertyBindingResult(target, "dummyValidatable")

        validator.validate(target, errors)

        assertTrue(errors.hasErrors())
        assertTrue(errors.hasFieldErrors("name"))
        val fieldError = errors.getFieldError("name")
        assertEquals("name", fieldError?.field)
        assertEquals("Name must not be empty", fieldError?.defaultMessage)
    }

    @Test
    fun `validate produces no errors when valid`() {
        val target = DummyValidatable(name = "Valid", shouldFail = false)
        val errors = BeanPropertyBindingResult(target, "dummyValidatable")

        validator.validate(target, errors)

        assertFalse(errors.hasErrors())
    }

    @Test
    fun `initBinder safely adds validator for validatable target`() {
        val target = DummyValidatable(name = "Valid")
        val binder = WebDataBinder(target, "dummyValidatable")

        advice.initBinder(binder)

        assertTrue(binder.validators.contains(validator))
    }

    @Test
    fun `initBinder safely skips non-validatable target without throwing IllegalStateException`() {
        val nonValidatableTarget = NonValidatable(text = "Hello")
        val binder = WebDataBinder(nonValidatableTarget, "nonValidatable")

        advice.initBinder(binder)

        assertFalse(binder.validators.contains(validator))
    }

    @Test
    fun `initBinder safely handles null target`() {
        val binder = WebDataBinder(null, "nullTarget")

        advice.initBinder(binder)

        assertFalse(binder.validators.contains(validator))
    }
}
