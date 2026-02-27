// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    idea
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.kotlinx.benchmark") version "0.4.15"
    id("org.springframework.boot") version "4.0.1"
}

val kotlinVersion by extra("2.3.0")

idea { module { inheritOutputDirs = true } }

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.BELLSOFT)
    }
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
         jvmTarget.set(JvmTarget.JVM_25)
        jvmToolchain(25)
    }
    sourceSets.all { languageSettings { languageVersion = "2.3" } }
}

tasks.withType<Zip> { // required for kotlinx.benchmark
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    isZip64 = true
}

dependencies {
    val springBootVersion = "4.0.1"
    val crawler4jVersion = "5.1.4"
    val kotlinVersion = project.extra["kotlinVersion"].toString()

    implementation("org.eclipse.angus:jakarta.mail:2.0.5")

    testImplementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime-jvm:0.4.15")
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
    testImplementation("io.ktor:ktor-client-cio:3.4.0")

    // implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    // implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

    implementation("de.hs-heilbronn.mi:crawler4j-core:$crawler4jVersion") {
        // breaks new crawler4j
        exclude(group = "org.apache.logging.log4j")
    }
    implementation("org.apache.tika:tika-parser-pdf-module:3.2.1")
    implementation("de.hs-heilbronn.mi:crawler4j-frontier-sleepycat:$crawler4jVersion")
    implementation("com.github.crawler-commons:crawler-commons:1.6")
    implementation("com.rometools:rome:2.1.0")

    implementation("org.springframework.boot:spring-boot-starter-webflux:$springBootVersion") {
        // Exclude Jackson 3.x from Spring Boot 4.x - we use Jackson 2.x with Kotlin module
        // exclude(group = "tools.jackson.core")
        // exclude(group = "tools.jackson")
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-jackson")
        exclude(group = "org.springframework.boot", module = "spring-boot-jackson")
    }
    implementation("org.springframework.boot:spring-boot-starter-reactor-netty:$springBootVersion")
    // Force Netty 4.1.x to avoid ByteBuf leak issues in 4.2.x with Spring Boot 4.0.1
    // See: https://github.com/spring-projects/spring-boot/issues/48765
    implementation(platform("io.netty:netty-bom:4.1.130.Final")) // upgrade again or just remove this line, some time later

    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion") {
        exclude(group = "org.mockito") // avoids deprecation issues that is reported as "error"
    }
    testImplementation("org.springframework.boot:spring-boot-webflux-test:$springBootVersion")

    // Jackson 3.x stack
    implementation("tools.jackson.module:jackson-module-kotlin:3.0.3")

    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jsoup:jsoup:1.22.1")

    // SpringDoc OpenAPI 3 for API documentation (v3.x required for Spring Boot 4.x)
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.1")
}

benchmark {
    configurations {
        named("main") {
            warmups = 2
            iterations = 5
            includes = mutableListOf("BenchmarkOkHttpClientTest")
        }
    }
    targets {
        register("test") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
    }
}
