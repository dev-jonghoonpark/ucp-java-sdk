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

package dev.ucp.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ucp.sdk.json.UcpJson;
import dev.ucp.sdk.models.UcpModels;
import dev.ucp.sdk.validation.UcpSchemaValidator;
import dev.ucp.sdk.validation.ValidationError;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every annotated JSON example from the UCP specification docs through the SDK.
 *
 * <p>The corpus ({@code examples.json}) is exported by {@code codegen/export_examples.py} with
 * the specification's own example reducer, so it tracks the docs of the pinned UCP release.
 */
class SpecExamplesTest {
    private static final UcpSchemaValidator VALIDATOR = UcpSchemaValidator.getDefault();

    /**
     * One documentation example.
     *
     * @param exact false when a request example is checked against the response schema because
     *     the schema defines no request variant; the response schema can require more
     */
    record Example(
            String source, String schema, boolean exact, List<String> elided, JsonNode payload) {}

    static List<Example> examples() throws IOException {
        try (InputStream in = SpecExamplesTest.class.getResourceAsStream("/examples.json")) {
            JsonNode root = UcpJson.mapper().readTree(Objects.requireNonNull(in));
            List<Example> out = new ArrayList<>();
            for (JsonNode e : root) {
                List<String> elided = new ArrayList<>();
                e.get("elided").forEach(p -> elided.add(p.asText()));
                out.add(new Example(e.get("source").asText(), e.get("schema").asText(),
                        e.get("exact").asBoolean(), elided, e.get("payload")));
            }
            return out;
        }
    }

    @TestFactory
    Stream<DynamicTest> everyExampleHasAModel() throws IOException {
        return examples().stream()
                .map(e -> DynamicTest.dynamicTest(e.source() + " " + e.schema(), () ->
                        assertTrue(UcpModels.forSchema(e.schema()).isPresent(),
                                "no model generated for " + e.schema())));
    }

    @TestFactory
    Stream<DynamicTest> examplesRoundTripThroughModels() throws IOException {
        return examples().stream()
                .map(e -> DynamicTest.dynamicTest(e.source() + " " + e.schema(), () -> {
                    Class<?> type = UcpModels.forSchema(e.schema()).orElseThrow();
                    Object model = UcpJson.parse(e.payload(), type);
                    assertEquals(type, model.getClass());
                    JsonNode written = UcpJson.toTree(model);
                    String diff = diff("$", e.payload(), written);
                    if (diff != null) {
                        fail("round trip changed the payload at " + diff
                                + "\nexpected: " + e.payload() + "\nactual:   " + written);
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> examplesValidateAgainstBundledSchemas() throws IOException {
        return examples().stream()
                .filter(Example::exact)
                .map(e -> DynamicTest.dynamicTest(e.source() + " " + e.schema(), () -> {
                    List<ValidationError> errors = VALIDATOR.validate(e.schema(), e.payload())
                            .errors().stream()
                            .filter(err -> !isElided(toPointer(err.instancePath()), e.elided()))
                            .toList();
                    if (!errors.isEmpty()) {
                        fail(errors.size() + " violation(s): " + errors);
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> modelsValidateAfterRoundTrip() throws IOException {
        return examples().stream()
                .filter(e -> e.exact() && e.elided().isEmpty())
                .map(e -> DynamicTest.dynamicTest(e.source() + " " + e.schema(), () -> {
                    Object model = UcpJson.parse(
                            e.payload(), UcpModels.forSchema(e.schema()).orElseThrow());
                    List<ValidationError> errors = VALIDATOR.validate(model).errors();
                    assertTrue(errors.isEmpty(), () -> "violations: " + errors);
                }));
    }

    private static boolean isElided(String pointer, List<String> elided) {
        return elided.stream().anyMatch(p -> pointer.equals(p) || pointer.startsWith(p + "/"));
    }

    /** Converts the validator's JSONPath ({@code $.a['b.c'][0]}) to a JSON pointer. */
    static String toPointer(String jsonPath) {
        StringBuilder out = new StringBuilder();
        int i = 1; // skip '$'
        while (i < jsonPath.length()) {
            char c = jsonPath.charAt(i);
            if (c == '.') {
                int j = i + 1;
                while (j < jsonPath.length() && jsonPath.charAt(j) != '.'
                        && jsonPath.charAt(j) != '[') {
                    j++;
                }
                out.append('/').append(escape(jsonPath.substring(i + 1, j)));
                i = j;
            } else if (c == '[' && jsonPath.charAt(i + 1) == '\'') {
                int j = jsonPath.indexOf("']", i);
                out.append('/').append(escape(jsonPath.substring(i + 2, j)));
                i = j + 2;
            } else if (c == '[') {
                int j = jsonPath.indexOf(']', i);
                out.append('/').append(jsonPath, i + 1, j);
                i = j + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Compares JSON trees, treating numerically equal numbers and equal instants as the same
     * (a {@code Double} field writes {@code 5} back as {@code 5.0}; an {@code OffsetDateTime}
     * drops redundant fractional zeros).
     *
     * @return the path of the first difference, or {@code null} if equivalent
     */
    static String diff(String path, JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0 ? null : path;
        }
        if (expected.isTextual() && actual.isTextual()) {
            if (expected.asText().equals(actual.asText())) {
                return null;
            }
            try {
                return OffsetDateTime.parse(expected.asText())
                        .isEqual(OffsetDateTime.parse(actual.asText())) ? null : path;
            } catch (DateTimeParseException e) {
                return path;
            }
        }
        if (expected instanceof ObjectNode eo && actual instanceof ObjectNode ao) {
            for (Map.Entry<String, JsonNode> f : eo.properties()) {
                JsonNode other = ao.get(f.getKey());
                if (other == null) {
                    return f.getValue().isNull() ? null : path + "." + f.getKey() + " (dropped)";
                }
                String d = diff(path + "." + f.getKey(), f.getValue(), other);
                if (d != null) {
                    return d;
                }
            }
            for (Map.Entry<String, JsonNode> f : ao.properties()) {
                if (!eo.has(f.getKey())) {
                    return path + "." + f.getKey() + " (added)";
                }
            }
            return null;
        }
        if (expected instanceof ArrayNode ea && actual instanceof ArrayNode aa) {
            if (ea.size() != aa.size()) {
                return path + " (size)";
            }
            for (int i = 0; i < ea.size(); i++) {
                String d = diff(path + "[" + i + "]", ea.get(i), aa.get(i));
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        return expected.equals(actual) ? null : path;
    }
}
