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

package dev.ucp.sdk.models;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import dev.ucp.sdk.UcpSchema;
import dev.ucp.sdk.json.UcpJson;
import dev.ucp.sdk.json.UnionDeserializer;
import dev.ucp.sdk.validation.UcpSchemaValidator;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Contract tests over every generated type, found reflectively from {@link UcpModels}. They pin
 * down what the generator promises for each kind of type, independent of any example payload.
 */
class GeneratedModelsContractIT {

    /** Every generated type: registry entries plus all types nested inside them. */
    private static Set<Class<?>> allGeneratedTypes() {
        Set<Class<?>> out = new LinkedHashSet<>();
        UcpModels.all()
                .values()
                .forEach(c -> collect(c.getEnclosingClass() != null ? topLevel(c) : c, out));
        return out;
    }

    private static Class<?> topLevel(Class<?> c) {
        while (c.getEnclosingClass() != null) {
            c = c.getEnclosingClass();
        }
        return c;
    }

    private static void collect(Class<?> c, Set<Class<?>> out) {
        if (out.add(c)) {
            for (Class<?> nested : c.getDeclaredClasses()) {
                if (!UnionDeserializer.class.isAssignableFrom(nested)) {
                    collect(nested, out);
                }
            }
        }
    }

    private static boolean isModelClass(Class<?> c) {
        return !c.isInterface()
                && !c.isEnum()
                && !Map.class.isAssignableFrom(c)
                && !Modifier.isAbstract(c.getModifiers())
                && hasPublicNoArgConstructor(c);
    }

    private static boolean hasPublicNoArgConstructor(Class<?> c) {
        try {
            return Modifier.isPublic(c.getConstructor().getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Object newInstance(Class<?> c) throws ReflectiveOperationException {
        return c.getConstructor().newInstance();
    }

    private static String name(Class<?> c) {
        return c.getName().substring(c.getPackageName().length() + 1);
    }

    @Test
    void schemaVersionIsAUcpReleaseDate() {
        assertTrue(
                UcpModels.SCHEMA_VERSION.matches("\\d{4}-\\d{2}-\\d{2}"), UcpModels.SCHEMA_VERSION);
    }

    @Test
    void registryIsNonEmptyAndUnmodifiable() {
        assertTrue(UcpModels.all().size() > 100);
        assertThrows(
                UnsupportedOperationException.class,
                () -> UcpModels.all().put("x.json", Object.class));
        assertTrue(UcpModels.forSchema("does/not/exist.json").isEmpty());
    }

    @TestFactory
    Stream<DynamicTest> registryEntriesPointAtTheirSchema() {
        return UcpModels.all().entrySet().stream()
                .map(
                        e ->
                                DynamicTest.dynamicTest(
                                        e.getKey(),
                                        () -> {
                                            UcpSchema annotation =
                                                    e.getValue().getAnnotation(UcpSchema.class);
                                            assertNotNull(
                                                    annotation,
                                                    name(e.getValue()) + " lacks @UcpSchema");
                                            // Either the class's own location, or an alias $def
                                            // resolving to it.
                                            assertEquals(
                                                    e.getValue(),
                                                    UcpModels.forSchema(annotation.value())
                                                            .orElseThrow());
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> everyRegisteredSchemaLoadsInTheValidator() {
        return UcpModels.all().keySet().stream()
                .map(
                        location ->
                                DynamicTest.dynamicTest(
                                        location,
                                        () ->
                                                assertDoesNotThrow(
                                                        () ->
                                                                UcpSchemaValidator.getDefault()
                                                                        .validate(
                                                                                location, "{}"))));
    }

    @TestFactory
    Stream<DynamicTest> modelClassesRoundTripAndHonourEquality() {
        return allGeneratedTypes().stream()
                .filter(GeneratedModelsContractIT::isModelClass)
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        name(c),
                                        () -> {
                                            // Arrange
                                            Object empty = newInstance(c);

                                            // Act
                                            String json = UcpJson.write(empty);
                                            Object back = UcpJson.parse(json, c);

                                            // Assert
                                            assertEquals(empty, back, json);
                                            assertEquals(empty.hashCode(), back.hashCode());
                                            assertEquals(empty, empty);
                                            assertNotEquals(empty, null);
                                            assertNotEquals(empty, new Object());
                                            assertTrue(
                                                    empty.toString()
                                                            .startsWith(c.getSimpleName() + "{"));
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> additionalPropertiesAffectEqualityAndSerialization() {
        return allGeneratedTypes().stream()
                .filter(GeneratedModelsContractIT::isModelClass)
                .filter(c -> findMethod(c, "additionalProperty") != null)
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        name(c),
                                        () -> {
                                            // Arrange
                                            Object plain = newInstance(c);
                                            Object extended = newInstance(c);
                                            Method fluent = findMethod(c, "additionalProperty");
                                            if (!fluent.getParameterTypes()[1].isAssignableFrom(
                                                    String.class)) {
                                                return; // typed additional properties; covered by
                                                // spec examples
                                            }

                                            // Act
                                            Object returned =
                                                    fluent.invoke(extended, "com.example.x", "y");

                                            // Assert
                                            assertTrue(
                                                    returned == extended,
                                                    "fluent setter returns this");
                                            assertNotEquals(plain, extended);
                                            assertEquals(
                                                    "y",
                                                    UcpJson.toTree(extended)
                                                            .get("com.example.x")
                                                            .asText());
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> requiredConstantsArePrefilled() {
        return allGeneratedTypes().stream()
                .filter(GeneratedModelsContractIT::isModelClass)
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        name(c),
                                        () -> {
                                            Object empty = newInstance(c);
                                            var tree = UcpJson.toTree(empty);
                                            // Whatever an empty instance writes must be a constant
                                            // from its schema:
                                            // only String fields are ever pre-filled.
                                            tree.properties()
                                                    .forEach(
                                                            f ->
                                                                    assertTrue(
                                                                            f.getValue()
                                                                                    .isTextual(),
                                                                            f.getKey()
                                                                                    + " written for an empty "
                                                                                    + c
                                                                                            .getSimpleName()));
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> enumsRoundTripEveryConstant() {
        return allGeneratedTypes().stream()
                .filter(Class::isEnum)
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        name(c),
                                        () -> {
                                            Method fromValue =
                                                    c.getMethod("fromValue", String.class);
                                            for (Object constant : c.getEnumConstants()) {
                                                String wire =
                                                        (String)
                                                                c.getMethod("value")
                                                                        .invoke(constant);
                                                assertTrue(
                                                        c.getMethod("value")
                                                                .isAnnotationPresent(
                                                                        JsonValue.class));
                                                assertEquals(
                                                        constant, fromValue.invoke(null, wire));
                                                assertEquals(wire, constant.toString());
                                                assertEquals(
                                                        "\"" + wire + "\"",
                                                        UcpJson.write(constant));
                                                assertEquals(
                                                        constant,
                                                        UcpJson.parse("\"" + wire + "\"", c));
                                            }
                                            InvocationTargetException unknown =
                                                    assertThrows(
                                                            InvocationTargetException.class,
                                                            () ->
                                                                    fromValue.invoke(
                                                                            null,
                                                                            "__not_a_value__"));
                                            assertTrue(
                                                    unknown.getCause()
                                                            instanceof IllegalArgumentException);
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> unionsDeclareAWorkingDeserializerAndVariantsOptOut() {
        return allGeneratedTypes().stream()
                .filter(Class::isInterface)
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        name(c),
                                        () -> {
                                            JsonDeserialize annotation =
                                                    c.getAnnotation(JsonDeserialize.class);
                                            assertNotNull(
                                                    annotation, "union lacks @JsonDeserialize");
                                            Class<?> deserializer = annotation.using();
                                            assertTrue(
                                                    UnionDeserializer.class.isAssignableFrom(
                                                            deserializer));
                                            assertDoesNotThrow(
                                                    () ->
                                                            deserializer
                                                                    .getConstructor()
                                                                    .newInstance());

                                            for (Class<?> variant : allGeneratedTypes()) {
                                                if (variant != c
                                                        && c.isAssignableFrom(variant)
                                                        && !variant.isInterface()) {
                                                    JsonDeserialize own =
                                                            variant.getAnnotation(
                                                                    JsonDeserialize.class);
                                                    assertNotNull(
                                                            own,
                                                            name(variant)
                                                                    + " must opt out of "
                                                                    + name(c));
                                                    assertEquals(
                                                            JsonDeserializer.None.class,
                                                            own.using());
                                                }
                                            }
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> namedMapsAreUsableAsMaps() {
        return allGeneratedTypes().stream()
                .filter(c -> LinkedHashMap.class.isAssignableFrom(c))
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        name(c),
                                        () -> {
                                            Object map = newInstance(c);
                                            assertEquals("{}", UcpJson.write(map));
                                            assertEquals(map, UcpJson.parse("{}", c));
                                            assertNotNull(c.getAnnotation(UcpSchema.class));
                                        }));
    }

    @Test
    void typesAreFoundForEveryKind() {
        Set<Class<?>> types = allGeneratedTypes();

        assertTrue(types.stream().anyMatch(Class::isEnum));
        assertTrue(types.stream().anyMatch(Class::isInterface));
        assertTrue(types.stream().anyMatch(c -> LinkedHashMap.class.isAssignableFrom(c)));
        assertFalse(
                types.stream().filter(GeneratedModelsContractIT::isModelClass).toList().isEmpty());
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 2) {
                return m;
            }
        }
        return null;
    }
}
