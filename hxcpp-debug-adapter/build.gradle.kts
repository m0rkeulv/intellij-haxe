plugins {
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// The new HXCPP debugger: a jsonrpc wire-protocol client for the
// hxcpp-debug-server running inside the debuggee, plus an in-process DAP
// adapter translating between the IDE-facing DAP surface and that protocol.
// See docs/implementation-plan.md.
dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }

    implementation(project(":dap-protocol"))
    implementation("tools.jackson.core:jackson-databind:3.1.0")

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// Integration-test fixture: a windows exe with hxcpp-debug-server compiled in.
// Needs haxe + hxcpp + a C++ toolchain; everything skips gracefully without
// them (same pattern as hashlink-debug-adapter). The port is fixed and
// non-default so tests never collide with a real debug session on 6972.
// ---------------------------------------------------------------------------
val hxcppFixturePort = 6973
val hxcppDebugServerVersion = "1.2.4" // pinned for reproducible fixture builds
val fixtureExeName = if (System.getProperty("os.name").startsWith("Windows")) "Main-debug.exe" else "Main-debug"
val hxcppFixtureExe = layout.buildDirectory.file("hxcpp/fixture/$fixtureExeName")

// probed lazily at execution time so a haxe-less machine can still configure and build the rest of the plugin
val haxeAvailable: Boolean by lazy {
    try {
        ProcessBuilder("haxe", "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start().waitFor() == 0
    } catch (e: Exception) {
        false
    }
}

tasks.register<Exec>("installHxcppDebugServerHaxelib") {
    group = "hxcpp"
    description = "Installs the pinned 'hxcpp-debug-server' haxelib compiled into the test fixture"
    onlyIf { haxeAvailable }
    commandLine = listOf("haxelib", "install", "hxcpp-debug-server", hxcppDebugServerVersion, "--quiet", "--always")
}

tasks.register<Exec>("buildHxcppFixture") {
    group = "hxcpp"
    description = "Compiles the debuggee test fixture to a native exe (build/hxcpp/fixture/$fixtureExeName)"
    onlyIf {
        if (!haxeAvailable) {
            logger.warn("SKIPPING hxcpp fixture build (haxe compiler not found on PATH); integration tests will be skipped")
        }
        haxeAvailable
    }
    dependsOn("installHxcppDebugServerHaxelib")
    workingDir = File(projectDir, "test-fixtures")
    commandLine = listOf("haxe", "fixture.hxml")
    inputs.dir("test-fixtures/src")
    inputs.file("test-fixtures/fixture.hxml")
    outputs.file(hxcppFixtureExe)
}

tasks.named<Test>("test") {
    dependsOn("buildHxcppFixture")
    // integration tests locate the built fixture and its sources through these
    systemProperty("hxcpp.fixture.exe", hxcppFixtureExe.get().asFile.absolutePath)
    systemProperty("hxcpp.fixture.port", hxcppFixturePort)
    systemProperty("hxcpp.fixture.src.dir", File(projectDir, "test-fixtures/src").absolutePath)
}
