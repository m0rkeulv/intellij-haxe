rootProject.name = "Haxe-plugin"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":hxcpp-debugger-protocol-legacy")
include(":jps-plugin")
include(":common")
include(":dap-protocol")
include(":hashlink-debug-adapter")
include(":hxcpp-debug-adapter")

