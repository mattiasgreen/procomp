plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val wiremockDist by configurations.creating

dependencies {
    wiremockDist("org.wiremock:wiremock-standalone:3.13.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val wiremockJarFile = layout.buildDirectory.file("wiremock/wiremock-standalone.jar")

val prepareWireMock by tasks.registering(Copy::class) {
    from(wiremockDist)
    into(layout.buildDirectory.dir("wiremock"))
    rename { "wiremock-standalone.jar" }
}

// Keep default `gradle test` / `gradle check` fast; run E2E explicitly via `:e2e:runner:e2eTest`.
tasks.test {
    enabled = false
}

val e2eTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs true E2E integration tests with real Spring Boot JARs, embedded Artemis, and WireMock."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()

    dependsOn(
        prepareWireMock,
        ":e2e:broker:bootJar",
        ":e2e:pricing:bootJar",
        ":e2e:orders:bootJar",
        ":e2e:notifications:bootJar",
    )

    val launcher = javaToolchains.launcherFor(java.toolchain)
    doFirst {
        systemProperty("e2e.repoRoot", rootProject.projectDir.absolutePath)
        systemProperty("e2e.wiremockJar", wiremockJarFile.get().asFile.absolutePath)
        systemProperty("e2e.javaHome", launcher.get().metadata.installationPath.asFile.absolutePath)
    }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
