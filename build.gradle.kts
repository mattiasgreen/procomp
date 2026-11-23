import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("com.diffplug.spotless") version "8.11.0"
    id("net.ltgt.errorprone") version "5.2.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
    }
    test {
        java.setSrcDirs(listOf("test", "gradle-test"))
        java.exclude("TestRunner.java")
    }
}

dependencies {
    // ZERO implementation dependencies: src/ must remain pure JDK 25 for JEP 458 source launch.
    errorprone("com.google.errorprone:error_prone_core:2.52.0")
    errorprone("com.uber.nullaway:nullaway:0.14.3")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>("compileJava") {
    options.errorprone {
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "procomp")
    }
}

tasks.named<JavaCompile>("compileTestJava") {
    options.errorprone.enabled = false
}

val testSourceSet = sourceSets.test.get()

val webAssetsDir = layout.projectDirectory.dir("web")

val unitTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs fast Level 1-2 unit and in-memory shell/CLI/web tests in parallel."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    inputs.dir(webAssetsDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("procomp.levels", "1,2")
    systemProperty("procomp.levels", "1,2")
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs Level 3 platform and real-OS smoke integration tests sequentially."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(unitTest)
    inputs.dir(webAssetsDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("procomp.levels", "3")
    systemProperty("procomp.levels", "3")
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

tasks.test {
    // Delegate to independently cacheable unitTest (Levels 1-2) and integrationTest (Level 3).
    enabled = false
    dependsOn(unitTest, integrationTest)
}

spotless {
    java {
        target("src/**/*.java", "test/**/*.java", "gradle-test/**/*.java", "e2e/*/src/**/*.java")
        trimTrailingWhitespace()
        endWithNewline()
        removeUnusedImports()
    }
}

val lint by tasks.registering {
    group = "verification"
    description = "Fast inner-loop typecheck, Error Prone + NullAway static analysis, and Spotless style check."
    dependsOn(tasks.compileJava, tasks.compileTestJava, tasks.named("spotlessCheck"))
}

val fastCheck by tasks.registering {
    group = "verification"
    description = "Fast inner-loop verification: lint + unitTest (Levels 1-2)."
    dependsOn(lint, unitTest)
}

val verifyJep458 by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies pure zero-dependency JEP 458 direct source launch of procomp with an empty classpath."
    val launcher = javaToolchains.launcherFor(java.toolchain)
    inputs.dir(layout.projectDirectory.dir("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { true }
    environment("CLASSPATH", "")
    executable(launcher.map { it.executablePath.asFile.absolutePath }.get())
    args("--class-path", "", "src/procomp/cli/Procomp.java", "--help")
    environment("JAVA_HOME", launcher.map { it.metadata.installationPath.asFile.absolutePath }.get())
}

val verifyTestRunnerCompile by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies offline test/TestRunner.java compiles cleanly with zero external dependencies."
    val compiler = javaToolchains.compilerFor(java.toolchain)
    val outDir = layout.buildDirectory.dir("verify-test-runner")
    inputs.file(layout.projectDirectory.file("test/TestRunner.java")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outDir)
    environment("CLASSPATH", "")
    executable(compiler.map { it.executablePath.asFile.absolutePath }.get())
    argumentProviders.add(CommandLineArgumentProvider {
        val dir = outDir.get().asFile
        dir.mkdirs()
        listOf("-encoding", "UTF-8", "-d", dir.absolutePath, "test/TestRunner.java")
    })
}

tasks.named("check") {
    dependsOn(lint, unitTest, integrationTest, verifyJep458, verifyTestRunnerCompile)
}

val outerLoop by tasks.registering {
    group = "verification"
    description = "Full outer-loop verification: check (Levels 1-3 + JEP 458) + scenarios/demos + real E2E topology + Playwright Web UI."
    dependsOn(
        tasks.named("check"),
        ":e2e:scenarios:check",
        ":e2e:runner:check",
        ":e2e:runner:e2eTest",
        ":e2e:web-ui:uiE2eTest",
    )
}
