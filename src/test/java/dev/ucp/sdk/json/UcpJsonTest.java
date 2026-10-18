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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link UcpJson}, using stub models so each mapper rule is isolated. */
class UcpJsonTest {

    enum Tier {
        GOLD("gold"),
        SILVER("silver");

        private final String value;

        Tier(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    /** Shaped like a generated model: typed fields plus an any-setter for the rest. */
    static class Member {
        public String id;
        public Tier tier;
        public List<Tier> history;
        public Long points;
        public OffsetDateTime since;
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

    /** Has no any-setter, like a closed schema. */
    static class ClosedMember {
        public Tier tier;
    }

    /** Its additional properties only accept numbers, so a raw string cannot be kept. */
    static class NumericExtras {
        public Tier tier;
        private final Map<String, Long> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void setAdditionalProperty(String name, Long value) {
            extra.put(name, value);
        }

        @JsonAnyGetter
        public Map<String, Long> getAdditionalProperties() {
            return extra;
        }
    }

    @Nested
    class MapperConfiguration {
        @Test
        void absentValuesAreOmittedNotWrittenAsNull() {
            // Arrange
            Member member = new Member();
            member.id = "m_1";

            // Act
            String json = UcpJson.write(member);

            // Assert
            assertEquals("{\"id\":\"m_1\"}", json);
        }

        @Test
        void timestampsKeepTheirOffsetAsRfc3339Strings() {
            // Arrange
            String json = "{\"since\":\"2026-10-18T09:30:00+09:00\"}";

            // Act
            Member member = UcpJson.parse(json, Member.class);
            String written = UcpJson.write(member);

            // Assert
            assertEquals(9 * 3600, member.since.getOffset().getTotalSeconds());
            assertEquals(json, written);
        }

        @Test
        void unknownPropertiesNeverFailParsing() {
            // Act
            ClosedMember member =
                    UcpJson.parse("{\"tier\":\"gold\",\"surprise\":1}", ClosedMember.class);

            // Assert
            assertEquals(Tier.GOLD, member.tier);
        }

        @Test
        void unknownPropertiesAreKeptByModelsThatAcceptThem() {
            // Act
            Member member =
                    UcpJson.parse("{\"id\":\"m_1\",\"com.example.flag\":true}", Member.class);

            // Assert
            assertEquals(true, member.getAdditionalProperties().get("com.example.flag"));
            assertEquals("{\"id\":\"m_1\",\"com.example.flag\":true}", UcpJson.write(member));
        }

        @Test
        void newObjectMapperReturnsIndependentInstances() {
            // Act
            ObjectMapper custom = UcpJson.newObjectMapper();
            custom.enable(SerializationFeature.INDENT_OUTPUT);

            // Assert
            assertNotSame(UcpJson.mapper(), custom);
            assertFalse(UcpJson.mapper().isEnabled(SerializationFeature.INDENT_OUTPUT));
            assertSame(UcpJson.mapper(), UcpJson.mapper());
        }
    }

    @Nested
    class Conversions {
        @Test
        void parseFromTreeMatchesParseFromText() throws Exception {
            // Arrange
            String json = "{\"id\":\"m_1\",\"points\":10}";
            JsonNode tree = UcpJson.mapper().readTree(json);

            // Act
            Member fromTree = UcpJson.parse(tree, Member.class);

            // Assert
            assertEquals("m_1", fromTree.id);
            assertEquals(10L, fromTree.points);
        }

        @Test
        void toTreeAndWritePrettyAgreeWithWrite() throws Exception {
            // Arrange
            Member member = UcpJson.parse("{\"id\":\"m_1\",\"tier\":\"silver\"}", Member.class);

            // Act
            JsonNode tree = UcpJson.toTree(member);
            String pretty = UcpJson.writePretty(member);

            // Assert
            assertEquals("silver", tree.get("tier").asText());
            assertTrue(pretty.contains("\n"));
            assertEquals(
                    UcpJson.mapper().readTree(UcpJson.write(member)),
                    UcpJson.mapper().readTree(pretty));
        }

        @Test
        void convertReshapesBetweenModels() {
            // Arrange
            Member member = UcpJson.parse("{\"tier\":\"gold\",\"id\":\"m_1\"}", Member.class);

            // Act
            ClosedMember closed = UcpJson.convert(member, ClosedMember.class);

            // Assert
            assertEquals(Tier.GOLD, closed.tier);
        }
    }

    @Nested
    class Errors {
        @Test
        void malformedJsonIsReportedUnchecked() {
            assertThrows(UncheckedIOException.class, () -> UcpJson.parse("{\"id\":", Member.class));
            assertThrows(
                    UncheckedIOException.class,
                    () -> UcpJson.parseLenient("not json", Member.class));
        }

        @Test
        void typeMismatchIsReportedTheSameForTextAndTrees() {
            // Arrange
            String json = "{\"points\":\"many\"}";
            JsonNode tree = UcpJson.mapper().createObjectNode().put("points", "many");

            // Act + Assert
            assertThrows(UncheckedIOException.class, () -> UcpJson.parse(json, Member.class));
            assertThrows(UncheckedIOException.class, () -> UcpJson.parse(tree, Member.class));
        }

        @Test
        void unknownEnumValueFailsStrictParsing() {
            UncheckedIOException e =
                    assertThrows(
                            UncheckedIOException.class,
                            () -> UcpJson.parse("{\"tier\":\"platinum\"}", Member.class));

            assertTrue(e.getMessage().contains("platinum"));
        }

        @Test
        void writeFailureIsReportedUnchecked() {
            // Arrange: Jackson cannot serialize a bean with no properties by default.
            Object empty = new Object();

            // Act + Assert
            assertThrows(UncheckedIOException.class, () -> UcpJson.write(empty));
            assertThrows(UncheckedIOException.class, () -> UcpJson.writePretty(empty));
        }
    }

    @Nested
    class LenientParsing {
        @Test
        void unknownEnumBecomesNullAndIsKeptAsAdditionalProperty() {
            // Act
            Member member =
                    UcpJson.parseLenient("{\"id\":\"m_1\",\"tier\":\"platinum\"}", Member.class);

            // Assert
            assertNull(member.tier);
            assertEquals("platinum", member.getAdditionalProperties().get("tier"));
        }

        @Test
        void lenientRoundTripWritesTheOriginalValueBack() {
            // Arrange
            String json = "{\"id\":\"m_1\",\"tier\":\"platinum\"}";

            // Act
            String written = UcpJson.write(UcpJson.parseLenient(json, Member.class));

            // Assert
            assertEquals(json, written);
        }

        @Test
        void knownEnumValuesParseNormally() {
            Member member = UcpJson.parseLenient("{\"tier\":\"gold\"}", Member.class);

            assertEquals(Tier.GOLD, member.tier);
            assertTrue(member.getAdditionalProperties().isEmpty());
        }

        @Test
        void unknownValuesInsideEnumListsBecomeNullElements() {
            // Act
            Member member =
                    UcpJson.parseLenient("{\"history\":[\"gold\",\"bronze\"]}", Member.class);

            // Assert
            assertEquals(Arrays.asList(Tier.GOLD, null), member.history);
        }

        @Test
        void modelsWithoutAnySetterStillParse() {
            ClosedMember member =
                    UcpJson.parseLenient("{\"tier\":\"platinum\"}", ClosedMember.class);

            assertNull(member.tier);
        }

        @Test
        void additionalPropertiesThatCannotHoldStringsAreLeftAlone() {
            NumericExtras model =
                    UcpJson.parseLenient("{\"tier\":\"platinum\"}", NumericExtras.class);

            assertNull(model.tier);
            assertTrue(model.getAdditionalProperties().isEmpty());
        }

        @Test
        void lenientModeOnlyRelaxesEnums() {
            assertThrows(
                    UncheckedIOException.class,
                    () -> UcpJson.parseLenient("{\"points\":\"many\"}", Member.class));
        }

        @Test
        void lenientMapperCanBeCustomizedIndependently() throws Exception {
            // Arrange
            ObjectMapper lenient = UcpJson.newLenientObjectMapper();

            // Act
            Member member = lenient.readValue("{\"tier\":\"platinum\"}", Member.class);

            // Assert
            assertNull(member.tier);
            assertNotSame(UcpJson.mapper(), lenient);
        }
    }
}
