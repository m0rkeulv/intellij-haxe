plugins {
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Neutral profiler snapshot model plus one translator per capture format
// (HashLink PROF first). Consumed by the IU profiler bridge in the main
// plugin; must stay free of IDE and target-specific dependencies so the
// translators remain plain fixture-testable functions.
dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }

    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
