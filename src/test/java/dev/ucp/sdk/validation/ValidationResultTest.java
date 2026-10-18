/*
 * Copyright 2026 UCP Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ucp.sdk.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for the validation value types. */
class ValidationResultTest {
    private static final ValidationError MISSING =
            new ValidationError(
                    "$", "required", "$.required", "$: required property 'id' not found");
    private static final ValidationError TOO_SMALL =
            new ValidationError(
                    "$.quantity",
                    "minimum",
                    "$.properties.quantity.minimum",
                    "$.quantity: must have a minimum value of 1");

    @Test
    void emptyResultIsValidAndOrThrowReturnsItself() {
        // Arrange
        ValidationResult result = new ValidationResult("shopping/checkout.json", List.of());

        // Act
        ValidationResult returned = result.orThrow();

        // Assert
        assertTrue(result.isValid());
        assertSame(result, returned);
    }

    @Test
    void resultWithErrorsIsInvalid() {
        ValidationResult result = new ValidationResult("x.json", List.of(MISSING));

        assertFalse(result.isValid());
    }

    @Test
    void orThrowCarriesTheResultAndListsEveryViolation() {
        // Arrange
        ValidationResult result =
                new ValidationResult("shopping/checkout.json", List.of(MISSING, TOO_SMALL));

        // Act
        UcpValidationException e = assertThrows(UcpValidationException.class, result::orThrow);

        // Assert
        assertSame(result, e.getResult());
        assertEquals(
                """
                2 violation(s) of shopping/checkout.json:
                  - $: required property 'id' not found
                  - $.quantity: must have a minimum value of 1""",
                e.getMessage());
    }

    @Test
    void errorsAreDefensivelyCopiedAndImmutable() {
        // Arrange
        List<ValidationError> source = new ArrayList<>(List.of(MISSING));

        // Act
        ValidationResult result = new ValidationResult("x.json", source);
        source.add(TOO_SMALL);

        // Assert
        assertEquals(List.of(MISSING), result.errors());
        assertThrows(UnsupportedOperationException.class, () -> result.errors().add(TOO_SMALL));
    }

    @Test
    void nullErrorsAreRejected() {
        assertThrows(NullPointerException.class, () -> new ValidationResult("x.json", null));
    }

    @Test
    void errorToStringIsItsMessage() {
        assertEquals("$.quantity: must have a minimum value of 1", TOO_SMALL.toString());
    }
}
