plugins {
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Generic Debug Adapter Protocol (DAP) client, transport and message types.
// Shared by the HashLink and HXCPP debuggers; must stay free of IDE and
// debugger-specific dependencies.
dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }

    implementation("tools.jackson.core:jackson-databind:3.1.0")

    compileOnly("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

    testImplementation(libs.junit)
}

// Debugger tests are OPT-IN (-PdebuggerTests=true): most plugin work does not
// touch the debuggers, and the compat-matrix tool passes the flag itself.
// Compilation still runs in every build.
tasks.named<Test>("test") {
    onlyIf {
        val enabled = providers.gradleProperty("debuggerTests").getOrElse("false").toBoolean()
        if (!enabled) {
            logger.lifecycle("SKIPPING debugger tests (opt in with -PdebuggerTests=true)")
        }
        enabled
    }
}
