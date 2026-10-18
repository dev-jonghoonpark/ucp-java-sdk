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

/**
 * One JSON Schema violation.
 *
 * @param instancePath JSONPath of the offending value in the validated document, e.g. {@code
 *     $.line_items[0].quantity}
 * @param keyword the JSON Schema keyword that failed, e.g. {@code required} or {@code minimum}
 * @param schemaPath where the failing keyword sits in the schema
 * @param message a human-readable description
 */
public record ValidationError(
        String instancePath, String keyword, String schemaPath, String message) {
    @Override
    public String toString() {
        return message;
    }
}
