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

import java.util.stream.Collectors;

/** Thrown by {@link ValidationResult#orThrow()} when a document violates its UCP schema. */
public class UcpValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient ValidationResult result;

    public UcpValidationException(ValidationResult result) {
        super(describe(result));
        this.result = result;
    }

    /**
     * Returns the failed validation result.
     *
     * @return the result carrying the individual errors
     */
    public ValidationResult getResult() {
        return result;
    }

    private static String describe(ValidationResult result) {
        return result.errors().size()
                + " violation(s) of "
                + result.schema()
                + ":\n"
                + result.errors().stream()
                        .map(e -> "  - " + e.message())
                        .collect(Collectors.joining("\n"));
    }
}
