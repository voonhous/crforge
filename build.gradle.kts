plugins {
    java
    idea
    id("com.diffplug.spotless") version "6.25.0"
}

allprojects {
    group = "org.crforge"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    spotless {
        java {
            endWithNewline()
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
        testImplementation("org.assertj:assertj-core:3.25.3")
        testImplementation("org.mockito:mockito-core:5.10.0")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}