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

tasks.test {
    useJUnitPlatform()
    systemProperty("procomp.fast", System.getProperty("procomp.fast", "false"))
    systemProperty("procomp.level", System.getProperty("procomp.level", "0"))
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

spotless {
    java {
        target("src/**/*.java", "test/**/*.java", "gradle-test/**/*.java")
        trimTrailingWhitespace()
        endWithNewline()
        removeUnusedImports()
    }
}

val verifyJep458 by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies pure zero-dependency JEP 458 direct source launch of procomp."
    val launcher = javaToolchains.launcherFor(java.toolchain)
    executable(launcher.get().executablePath.asFile.absolutePath)
    args("src/procomp/cli/Procomp.java", "--help")
    environment("JAVA_HOME", launcher.get().metadata.installationPath.asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(verifyJep458)
}
