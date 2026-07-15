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

tasks.register<Exec>("testHaxeServer") {
    group = "verification"
    description = "Runs the intellij-hxcpp-debug-server unit tests under the Haxe interpreter"
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
