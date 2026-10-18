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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import dev.ucp.sdk.UcpSchema;
import dev.ucp.sdk.json.UcpJson;

import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates JSON documents against the UCP JSON Schemas bundled with the SDK.
 *
 * <p>The generated models capture structure and types but not every rule in the specification:
 * conditional requirements ({@code if}/{@code then}), {@code contains} counts, {@code
 * dependentRequired}, patterns and numeric bounds are only enforced here. Schemas are loaded from
 * the classpath (no network access) and cached.
 *
 * <pre>{@code
 * UcpSchemaValidator validator = UcpSchemaValidator.getDefault();
 *
 * // Against the schema a model was generated from:
 * validator.validate(checkout).orThrow();
 *
 * // Or against a schema by location:
 * ValidationResult result = validator.validate("shopping/checkout_create_request.json", body);
 * }</pre>
 */
public final class UcpSchemaValidator {
    /** Base URI of the UCP schemas; {@code $id}s and locations resolve against it. */
    public static final String SCHEMA_BASE_URI = "https://ucp.dev/schemas/";

    private static final String RESOURCE_BASE = "dev/ucp/sdk/schemas/";
    private static final String CLASSPATH_BASE = "classpath:" + RESOURCE_BASE;

    private static final UcpSchemaValidator DEFAULT = new UcpSchemaValidator();

    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config;
    private final Map<String, JsonSchema> cache = new ConcurrentHashMap<>();

    private UcpSchemaValidator() {
        this.factory =
                JsonSchemaFactory.getInstance(
                        SpecVersion.VersionFlag.V202012,
                        builder ->
                                builder.schemaMappers(
                                        mappers ->
                                                mappers.mapPrefix(
                                                        SCHEMA_BASE_URI, CLASSPATH_BASE)));
        this.config =
                SchemaValidatorsConfig.builder()
                        .pathType(PathType.JSON_PATH)
                        .formatAssertionsEnabled(true)
                        .build();
    }

    /**
     * Returns the shared validator. It is thread-safe.
     *
     * @return the validator
     */
    public static UcpSchemaValidator getDefault() {
        return DEFAULT;
    }

    /**
     * Validates a model against the schema it was generated from.
     *
     * @param model a generated UCP model
     * @return the validation result
     * @throws IllegalArgumentException if the model's class carries no {@link UcpSchema}
     */
    public ValidationResult validate(Object model) {
        return validate(schemaOf(model.getClass()), UcpJson.toTree(model));
    }

    /**
     * Validates JSON text against the schema a model class was generated from.
     *
     * @param type a generated UCP model class
     * @param json the JSON text
     * @return the validation result
     */
    public ValidationResult validate(Class<?> type, String json) {
        return validate(schemaOf(type), json);
    }

    /**
     * Validates JSON text against a schema.
     *
     * @param schema the schema location relative to {@link #SCHEMA_BASE_URI}, optionally with a
     *     JSON pointer fragment, e.g. {@code shopping/checkout.json}
     * @param json the JSON text
     * @return the validation result
     * @throws IllegalArgumentException if {@code schema} is not a schema bundled with the SDK
     */
    public ValidationResult validate(String schema, String json) {
        try {
            return validate(schema, UcpJson.mapper().readTree(json));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Validates a JSON tree against a schema.
     *
     * @param schema the schema location relative to {@link #SCHEMA_BASE_URI}, optionally with a
     *     JSON pointer fragment
     * @param json the JSON tree
     * @return the validation result
     * @throws IllegalArgumentException if {@code schema} is not a schema bundled with the SDK
     */
    public ValidationResult validate(String schema, JsonNode json) {
        Set<ValidationMessage> messages = load(schema).validate(json);
        List<ValidationError> errors =
                messages.stream()
                        .map(
                                m ->
                                        new ValidationError(
                                                String.valueOf(m.getInstanceLocation()),
                                                m.getType(),
                                                String.valueOf(m.getEvaluationPath()),
                                                m.getMessage()))
                        .sorted(
                                Comparator.comparing(ValidationError::instancePath)
                                        .thenComparing(ValidationError::message))
                        .toList();
        return new ValidationResult(schema, errors);
    }

    /**
     * Returns the schema location recorded on a generated model class.
     *
     * @param type a generated UCP model class
     * @return the schema location
     * @throws IllegalArgumentException if the class carries no {@link UcpSchema}
     */
    public static String schemaOf(Class<?> type) {
        UcpSchema annotation = type.getAnnotation(UcpSchema.class);
        if (annotation == null) {
            throw new IllegalArgumentException(type.getName() + " is not a generated UCP model");
        }
        return annotation.value();
    }

    private JsonSchema load(String schema) {
        JsonSchema cached = cache.get(schema);
        if (cached != null) {
            return cached;
        }
        checkBundled(schema);
        return cache.computeIfAbsent(
                schema,
                s -> {
                    JsonSchema loaded =
                            factory.getSchema(SchemaLocation.of(SCHEMA_BASE_URI + s), config);
                    loaded.initializeValidators();
                    return loaded;
                });
    }

    /** Rejects locations that do not name a schema bundled with the SDK. */
    private static void checkBundled(String schema) {
        String path = schema.split("#", 2)[0];
        boolean bundled =
                path.endsWith(".json")
                        && !path.startsWith("/")
                        && !path.contains("..")
                        && !path.contains("\\")
                        && UcpSchemaValidator.class
                                        .getClassLoader()
                                        .getResource(RESOURCE_BASE + path)
                                != null;
        if (!bundled) {
            throw new IllegalArgumentException(
                    "Unknown UCP schema: "
                            + schema
                            + " (expected a path such as shopping/checkout.json, relative to "
                            + SCHEMA_BASE_URI
                            + ")");
        }
    }
}
