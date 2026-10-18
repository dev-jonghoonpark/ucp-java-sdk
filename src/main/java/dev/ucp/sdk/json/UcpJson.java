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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.UncheckedIOException;

/**
 * JSON (de)serialization for UCP models.
 *
 * <p>The shared {@link ObjectMapper} is configured the way UCP payloads expect: RFC 3339
 * timestamps as strings, absent values omitted rather than written as {@code null}, and
 * timestamps keeping the offset they arrived with. Unknown properties never fail parsing; models
 * keep them in {@code getAdditionalProperties()} so they survive a round trip.
 *
 * <pre>{@code
 * Checkout checkout = UcpJson.parse(body, Checkout.class);
 * String json = UcpJson.write(checkout);
 * }</pre>
 */
public final class UcpJson {
    private static final ObjectMapper MAPPER = newObjectMapper();

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
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
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
     * @throws IllegalArgumentException if the tree does not fit the model
     */
    public static <T> T parse(JsonNode json, Class<T> type) {
        return MAPPER.convertValue(json, type);
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
     * chosen by a discriminator the generated type cannot express, e.g. reading a
     * {@code FulfillmentDestination} whose {@code type} is {@code shipping_address} as a
     * {@code ShippingDestination}.
     *
     * @param value the source model
     * @param type the target model class
     * @param <T> the target type
     * @return the converted model
     */
    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }
}
