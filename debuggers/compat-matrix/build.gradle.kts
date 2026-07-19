// The debugger compatibility matrix tool (`gradlew debuggerCompatibilityReport`):
// provisions the haxe/HashLink toolchains into <repo>/debuggerResources, runs
// every debugger lane against them, and writes the HTML matrix report.
// Plain JVM module - it must run anywhere the build runs (Windows AND linux),
// which is exactly why it replaced the original PowerShell runner.

plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // tar.gz extraction (haxe linux archives); the JDK only covers zip
    implementation("org.apache.commons:commons-compress:1.26.2")
}
