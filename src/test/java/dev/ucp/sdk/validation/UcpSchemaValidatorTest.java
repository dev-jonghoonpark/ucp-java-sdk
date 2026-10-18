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

import com.fasterxml.jackson.databind.JsonNode;

import dev.ucp.sdk.UcpSchema;
import dev.ucp.sdk.json.UcpJson;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * Tests for {@link UcpSchemaValidator} against the schemas bundled in the jar. The validator has no
 * external dependency (schemas load from the classpath), so these run it for real.
 */
class UcpSchemaValidatorTest {
    private static final UcpSchemaValidator VALIDATOR = UcpSchemaValidator.getDefault();

    private static final String LINK = "common/types/link.json";

    @UcpSchema(LINK)
    static class StubLink {
        public String type;
        public String url;

        StubLink(String type, String url) {
            this.type = type;
            this.url = url;
        }
    }

    static class NotAModel {
        public String value = "x";
    }

    @Nested
    class HappyPath {
        @Test
        void validDocumentHasNoErrors() {
            // Act
            ValidationResult result =
                    VALIDATOR.validate(
                            LINK,
                            "{\"type\": \"terms_of_service\", \"url\": \"https://example.com/terms\"}");

            // Assert
            assertTrue(result.isValid(), () -> result.errors().toString());
            assertEquals(LINK, result.schema());
        }

        @Test
        void annotatedModelIsValidatedAgainstItsSchema() {
            // Act
            ValidationResult result =
                    VALIDATOR.validate(new StubLink("privacy_policy", "https://example.com/p"));

            // Assert
            assertTrue(result.isValid(), () -> result.errors().toString());
            assertEquals(LINK, result.schema());
        }

        @Test
        void schemaCanBeNamedByModelClass() {
            ValidationResult result =
                    VALIDATOR.validate(
                            StubLink.class,
                            "{\"type\": \"refund_policy\", \"url\": \"https://example.com/r\"}");

            assertTrue(result.isValid());
            assertEquals(LINK, result.schema());
        }

        @Test
        void fragmentLocationValidatesAgainstASubSchema() {
            ValidationResult valid =
                    VALIDATOR.validate(
                            "shopping/catalog_search.json#/$defs/search_request",
                            "{\"query\": \"shoes\"}");
            ValidationResult invalid =
                    VALIDATOR.validate(
                            "shopping/catalog_search.json#/$defs/search_request",
                            "{\"query\": 42}");

            assertTrue(valid.isValid(), () -> valid.errors().toString());
            assertFalse(invalid.isValid());
        }

        @Test
        void getDefaultIsASingleton() {
            assertSame(UcpSchemaValidator.getDefault(), UcpSchemaValidator.getDefault());
        }
    }

    @Nested
    class Violations {
        @Test
        void missingRequiredPropertyIsReportedAtItsParent() {
            // Act
            ValidationResult result = VALIDATOR.validate(LINK, "{\"type\": \"terms_of_service\"}");

            // Assert
            ValidationError error = result.errors().get(0);
            assertEquals("$", error.instancePath());
            assertEquals("required", error.keyword());
            assertTrue(error.message().contains("url"));
            assertFalse(error.schemaPath().isEmpty());
        }

        @Test
        void formatsAreAsserted() {
            ValidationResult result =
                    VALIDATOR.validate(
                            LINK, "{\"type\": \"terms_of_service\", \"url\": \"not a uri\"}");

            assertFalse(result.isValid());
            assertEquals("format", result.errors().get(0).keyword());
            assertEquals("$.url", result.errors().get(0).instancePath());
        }

        @Test
        void errorsAreSortedByInstancePath() {
            // Arrange: two independent violations, at $.quantity and $.item.
            String json = "{\"item\": {}, \"quantity\": 0}";

            // Act
            List<ValidationError> errors =
                    VALIDATOR
                            .validate("shopping/types/line_item_create_request.json", json)
                            .errors();

            // Assert
            List<String> paths = errors.stream().map(ValidationError::instancePath).toList();
            assertEquals(paths.stream().sorted().toList(), paths);
            assertTrue(paths.contains("$.item"));
            assertTrue(paths.contains("$.quantity"));
        }

        @Test
        void wrongRootTypeIsReported() {
            ValidationResult result = VALIDATOR.validate(LINK, "[]");

            assertFalse(result.isValid());
            assertEquals("type", result.errors().get(0).keyword());
        }
    }

    @Nested
    class BadInput {
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "shopping/chekout.json",
                    "shopping/checkout",
                    "/shopping/checkout.json",
                    "../dev/ucp/sdk/schemas/ucp.json",
                    "shopping\\checkout.json",
                    "../../META-INF/MANIFEST.MF",
                    "",
                    "#/$defs/version"
                })
        void unknownLocationsAreRejectedBeforeLoading(String location) {
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> VALIDATOR.validate(location, "{}"));

            assertTrue(e.getMessage().startsWith("Unknown UCP schema: " + location));
        }

        @Test
        void unannotatedClassIsRejected() {
            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> VALIDATOR.validate(new NotAModel()));

            assertTrue(e.getMessage().contains("is not a generated UCP model"));
        }

        @Test
        void schemaOfReadsTheAnnotation() {
            assertEquals(LINK, UcpSchemaValidator.schemaOf(StubLink.class));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> UcpSchemaValidator.schemaOf(NotAModel.class));
        }

        @Test
        void malformedJsonIsReportedUnchecked() {
            assertThrows(UncheckedIOException.class, () -> VALIDATOR.validate(LINK, "{\"type\":"));
        }
    }

    @Nested
    class Integration {
        @Test
        void repeatedValidationUsesTheCachedSchemaConsistently() {
            // Arrange
            String bad = "{\"type\": \"terms_of_service\"}";

            // Act
            ValidationResult first = VALIDATOR.validate(LINK, bad);
            ValidationResult second = VALIDATOR.validate(LINK, bad);

            // Assert
            assertEquals(first, second);
        }

        @Test
        void concurrentValidationIsThreadSafe() throws Exception {
            // Arrange: a schema not loaded by other tests, so threads race to load it.
            String schema = "shopping/types/line_item_update_request.json";
            JsonNode valid =
                    UcpJson.mapper()
                            .readTree(
                                    "{\"id\": \"li_1\", \"item\": {\"id\": \"sku\"}, \"quantity\": 1}");
            JsonNode invalid = UcpJson.mapper().readTree("{\"quantity\": -1}");
            ExecutorService pool = Executors.newFixedThreadPool(8);

            try {
                // Act
                List<Callable<Boolean>> tasks =
                        IntStream.range(0, 64)
                                .<Callable<Boolean>>mapToObj(
                                        i ->
                                                () ->
                                                        VALIDATOR
                                                                .validate(
                                                                        schema,
                                                                        i % 2 == 0
                                                                                ? valid
                                                                                : invalid)
                                                                .isValid())
                                .toList();
                List<Future<Boolean>> results = pool.invokeAll(tasks);

                // Assert
                for (int i = 0; i < results.size(); i++) {
                    assertEquals(i % 2 == 0, results.get(i).get(), "task " + i);
                }
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void validationOfAnInvalidModelThrowsWithEveryViolation() {
            // Arrange
            StubLink link = new StubLink("terms_of_service", "not a uri");

            // Act
            UcpValidationException e =
                    assertThrows(
                            UcpValidationException.class, () -> VALIDATOR.validate(link).orThrow());

            // Assert
            assertEquals(1, e.getResult().errors().size());
            assertTrue(e.getMessage().startsWith("1 violation(s) of " + LINK));
        }
    }
}
