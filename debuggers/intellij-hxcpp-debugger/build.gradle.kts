// Our own in-debuggee HXCPP debug server (haxelib: intellij-hxcpp-debug-server):
// interpreter-run unit tests for the Haxe server, gradle-built native fixtures,
// and the Java-client integration suite driving the REAL DapClient stack
// against those fixtures. See docs/implementation-plan.md (M8).

plugins {
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// No production Java: this module's product is the Haxe haxelib. The Java
// sources are the integration tests only.
dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }

    testImplementation(project(":debuggers:dap-protocol"))
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// Toolchain probing + CI gating: fixtures need haxe + hxcpp + a C++ toolchain,
// so everything skips gracefully without them (same pattern as the vshaxe
// module). The regular build/release jobs pass -PdebuggerTests=false, which
// disables this module's tests and fixture builds entirely.
// ---------------------------------------------------------------------------
val debuggerTests = providers.gradleProperty("debuggerTests").getOrElse("true").toBoolean()
val exeSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""

// probed lazily at execution time so a haxe-less machine can still configure
// and build the rest of the plugin
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

// the DAP message typedefs come from the shared :debuggers:dap-protocol
// module (haxelib "intellij-dap-protocol"); registration is idempotent
tasks.register<Exec>("registerDapProtocolHaxelib") {
    group = "hxcpp"
    description = "Points haxelib at the in-repo intellij-dap-protocol sources (haxelib dev)"
    onlyIf { haxeAvailable }
    commandLine = listOf("haxelib", "dev", "intellij-dap-protocol",
                         rootProject.file("debuggers/dap-protocol").absolutePath)
}

// the server-under-test itself: fixtures compile it in via
// `-lib intellij-hxcpp-debug-server`, so the dev registration IS the setup
tasks.register<Exec>("registerServerHaxelib") {
    group = "hxcpp"
    description = "Registers haxelib/ as the haxelib dev path for 'intellij-hxcpp-debug-server'"
    onlyIf { haxeAvailable }
    commandLine = listOf("haxelib", "dev", "intellij-hxcpp-debug-server", File(projectDir, "haxelib").absolutePath)
}

// hscript powers watch/hover/condition evaluation (M5); it is a released
// haxelib, so pull it in when absent rather than assuming a primed machine
tasks.register<Exec>("installHscript") {
    group = "hxcpp"
    description = "Installs the hscript haxelib if it is not already present"
    onlyIf { haxeAvailable }
    commandLine = listOf("haxelib", "install", "hscript", "--always", "--quiet")
    isIgnoreExitValue = true
}

tasks.register<Exec>("testHaxeServer") {
    group = "verification"
    description = "Runs the intellij-hxcpp-debug-server unit tests under the Haxe interpreter"
    dependsOn("registerDapProtocolHaxelib", "installHscript")
    workingDir = projectDir
    commandLine = listOf("haxe", "test.hxml")
    onlyIf {
        if (!haxeAvailable) logger.lifecycle("SKIPPING intellij-hxcpp-debug-server unit tests (haxe compiler not found on PATH)")
        haxeAvailable
    }
}

// ---------------------------------------------------------------------------
// Native fixtures: name -> (hxml, main class); each compiles to
// build/hxcpp/<name>/<Main>-debug(.exe) with the WORK-IN-PROGRESS server
// compiled in (haxelib dev), so the integration tests always test this tree.
// ---------------------------------------------------------------------------
val hxcppFixtures = mapOf(
    "fixture" to Pair("fixture.hxml", "Main"),
    "fixture-ex" to Pair("fixture-ex.hxml", "MainEx"),
)

fun fixtureExe(name: String) =
    layout.buildDirectory.file("hxcpp/$name/${hxcppFixtures.getValue(name).second}-debug$exeSuffix")

fun fixtureTaskName(name: String) =
    "buildHxcpp${name.split("-").joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }}Fixture"

hxcppFixtures.forEach { (name, spec) ->
    tasks.register<Exec>(fixtureTaskName(name)) {
        group = "hxcpp"
        description = "Compiles the '$name' debuggee fixture to a native exe (build/hxcpp/$name)"
        onlyIf {
            if (!debuggerTests) {
                logger.lifecycle("SKIPPING hxcpp '$name' fixture build (-PdebuggerTests=false)")
            } else if (!haxeAvailable) {
                logger.warn("SKIPPING hxcpp '$name' fixture build (haxe compiler not found on PATH); integration tests will be skipped")
            }
            debuggerTests && haxeAvailable
        }
        dependsOn("registerDapProtocolHaxelib", "registerServerHaxelib", "installHscript")
        // run from the MODULE root, not test-fixtures: haxe builds generated-file
        // paths from the cwd without normalizing, and a "test-fixtures/../" segment
        // pushes the longest generated names past Windows' 260-char MAX_PATH
        workingDir = projectDir
        commandLine = listOf("haxe", "test-fixtures/${spec.first}")
        inputs.dir("test-fixtures/src")
        inputs.file("test-fixtures/${spec.first}")
        // the fixture embeds the server sources: a haxelib change must rebuild
        inputs.dir("haxelib")
        outputs.file(fixtureExe(name))
    }
}

tasks.named<Test>("test") {
    onlyIf {
        if (!debuggerTests) {
            logger.lifecycle("SKIPPING debugger tests (-PdebuggerTests=false); the dedicated CI job runs them")
        }
        debuggerTests
    }
    hxcppFixtures.keys.forEach { name ->
        dependsOn(fixtureTaskName(name))
        // integration tests locate each built fixture through these
        systemProperty("hxcpp.server.fixture.$name.exe", fixtureExe(name).get().asFile.absolutePath)
        // the fixture (with the server compiled in) IS a test input: without
        // this, a haxelib-only change reuses a cached test result and the new
        // server is never actually exercised
        inputs.file(fixtureExe(name))
    }
    systemProperty("hxcpp.server.fixture.src.dir", File(projectDir, "test-fixtures/src").absolutePath)
}

tasks.named("check") {
    dependsOn("testHaxeServer")
}
