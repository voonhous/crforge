plugins {
    application
    `java-library`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    // LibGDX
    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")

    // Logging
    api(libs.slf4j.api)
    // Use simple logger for now in tests/CLI
    testImplementation(libs.slf4j.simple)

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
