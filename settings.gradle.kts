plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "crforge"

include("core")
include("desktop")
include("gym-bridge")
include("data")
