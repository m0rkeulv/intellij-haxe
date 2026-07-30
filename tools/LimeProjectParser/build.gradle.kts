plugins {
    base
}

// probed lazily at execution time so a haxe-less machine can still configure
// and build the rest of the plugin
val haxeAvailable: Boolean by lazy {
    try {
        ProcessBuilder("haxe", "--version")
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }
}

val parserJar = layout.buildDirectory.file("libs/LimeProjectParser.jar")

tasks.register<Exec>("buildParser") {
    group = "build"
    description = "Compiles the lime project.xml evaluator to a JVM jar"
    onlyIf { haxeAvailable }
    workingDir = projectDir
    commandLine = listOf("haxe", "build.hxml")
    inputs.dir("src/main/haxe")
    inputs.file("build.hxml")
    outputs.file(parserJar)
}

tasks.register<Exec>("testParser") {
    group = "verification"
    description = "Runs the evaluator's haxe-side tests via --interp"
    onlyIf { haxeAvailable }
    workingDir = projectDir
    commandLine = listOf("haxe", "test.hxml")
    inputs.dir("src/main/haxe")
    inputs.dir("src/test/haxe")
    inputs.file("test.hxml")
    // --interp leaves no artifact; declare a marker so up-to-date checks work
    outputs.file(layout.buildDirectory.file("test-marker.txt"))
    doLast {
        outputs.files.singleFile.writeText("passed")
    }
}

tasks.register<Exec>("integrationTestParser") {
    group = "verification"
    description = "Evaluates project.xml AND project.hxp fixtures end to end (self-skips without lime/hxp)"
    onlyIf { haxeAvailable }
    workingDir = projectDir
    commandLine = listOf("haxe", "integration-test.hxml")
    inputs.dir("src/main/haxe")
    inputs.dir("src/main/resources")
    inputs.dir("src/test/haxe")
    inputs.file("integration-test.hxml")
    outputs.file(layout.buildDirectory.file("integration-test-marker.txt"))
    doLast {
        outputs.files.singleFile.writeText("passed")
    }
}

tasks.named("check") {
    dependsOn("testParser", "integrationTestParser")
}

tasks.named("assemble") {
    dependsOn("buildParser")
}
