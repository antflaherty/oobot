plugins {
    java
    application
}

group = "oobot"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.okhttp)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    implementation(libs.picocli)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

application {
    mainClass = "oobot.robot.RobotController"
}

val fatJar by tasks.registering(Jar::class) {
    archiveFileName = "robot.jar"
    destinationDirectory = layout.projectDirectory.dir(".")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "oobot.robot.RobotController"
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
    }
}
