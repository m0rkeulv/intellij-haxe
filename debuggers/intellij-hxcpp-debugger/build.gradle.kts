// Our own in-debuggee HXCPP debug server (haxelib: intellij-hxcpp-debug-server).
// M0: unit tests under the Haxe interpreter; native fixture builds arrive with
// the wire/breakpoint milestones (see docs/implementation-plan.md).

plugins {
    base
}

// probed at execution time so a haxe-less machine still builds the plugin
fun haxeOnPath(): Boolean = try {
    ProcessBuilder("haxe", "--version").redirectErrorStream(true).start().waitFor() == 0
} catch (e: Exception) {
    false
}

// the DAP message typedefs come from the shared :debuggers:dap-protocol
// module (haxelib "intellij-dap-protocol"); registration is idempotent
tasks.register<Exec>("registerDapProtocolHaxelib") {
    group = "hxcpp"
    description = "Points haxelib at the in-repo intellij-dap-protocol sources (haxelib dev)"
    onlyIf { haxeOnPath() }
    commandLine = listOf("haxelib", "dev", "intellij-dap-protocol",
                         rootProject.file("debuggers/dap-protocol").absolutePath)
}

// hscript powers watch/hover/condition evaluation (M5); it is a released
// haxelib, so pull it in when absent rather than assuming a primed machine
tasks.register<Exec>("installHscript") {
    group = "hxcpp"
    description = "Installs the hscript haxelib if it is not already present"
    onlyIf { haxeOnPath() }
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
        val found = haxeOnPath()
        if (!found) logger.lifecycle("SKIPPING intellij-hxcpp-debug-server unit tests (haxe compiler not found on PATH)")
        found
    }
}

tasks.named("check") {
    dependsOn("testHaxeServer")
}

// developer convenience: point the local haxelib at this module's library
// sources, so fixtures and user projects pick the work-in-progress server up
// via `-lib intellij-hxcpp-debug-server`
tasks.register<Exec>("haxelibDevSetup") {
    group = "hxcpp"
    description = "Registers haxelib/ as the haxelib dev path for 'intellij-hxcpp-debug-server'"
    commandLine = listOf("haxelib", "dev", "intellij-hxcpp-debug-server", File(projectDir, "haxelib").absolutePath)
}
