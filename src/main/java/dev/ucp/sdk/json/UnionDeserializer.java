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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deserializes a UCP {@code oneOf}/{@code anyOf} union into one of its variant classes.
 *
 * <p>A variant is ruled out when the input contradicts it: a constant-valued property
 * (discriminator such as {@code "type": "error"}) holds another value, a required property is
 * missing, or the variant is closed ({@code additionalProperties: false}) and the input carries a
 * property it does not declare. Among the remaining variants the best fit wins: first the one
 * that matched more discriminators, then the one that declares more of the input's properties,
 * then the one with more required properties; ties go to the variant declared first, mirroring
 * schema order.
 *
 * <p>Generated union interfaces subclass this with their variant table; it is not meant to be
 * used directly.
 *
 * @param <T> the union interface
 */
public abstract class UnionDeserializer<T> extends StdDeserializer<T> {
    private static final long serialVersionUID = 1L;

    private final transient List<Variant> variants;
    private transient volatile Map<Class<?>, Shape> shapes;

    protected UnionDeserializer(Class<T> unionType, List<Variant> variants) {
        super(unionType);
        this.variants = List.copyOf(variants);
    }

    /**
     * Describes one union variant.
     *
     * @param type the concrete class to deserialize into
     * @param required properties the variant's schema requires
     * @param constants property values the variant's schema pins with {@code const}
     * @return the variant descriptor
     */
    protected static Variant variant(
            Class<?> type, List<String> required, Map<String, String> constants) {
        return new Variant(type, List.copyOf(required), Map.copyOf(constants));
    }

    /** A union member: its class plus the schema facts used to recognise it. */
    public record Variant(Class<?> type, List<String> required, Map<String, String> constants) {}

    /** Properties a variant class declares, and whether it accepts undeclared ones. */
    private record Shape(Set<String> properties, boolean open) {}

    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = ctxt.readTree(p);
        Variant chosen = select(node, shapes(ctxt));
        if (chosen == null) {
            return ctxt.reportInputMismatch(
                    this,
                    "JSON does not match any variant of %s (candidates: %s)",
                    handledType().getSimpleName(),
                    variants.stream().map(v -> v.type().getSimpleName()).toList());
        }
        @SuppressWarnings("unchecked")
        T value = (T) ctxt.readTreeAsValue(node, chosen.type());
        return value;
    }

    private Map<Class<?>, Shape> shapes(DeserializationContext ctxt) {
        Map<Class<?>, Shape> result = shapes;
        if (result == null) {
            Map<Class<?>, Shape> built = new HashMap<>();
            for (Variant v : variants) {
                BeanDescription bean =
                        ctxt.getConfig().introspect(ctxt.constructType(v.type()));
                Set<String> names = new HashSet<>();
                for (BeanPropertyDefinition prop : bean.findProperties()) {
                    names.add(prop.getName());
                }
                built.put(v.type(), new Shape(names, bean.findAnySetterAccessor() != null));
            }
            result = Map.copyOf(built);
            shapes = result;
        }
        return result;
    }

    private Variant select(JsonNode node, Map<Class<?>, Shape> shapes) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Variant best = null;
        long bestScore = -1;
        for (Variant v : variants) {
            long score = score(v, shapes.get(v.type()), node);
            if (score > bestScore) {
                best = v;
                bestScore = score;
            }
        }
        return best;
    }

    private static long score(Variant v, Shape shape, JsonNode node) {
        long matchedConstants = 0;
        for (Map.Entry<String, String> c : v.constants().entrySet()) {
            JsonNode actual = node.get(c.getKey());
            if (actual == null) {
                continue;
            }
            if (!actual.isTextual() || !actual.asText().equals(c.getValue())) {
                return -1;
            }
            matchedConstants++;
        }
        for (String r : v.required()) {
            if (!node.has(r)) {
                return -1;
            }
        }
        long declared = 0;
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (shape.properties().contains(names.next())) {
                declared++;
            } else if (!shape.open()) {
                return -1;
            }
        }
        return matchedConstants * 1_000_000_000_000L + declared * 1_000_000L + v.required().size();
    }
}
