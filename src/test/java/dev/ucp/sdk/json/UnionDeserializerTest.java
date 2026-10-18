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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link UnionDeserializer}, isolated from the generated models with small stub
 * unions that exercise one selection rule each.
 */
class UnionDeserializerTest {
    private final ObjectMapper mapper = UcpJson.newObjectMapper();

    // ---- Stub union: discriminated by "kind", one closed and one open variant ----

    @JsonDeserialize(using = Shape.Deserializer.class)
    interface Shape {
        final class Deserializer extends UnionDeserializer<Shape> {
            private static final long serialVersionUID = 1L;

            public Deserializer() {
                super(
                        Shape.class,
                        List.of(
                                variant(
                                        Circle.class,
                                        List.of("kind", "radius"),
                                        Map.of("kind", "circle")),
                                variant(
                                        Square.class,
                                        List.of("kind", "side"),
                                        Map.of("kind", "square"))));
            }
        }
    }

    /** Closed variant: no any-setter. */
    @JsonDeserialize(using = JsonDeserializer.None.class)
    static class Circle implements Shape {
        public String kind;
        public Long radius;
    }

    /** Open variant that also carries an enum, for lenient-mode tests. */
    @JsonDeserialize(using = JsonDeserializer.None.class)
    static class Square implements Shape {
        public String kind;
        public Long side;
        public Color color;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void setAdditionalProperty(String name, Object value) {
            extra.put(name, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getAdditionalProperties() {
            return extra;
        }
    }

    enum Color {
        RED("red");

        private final String value;

        Color(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    // ---- Stub union without discriminators, to exercise ranking ----

    @JsonDeserialize(using = Loose.Deserializer.class)
    interface Loose {
        final class Deserializer extends UnionDeserializer<Loose> {
            private static final long serialVersionUID = 1L;

            public Deserializer() {
                super(
                        Loose.class,
                        List.of(
                                variant(OnlyA.class, List.of(), Map.of()),
                                variant(AandB.class, List.of(), Map.of()),
                                variant(AlsoOnlyA.class, List.of(), Map.of())));
            }
        }
    }

    @JsonDeserialize(using = JsonDeserializer.None.class)
    static class OpenBase implements Loose {
        private final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void setAdditionalProperty(String name, Object value) {
            extra.put(name, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getAdditionalProperties() {
            return extra;
        }
    }

    @JsonDeserialize(using = JsonDeserializer.None.class)
    static class OnlyA extends OpenBase {
        public Long a;
    }

    @JsonDeserialize(using = JsonDeserializer.None.class)
    static class AandB extends OpenBase {
        public Long a;
        public Long b;
    }

    @JsonDeserialize(using = JsonDeserializer.None.class)
    static class AlsoOnlyA extends OpenBase {
        public Long a;
    }

    // ---- Stub union: a closed variant listed before an open one ----

    @JsonDeserialize(using = Point.Deserializer.class)
    interface Point {
        final class Deserializer extends UnionDeserializer<Point> {
            private static final long serialVersionUID = 1L;

            public Deserializer() {
                super(
                        Point.class,
                        List.of(
                                variant(ClosedPoint.class, List.of("x"), Map.of()),
                                variant(OpenPoint.class, List.of("x"), Map.of())));
            }
        }
    }

    @JsonDeserialize(using = JsonDeserializer.None.class)
    static class ClosedPoint implements Point {
        public Long x;
    }

    @JsonDeserialize(using = JsonDeserializer.None.class)
    static class OpenPoint implements Point {
        public Long x;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void setAdditionalProperty(String name, Object value) {
            extra.put(name, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getAdditionalProperties() {
            return extra;
        }
    }

    static class Holder {
        public Shape shape;
    }

    @Nested
    class Selection {
        @Test
        void discriminatorPicksTheMatchingVariant() {
            // Arrange
            String circle = "{\"kind\": \"circle\", \"radius\": 3}";
            String square = "{\"kind\": \"square\", \"side\": 4}";

            // Act
            Shape first = UcpJson.parse(circle, Shape.class);
            Shape second = UcpJson.parse(square, Shape.class);

            // Assert
            assertEquals(3L, assertInstanceOf(Circle.class, first).radius);
            assertEquals(4L, assertInstanceOf(Square.class, second).side);
        }

        @Test
        void discriminatorWinsOverDeclaredPropertyCount() {
            // Arrange: fields fit Circle's shape better, but the constant says square.
            String json = "{\"kind\": \"square\", \"side\": 1, \"radius\": 9}";

            // Act
            Shape shape = UcpJson.parse(json, Shape.class);

            // Assert
            Square square = assertInstanceOf(Square.class, shape);
            assertEquals(9, square.getAdditionalProperties().get("radius"));
        }

        @Test
        void moreDeclaredPropertiesWinWithoutDiscriminators() {
            // Act
            Loose loose = UcpJson.parse("{\"a\": 1, \"b\": 2}", Loose.class);

            // Assert
            assertEquals(2L, assertInstanceOf(AandB.class, loose).b);
        }

        @Test
        void tiesGoToTheVariantDeclaredFirst() {
            // Act: OnlyA and AlsoOnlyA fit equally well.
            Loose loose = UcpJson.parse("{\"a\": 1}", Loose.class);

            // Assert
            assertInstanceOf(OnlyA.class, loose);
        }

        @Test
        void emptyObjectStillResolvesWhenNothingIsRequired() {
            // Act
            Loose loose = UcpJson.parse("{}", Loose.class);

            // Assert
            assertInstanceOf(OnlyA.class, loose);
        }

        @Test
        void closedVariantWinsWhileInputFitsIt() {
            // Act
            Point point = UcpJson.parse("{\"x\": 1}", Point.class);

            // Assert
            assertInstanceOf(ClosedPoint.class, point);
        }

        @Test
        void closedVariantIsSkippedWhenInputHasUndeclaredProperties() {
            // Act
            Point point = UcpJson.parse("{\"x\": 1, \"y\": 2}", Point.class);

            // Assert
            OpenPoint open = assertInstanceOf(OpenPoint.class, point);
            assertEquals(2, open.getAdditionalProperties().get("y"));
        }
    }

    @Nested
    class Rejection {
        @Test
        void unknownDiscriminatorValueMatchesNothing() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"kind\": \"triangle\", \"radius\": 1}", Shape.class));
        }

        @Test
        void missingRequiredPropertyRulesVariantOut() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"kind\": \"circle\"}", Shape.class));
        }

        @Test
        void nonTextualDiscriminatorDoesNotMatch() {
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("{\"kind\": 1, \"radius\": 1}", Shape.class));
        }

        @Test
        void errorNamesTheUnionAndItsCandidates() {
            MismatchedInputException e =
                    assertThrows(
                            MismatchedInputException.class,
                            () -> mapper.readValue("{\"kind\": \"triangle\"}", Shape.class));

            assertTrue(e.getMessage().contains("does not match any variant of Shape"));
            assertTrue(e.getMessage().contains("[Circle, Square]"));
        }

        @Test
        void nonObjectInputIsRejected() {
            assertThrows(
                    MismatchedInputException.class, () -> mapper.readValue("[1, 2]", Shape.class));
            assertThrows(
                    MismatchedInputException.class,
                    () -> mapper.readValue("\"circle\"", Shape.class));
        }
    }

    @Nested
    class Integration {
        @Test
        void jsonNullFieldStaysNull() {
            // Act
            Holder holder = UcpJson.parse("{\"shape\": null}", Holder.class);

            // Assert
            assertNull(holder.shape);
        }

        @Test
        void unionsInsideCollectionsResolveIndependently() throws Exception {
            // Arrange
            String json =
                    "[{\"kind\": \"square\", \"side\": 2}, {\"kind\": \"circle\", \"radius\": 5}]";

            // Act: read through an ObjectReader, so the parser's codec is a reader, not a mapper.
            List<Shape> shapes =
                    mapper.readerFor(new TypeReference<List<Shape>>() {}).readValue(json);

            // Assert
            assertInstanceOf(Square.class, shapes.get(0));
            assertInstanceOf(Circle.class, shapes.get(1));
        }

        @Test
        void variantRoundTripsWithItsUndeclaredProperties() {
            // Arrange
            String json = "{\"kind\":\"square\",\"side\":2,\"com.example.tag\":\"x\"}";

            // Act
            String written = UcpJson.write(UcpJson.parse(json, Shape.class));

            // Assert
            assertEquals(json, written);
        }

        @Test
        void lenientEnumHandlingReachesVariantsThroughTheReplay() {
            // Arrange
            String json = "{\"shape\": {\"kind\": \"square\", \"side\": 2, \"color\": \"teal\"}}";

            // Act
            Holder holder = UcpJson.parseLenient(json, Holder.class);

            // Assert
            Square square = assertInstanceOf(Square.class, holder.shape);
            assertNull(square.color);
            assertEquals("teal", square.getAdditionalProperties().get("color"));
        }

        @Test
        void strictEnumHandlingFailsInsideVariants() {
            String json = "{\"shape\": {\"kind\": \"square\", \"side\": 2, \"color\": \"teal\"}}";

            assertThrows(
                    java.io.UncheckedIOException.class, () -> UcpJson.parse(json, Holder.class));
        }
    }
}
