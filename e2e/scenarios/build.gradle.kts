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

dependencies {
    implementation(rootProject)

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

val demosOutputDir = rootProject.file("docs/demos")

tasks.register<JavaExec>("repl") {
    group = "application"
    description = "Launch the interactive procomp Scenario REPL over InMemoryWorld (-Pscenario=..., -Pstep=..., -Plag=none|fast|realistic)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("e2e.scenarios.repl.ScenarioReplMain")
    standardInput = System.`in`
    val replArgs = mutableListOf<String>()
    (project.findProperty("scenario") as String?)?.takeIf { it.isNotBlank() }?.let {
        replArgs.addAll(listOf("--scenario", it))
    }
    (project.findProperty("step") as String?)?.takeIf { it.isNotBlank() }?.let {
        replArgs.addAll(listOf("--step", it))
    }
    (project.findProperty("lag") as String?)?.takeIf { it.isNotBlank() }?.let {
        replArgs.addAll(listOf("--lag", it))
    }
    args = replArgs
}

tasks.register<JavaExec>("generateDemos") {
    group = "documentation"
    description = "Generate deterministic Markdown demo snapshots under docs/demos/*.md."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("e2e.scenarios.demo.DemoCaptureMain")
    args("--generate", demosOutputDir.absolutePath)
}

val checkDemos = tasks.register<JavaExec>("checkDemos") {
    group = "verification"
    description = "Verify that committed docs/demos/*.md files match deterministic DemoCapture output."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("e2e.scenarios.demo.DemoCaptureMain")
    args("--check", demosOutputDir.absolutePath)
}

tasks.named("check") {
    dependsOn(checkDemos)
}
