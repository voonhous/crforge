plugins {
    application
    `java-library`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":gym-bridge"))

    // ZMQ + JSON (needed directly since gym-bridge uses implementation scope)
    implementation(libs.jeromq)
    implementation(libs.jackson.databind)

    // LibGDX
    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    implementation(libs.gdx.freetype)
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:${libs.versions.gdx.get()}:natives-desktop")

    // Logging
    api(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

application {
    mainClass.set("org.crforge.desktop.DesktopLauncher")

    // macOS requires this for LWJGL/GLFW
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}
