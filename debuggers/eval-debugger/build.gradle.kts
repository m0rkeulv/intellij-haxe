// Debugger for haxe's EVAL target (--interp scripts and macros): a Java
// translator that will present DAP to the IDE stack while speaking the
// compiler's built-in eval-debugger JSON-RPC protocol (see
// docs/implementation-plan.md). No shipped artifact and no extra runtime:
// the debug server lives inside haxe itself (-D eval-debugger).

plugins {
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }

    implementation(project(":debuggers:dap-protocol"))

    implementation("tools.jackson.core:jackson-databind:3.1.0")

    compileOnly("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

    testImplementation(libs.junit)
}

// Debugger validation belongs to the dedicated windows CI job; the regular
// build/release jobs pass -PdebuggerTests=false (same pattern as the other
// debugger modules). The live tests also self-skip when haxe is not on PATH.
val debuggerTests = providers.gradleProperty("debuggerTests").getOrElse("true").toBoolean()

tasks.named<Test>("test") {
    onlyIf {
        if (!debuggerTests) {
            logger.lifecycle("SKIPPING eval debugger tests (-PdebuggerTests=false); the dedicated CI job runs them")
        }
        debuggerTests
    }
    // live tests locate the interp fixtures through this
    systemProperty("eval.fixture.src.dir", File(projectDir, "test-fixtures").absolutePath)
}
