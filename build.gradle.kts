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

val unitTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs fast Level 1-2 unit and in-memory shell/CLI/web tests in parallel."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
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
        target("src/**/*.java", "test/**/*.java", "gradle-test/**/*.java")
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
    description = "Verifies pure zero-dependency JEP 458 direct source launch of procomp."
    val launcher = javaToolchains.launcherFor(java.toolchain)
    val javaExec = launcher.get().executablePath.asFile.absolutePath
    val javaHome = launcher.get().metadata.installationPath.asFile.absolutePath
    inputs.dir(layout.projectDirectory.dir("src"))
    outputs.upToDateWhen { true }
    executable(javaExec)
    args("src/procomp/cli/Procomp.java", "--help")
    environment("JAVA_HOME", javaHome)
}

tasks.named("check") {
    dependsOn(lint, unitTest, integrationTest, verifyJep458)
}

val outerLoop by tasks.registering {
    group = "verification"
    description = "Full outer-loop verification: check (Levels 1-3 + JEP 458) + real E2E topology suite."
    dependsOn(tasks.named("check"), ":e2e:runner:e2eTest")
}
