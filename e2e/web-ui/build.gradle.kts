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
    testImplementation(rootProject)
    testImplementation(project(":e2e:scenarios"))
    testImplementation("com.microsoft.playwright:playwright:1.51.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Keep `gradle check` fast and browser-free; run Playwright explicitly via `:e2e:web-ui:uiE2eTest` or `gradle outerLoop`.
tasks.test {
    enabled = false
}

val installBrowsers = tasks.register<JavaExec>("installBrowsers") {
    group = "verification"
    description = "Install the Playwright Chromium browser binary (zero npm required)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
}

val uiE2eTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs Playwright headless Chromium E2E tests against the real Web UI backed by InMemoryWorld Scenarios."
    shouldRunAfter(installBrowsers)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()

    val resolvedWebDir = rootProject.file("web").absolutePath
    val resolvedScreenshotsDir = layout.buildDirectory.dir("playwright-failures").get().asFile.absolutePath
    systemProperty("e2e.webDir", resolvedWebDir)
    systemProperty("e2e.failureScreenshotsDir", resolvedScreenshotsDir)

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
