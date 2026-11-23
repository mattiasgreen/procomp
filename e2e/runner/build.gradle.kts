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
    implementation(project(":e2e:scenarios"))
    wiremockDist("org.wiremock:wiremock-standalone:3.13.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val wiremockJarFile = layout.buildDirectory.file("wiremock/wiremock-standalone.jar")

val prepareWireMock by tasks.registering(Copy::class) {
    from(wiremockDist)
    into(layout.buildDirectory.dir("wiremock"))
    rename { "wiremock-standalone.jar" }
}

// Keep default `gradle test` / `gradle check` fast (unit tests only); run real-OS E2E via `:e2e:runner:e2eTest`.
tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

val e2eTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs true E2E integration tests with real Spring Boot JARs, embedded Artemis, and WireMock."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("e2e")
    }

    dependsOn(
        prepareWireMock,
        ":e2e:broker:bootJar",
        ":e2e:pricing:bootJar",
        ":e2e:orders:bootJar",
        ":e2e:notifications:bootJar",
    )

    val repoRootPath = rootProject.projectDir.absolutePath
    val wiremockJarPath = wiremockJarFile.map { it.asFile.absolutePath }
    val launcher = javaToolchains.launcherFor(java.toolchain)
    val javaHomePath = launcher.map { it.metadata.installationPath.asFile.absolutePath }

    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-De2e.repoRoot=$repoRootPath",
            "-De2e.wiremockJar=${wiremockJarPath.get()}",
            "-De2e.javaHome=${javaHomePath.get()}",
        )
    })

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

val sandbox by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Fast-forwards RealTopologyWorld to a Scenario/Step with a local p.cmd / ./p wrapper shim and pauses."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "e2e.runner.RealSandboxMain"
    standardInput = System.`in`

    dependsOn(
        prepareWireMock,
        ":e2e:broker:bootJar",
        ":e2e:pricing:bootJar",
        ":e2e:orders:bootJar",
        ":e2e:notifications:bootJar",
    )

    val repoRootPath = rootProject.projectDir.absolutePath
    val wiremockJarPath = wiremockJarFile.map { it.asFile.absolutePath }
    val launcher = javaToolchains.launcherFor(java.toolchain)
    val javaHomePath = launcher.map { it.metadata.installationPath.asFile.absolutePath }
    val scenarioProp = providers.gradleProperty("scenario")
    val stepProp = providers.gradleProperty("step")

    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-De2e.repoRoot=$repoRootPath",
            "-De2e.wiremockJar=${wiremockJarPath.get()}",
            "-De2e.javaHome=${javaHomePath.get()}",
        )
    })

    argumentProviders.add(CommandLineArgumentProvider {
        val cliArgs = mutableListOf<String>()
        scenarioProp.orNull?.takeIf { it.isNotBlank() }?.let {
            cliArgs.add("--scenario")
            cliArgs.add(it)
        }
        stepProp.orNull?.takeIf { it.isNotBlank() }?.let {
            cliArgs.add("--step")
            cliArgs.add(it)
        }
        cliArgs
    })
}
