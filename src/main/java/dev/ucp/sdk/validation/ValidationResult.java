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

import java.util.List;

/**
 * The outcome of validating a document against a UCP schema.
 *
 * @param schema the schema location that was validated against
 * @param errors the violations found; empty when the document is valid
 */
public record ValidationResult(String schema, List<ValidationError> errors) {
    public ValidationResult {
        errors = List.copyOf(errors);
    }

    /**
     * Returns whether the document satisfied the schema.
     *
     * @return {@code true} if there are no errors
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * Throws when the document is invalid.
     *
     * @return this result, for chaining
     * @throws UcpValidationException if there are errors
     */
    public ValidationResult orThrow() {
        if (!isValid()) {
            throw new UcpValidationException(this);
        }
        return this;
    }
}
