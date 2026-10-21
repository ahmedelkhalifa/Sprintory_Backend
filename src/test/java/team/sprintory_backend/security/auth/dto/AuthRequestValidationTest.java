package team.sprintory_backend.security.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthRequestValidationTest {
    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void registrationRequiresNamesValidEmailAndPasswordOfAtLeastEightCharacters() {
        var violations = validator.validate(new RegisterRequest("", "", "invalid", "1234567"));
        assertEquals(4, violations.size());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("password")));
    }

    @Test
    void validLoginWithShortPasswordIsNotRejectedByRegistrationOnlyRule() {
        assertTrue(validator.validate(new LoginRequest("a@example.com", "pass", false)).isEmpty());
    }

    @Test
    void resetPasswordRequiresTokenAndNewPasswordMinimumLength() {
        var violations = validator.validate(new ResetPasswordRequest("", "1234567"));
        assertEquals(2, violations.size());
    }

    @Test
    void forgotPasswordRequiresValidEmail() {
        assertFalse(validator.validate(new ForgotPasswordRequest("not-an-email")).isEmpty());
    }
}
