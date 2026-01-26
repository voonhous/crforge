plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    // LibGDX
    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    implementation("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
}

application {
    mainClass.set("org.crforge.desktop.DesktopLauncher")

    // macOS requires this for LWJGL/GLFW
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}
