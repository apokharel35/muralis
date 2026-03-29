# BUILD.md — Muralis

> This file defines the complete Gradle build configuration for Muralis.
> It is the authoritative reference for all dependency coordinates,
> JVM flags, module configuration, and run instructions.
>
> **Claude Code instruction:** When generating `build.gradle.kts`,
> `settings.gradle.kts`, or any Gradle wrapper file, use the exact
> versions and coordinates specified in this file. Do not substitute
> newer or older versions without an explicit ADR in
> `ARCHITECTURE.md` Section 8.

---

## 1. Project identity

```
Project name:     muralis
Group:            com.muralis
Version:          0.1.0-SNAPSHOT
Java version:     21
Gradle version:   8.7
```

---

## 2. Dependency manifest

All five runtime dependencies. No others are permitted in Phase 1.

| Dependency | Coordinate | Version | Purpose |
|---|---|---|---|
| Java-WebSocket | `org.java-websocket:Java-WebSocket` | `1.6.0` | WebSocket client |
| Gson | `com.google.code.gson:gson` | `2.11.0` | JSON parsing |
| JavaFX Controls | `org.openjfx:javafx-controls` | `21.0.5` | UI framework |
| JavaFX FXML | ~~excluded~~ | — | Not used — no FXML in Muralis |
| SLF4J API | `org.slf4j:slf4j-api` | `2.0.13` | Logging API |
| Logback Classic | `ch.qos.logback:logback-classic` | `1.5.6` | Logging implementation |

Test dependencies:

| Dependency | Coordinate | Version | Purpose |
|---|---|---|---|
| JUnit 5 | `org.junit.jupiter:junit-jupiter` | `5.10.3` | Unit testing |
| AssertJ | `org.assertj:assertj-core` | `3.26.0` | Fluent assertions |

**JavaFX transitive dependencies:** Declaring `javafx-controls` pulls in
`javafx-graphics` and `javafx-base` transitively. No additional JavaFX
modules need to be declared explicitly.

**Java-WebSocket transitive dependency:** Pulls in `org.slf4j:slf4j-api`.
This is the same SLF4J version we declare — Gradle will resolve to a
single version. No conflict.

---

## 3. `settings.gradle.kts`

```kotlin
rootProject.name = "muralis"
```

---

## 4. `build.gradle.kts`

```kotlin
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
```

---

## 5. Module system — `module-info.java`

Muralis does NOT use the Java Module System (JPMS) in Phase 1.
`module-info.java` is intentionally absent.

**Rationale:** JPMS requires explicit `requires` declarations for every
dependency and `opens` declarations for reflection. Java-WebSocket,
Gson, and Logback all have incomplete or missing `module-info.java`
files, which causes `--module-path` resolution failures. The `--add-opens`
flag in `applicationDefaultJvmArgs` handles the one JavaFX reflection
requirement without JPMS.

**Phase 2 consideration:** If native image packaging via GraalVM is
pursued, revisit JPMS at that point with a dedicated configuration pass.

---

## 6. Project directory structure

Create this structure before running any Gradle tasks:

```
muralis/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/muralis/
│   │   │       ├── Application.java
│   │   │       ├── model/
│   │   │       ├── provider/
│   │   │       ├── ingestion/
│   │   │       ├── engine/
│   │   │       └── ui/
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── services/
│   │       │       └── com.muralis.provider.MarketDataProvider
│   │       └── logback.xml
│   └── test/
│       ├── java/
│       │   └── com/muralis/
│       │       ├── engine/
│       │       └── ingestion/
│       └── resources/
│
├── logs/                        ← created at runtime by GC log config
│
├── PROJECT.md
├── DATA-CONTRACTS.md
├── ARCHITECTURE.md
├── SPEC-ingestion.md
├── SPEC-engine.md
├── SPEC-rendering.md
├── SPEC-provider-spi.md
└── BUILD.md
```

---

## 7. Logback configuration — `logback.xml`

Place at `src/main/resources/logback.xml`:

```xml
<configuration>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} — %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Ingestion layer: INFO in normal operation, DEBUG when diagnosing
         bootstrap or gap detection issues -->
    <logger name="com.muralis.ingestion" level="INFO"/>

    <!-- Engine layer: WARN only — DEBUG is very noisy at 100 events/sec -->
    <logger name="com.muralis.engine" level="WARN"/>

    <!-- UI layer: WARN only -->
    <logger name="com.muralis.ui" level="WARN"/>

    <!-- Java-WebSocket library: suppress its internal INFO spam -->
    <logger name="org.java_websocket" level="WARN"/>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

</configuration>
```

**To enable DEBUG for bootstrap diagnostics**, change the ingestion
logger level to `DEBUG` temporarily:
```xml
<logger name="com.muralis.ingestion" level="DEBUG"/>
```

---

## 8. Gradle wrapper configuration

`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Generate the wrapper files by running (requires Gradle 8.7 installed locally):
```bash
gradle wrapper --gradle-version 8.7
```

Or bootstrap from scratch without a local Gradle install by copying the
wrapper JAR from any existing Gradle 8.x project.

---

## 9. Common Gradle tasks

| Task | Command | Description |
|---|---|---|
| Build | `./gradlew build` | Compile + test |
| Run | `./gradlew run` | Launch Muralis with correct JVM flags |
| Test | `./gradlew test` | Run all JUnit 5 tests |
| Test report | `./gradlew test --info` | Verbose test output |
| Clean | `./gradlew clean` | Delete build directory |
| Fat JAR | `./gradlew fatJar` | Assemble `build/libs/muralis-0.1.0-SNAPSHOT-all.jar` |
| Dependency tree | `./gradlew dependencies --configuration runtimeClasspath` | Verify no unwanted transitive deps |

**Always use `./gradlew run` for development** — not `java -jar`. The
`run` task applies all JVM flags from `applicationDefaultJvmArgs` and
correctly resolves the JavaFX native libraries for the current platform.

---

## 10. Platform-specific notes

The JavaFX Gradle plugin (`org.openjfx.javafxplugin`) automatically
selects the correct JavaFX native libraries for the current OS and
architecture. No manual classifier selection is needed.

Tested platforms for Phase 1:

| Platform | JDK | Status |
|---|---|---|
| macOS (Apple Silicon) | Temurin 21 | Supported |
| macOS (Intel) | Temurin 21 | Supported |
| Windows 10/11 (x64) | Temurin 21 | Supported |
| Ubuntu 22.04 (x64) | Temurin 21 | Supported — requires `libgtk-3` |

**Linux requirement:** On Ubuntu/Debian, install GTK3 before running:
```bash
sudo apt-get install libgtk-3-0 libglib2.0-0
```

---

## 11. JVM flag rationale (reference)

| Flag | Why |
|---|---|
| `-Xms256m` | Prevents slow heap ramp-up during bootstrap sequence |
| `-Xmx1g` | Hard ceiling per PROJECT.md quality constraint |
| `-XX:+UseZGC` | Sub-millisecond GC pauses — prevents frame drops at 60 FPS |
| `-XX:+ZGenerational` | Improves throughput for the short-lived `RenderSnapshot` arrays that are allocated and discarded at 100/sec |
| `-Dprism.order=es2,sw` | Forces JavaFX to use OpenGL hardware rendering; software fallback for CI/headless environments |
| `-Dprism.vsync=true` | Caps `AnimationTimer` at monitor refresh rate — no wasted frames above 60 FPS |
| `-Dprism.lcdtext=false` | LCD sub-pixel rendering is designed for light backgrounds — disabled for the dark canvas theme |

---

## 12. First-run checklist

Before writing any code, verify the build environment is correct:

- [ ] `java --version` shows `openjdk 21` (or compatible JDK 21 build)
- [ ] `./gradlew --version` shows `Gradle 8.7`
- [ ] `./gradlew dependencies --configuration runtimeClasspath` shows
      exactly 5 runtime dependencies (Java-WebSocket, Gson, SLF4J,
      Logback, JavaFX) with no Spring, no Tomcat, no Jakarta EE
- [ ] `./gradlew build` succeeds on an empty `Application.java` stub
- [ ] `./gradlew run` opens a blank JavaFX window (smoke test before
      any ingestion or engine code is written)

---

*Last updated: BUILD.md v1.0 — build configuration locked for Phase 1.*
*Spec chain complete. All 8 files written. Ready for Claude Code generation.*
