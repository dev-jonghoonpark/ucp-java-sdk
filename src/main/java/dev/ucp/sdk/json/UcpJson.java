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

package dev.ucp.sdk.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * JSON (de)serialization for UCP models.
 *
 * <p>The shared {@link ObjectMapper} is configured the way UCP payloads expect: RFC 3339 timestamps
 * as strings, absent values omitted rather than written as {@code null}, and timestamps keeping the
 * offset they arrived with. Unknown properties never fail parsing; models keep them in {@code
 * getAdditionalProperties()} so they survive a round trip.
 *
 * <p>Enum values the schema does not define fail parsing, as in the Python and JavaScript SDKs. A
 * peer on a newer UCP release may send values this SDK does not know yet; to accept those, parse
 * with {@link #parseLenient(String, Class)} or a {@link #newLenientObjectMapper() lenient mapper}.
 *
 * <pre>{@code
 * Checkout checkout = UcpJson.parse(body, Checkout.class);
 * String json = UcpJson.write(checkout);
 * }</pre>
 */
public final class UcpJson {
    private static final ObjectMapper MAPPER = newObjectMapper();
    private static final ObjectMapper LENIENT_MAPPER = newLenientObjectMapper();

    private UcpJson() {}

    /**
     * Creates a new mapper with the UCP configuration, for callers that want to customise it
     * further (e.g. to register it with a web framework).
     *
     * @return a new, independently configurable mapper
     */
    public static ObjectMapper newObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(
                                JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS))
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Creates a mapper that accepts enum values the schema does not define.
     *
     * <p>An unknown value leaves the enum field {@code null} and is kept, under its property name,
     * in the enclosing model's {@code getAdditionalProperties()}, so it is written back unchanged
     * and {@link dev.ucp.sdk.validation.UcpSchemaValidator} still reports it. Unknown values inside
     * a list of enums become {@code null} elements.
     *
     * @return a new lenient mapper
     */
    public static ObjectMapper newLenientObjectMapper() {
        ObjectMapper mapper = newObjectMapper();
        mapper.addHandler(new PreserveUnknownEnumValues());
        return mapper;
    }

    /**
     * Returns the shared mapper. Do not reconfigure it; use {@link #newObjectMapper()} instead.
     *
     * @return the shared mapper
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Parses JSON text into a model.
     *
     * @param json the JSON text
     * @param type the model class
     * @param <T> the model type
     * @return the parsed model
     * @throws UncheckedIOException if the JSON is malformed or does not fit the model
     */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Converts a JSON tree into a model.
     *
     * @param json the JSON tree
     * @param type the model class
     * @param <T> the model type
     * @return the converted model
     * @throws UncheckedIOException if the tree does not fit the model
     */
    public static <T> T parse(JsonNode json, Class<T> type) {
        try {
            return MAPPER.treeToValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses JSON text into a model, accepting enum values the schema does not define. See {@link
     * #newLenientObjectMapper()}.
     *
     * @param json the JSON text
     * @param type the model class
     * @param <T> the model type
     * @return the parsed model
     * @throws UncheckedIOException if the JSON is malformed or does not fit the model
     */
    public static <T> T parseLenient(String json, Class<T> type) {
        try {
            return LENIENT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Serializes a model to compact JSON.
     *
     * @param value the model
     * @return the JSON text
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Serializes a model to indented JSON.
     *
     * @param value the model
     * @return the JSON text
     */
    public static String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Converts a model to a JSON tree.
     *
     * @param value the model
     * @return the JSON tree
     */
    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * Re-reads a model as another model type. Useful for open schemas whose concrete shape is
     * chosen by a discriminator the generated type cannot express, e.g. reading a {@code
     * FulfillmentDestination} whose {@code type} is {@code shipping_address} as a {@code
     * ShippingDestination}.
     *
     * @param value the source model
     * @param type the target model class
     * @param <T> the target type
     * @return the converted model
     */
    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    /**
     * Turns an unknown enum value into {@code null} and moves the raw value into the enclosing
     * model's additional properties.
     */
    private static final class PreserveUnknownEnumValues extends DeserializationProblemHandler {
        @Override
        public Object handleWeirdStringValue(
                DeserializationContext ctxt, Class<?> targetType, String value, String failureMsg)
                throws IOException {
            if (!targetType.isEnum()) {
                return NOT_HANDLED;
            }
            JsonParser p = ctxt.getParser();
            Object model = p.getParsingContext().getCurrentValue();
            String property = p.currentName();
            if (model != null && property != null) {
                Method setter = findAdditionalPropertySetter(model.getClass());
                if (setter != null) {
                    try {
                        setter.invoke(model, property, value);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        throw new IOException("Cannot keep unknown value of " + property, e);
                    }
                }
            }
            return null;
        }

        private static Method findAdditionalPropertySetter(Class<?> type) {
            for (Method m : type.getMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (m.getName().equals("setAdditionalProperty")
                        && params.length == 2
                        && params[0] == String.class
                        && params[1].isAssignableFrom(String.class)) {
                    return m;
                }
            }
            return null;
        }
    }
}
