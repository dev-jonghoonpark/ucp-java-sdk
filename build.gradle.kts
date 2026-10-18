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

plugins {
    `java-library`
    `maven-publish`
    id("com.diffplug.spotless") version "8.10.4"
}

description = "Java library for the Universal Commerce Protocol (UCP)."

java {
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api(platform("com.fasterxml.jackson:jackson-bom:2.22.4"))
    api("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.networknt:json-schema-validator:1.5.9") {
        // Only needed for YAML schemas; UCP schemas are JSON.
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Silences the "no SLF4J providers" notice the validator's logging triggers in tests;
    // applications bring their own binding.
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    // Generated models document what the schemas describe; many schema properties carry no
    // description, so the "missing" group would only report the schemas' gaps.
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

// Unit tests are *Test classes; integration tests (*IT) run against every generated model and
// the specification's documented examples.
tasks.test {
    useJUnitPlatform()
    exclude("**/*IT.class")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the integration tests (*IT)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

spotless {
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        // Generated from the UCP schemas; kept in sync by the model-drift check.
        targetExclude("src/main/java/dev/ucp/sdk/models/**")
        // 1.37.0 removed JavaFormatterOptions.Style.valueOf, which Spotless 8.10.4 still calls.
        googleJavaFormat("1.36.1").aosp().reorderImports(true)
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "UCP Java SDK"
                description = project.description
                url = "https://ucp.dev"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}
