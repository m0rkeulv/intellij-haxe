// The debugger compatibility matrix tool (`gradlew debuggerCompatibilityReport`):
// provisions the haxe/HashLink toolchains into <repo>/debuggerResources, runs
// every debugger lane against them, and writes the HTML matrix report.
// Plain JVM module - it must run anywhere the build runs (Windows AND linux),
// which is exactly why it replaced the original PowerShell runner. Fully
// self-contained: the module owns its task; nothing bleeds into the root build.

plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // tar.gz extraction (haxe linux archives); the JDK only covers zip
    implementation("org.apache.commons:commons-compress:1.26.2")
}

// Provisions toolchains (downloading what this OS has release binaries for),
// runs every debugger's test suite against each of them, and writes the
// matrix report to build/reports/debugger-matrix/index.html — the "full
// check on all our debugger work" button. See README.md for options.
tasks.register<JavaExec>("debuggerCompatibilityReport") {
    group = "verification"
    description = "Debugger compatibility matrix across provisioned haxe/HL versions + HTML report"
    mainClass = "com.intellij.plugins.haxe.matrix.MatrixMain"
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("matrix.root", rootDir.absolutePath)
    (findProperty("matrixLanes") as String?)?.let { args("--lanes=$it") }
    (findProperty("matrixResources") as String?)?.let { args("--resources=$it") }
    if ((findProperty("matrixFull") as String?)?.toBoolean() == true) args("--full")
    if ((findProperty("matrixReportOnly") as String?)?.toBoolean() == true) args("--report-only")
}
