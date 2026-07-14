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
