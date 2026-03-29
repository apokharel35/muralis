import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group   = "com.muralis"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// ── JavaFX plugin configuration ───────────────────────────────────
javafx {
    version = "21.0.5"
    modules  = listOf("javafx.controls")
}

// ── Repositories ──────────────────────────────────────────────────
repositories {
    mavenCentral()
}

// ── Dependencies ──────────────────────────────────────────────────
dependencies {
    // WebSocket client
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.11.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── Application entry point ───────────────────────────────────────
application {
    mainClass = "com.muralis.Application"

    // JVM flags — applied to both `./gradlew run` and the packaged jar
    applicationDefaultJvmArgs = listOf(
        // ── Memory ───────────────────────────────────────────────
        "-Xms256m",          // Initial heap — avoids slow ramp-up
        "-Xmx1g",            // Max heap — well within the 1GB quality constraint
                             // (see PROJECT.md Section 5)

        // ── Garbage collector ─────────────────────────────────────
        "-XX:+UseZGC",                // ZGC: sub-millisecond pause times
        "-XX:+ZGenerational",         // Generational ZGC (Java 21+)
                                      // Better throughput for short-lived objects
                                      // (RenderSnapshot arrays are all short-lived)

        // ── GC logging (dev only — remove for distribution) ───────
        "-Xlog:gc:file=logs/gc.log:time,uptime:filecount=3,filesize=10m",

        // ── JavaFX rendering pipeline ─────────────────────────────
        "-Dprism.order=es2,sw",      // Prefer OpenGL ES2 hardware pipeline;
                                     // fall back to software if GPU unavailable
        "-Dprism.vsync=true",        // Sync to monitor refresh rate (60 FPS cap)

        // ── JavaFX font rendering ─────────────────────────────────
        "-Dprism.lcdtext=false",     // Disable LCD sub-pixel text — looks better
                                     // on the dark canvas background

        // ── Module system (Java 9+ requirement for JavaFX) ────────
        "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"
    )
}

// ── Test configuration ────────────────────────────────────────────
tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "-Xms128m",
        "-Xmx512m",
        "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"
    )
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ── Fat JAR task (for distribution) ──────────────────────────────
tasks.register<Jar>("fatJar") {
    archiveClassifier = "all"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.muralis.Application"
    }
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    with(tasks.jar.get())
    // Note: JavaFX native libraries are platform-specific and cannot be
    // bundled in a fat JAR cleanly. Use `./gradlew run` for development.
    // For distribution, use jpackage (see Section 7).
}

// ── Source sets ───────────────────────────────────────────────────
sourceSets {
    main {
        java.srcDirs("src/main/java")
        resources.srcDirs("src/main/resources")
    }
    test {
        java.srcDirs("src/test/java")
        resources.srcDirs("src/test/resources")
    }
}
