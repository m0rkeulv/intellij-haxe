plugins {
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Haxe JSON-RPC display protocol: DTOs mirroring std haxe.display.*, the
// null-terminated socket transport to a `haxe --wait <port>` server, and the
// type-blueprint cache. Must stay free of IDE and project-model dependencies;
// the main plugin adds the IDE glue on top. Wire facts live in README.md.
dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }

    implementation(libs.jacksonDatabind)

    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
