plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "com.song"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("ai.koog:koog-agents:1.1.1") // kotlinx-serialization 런타임 공급용 (AIAgent은 미사용)
    implementation("org.jline:jline:3.26.3")    // TUI: raw 모드/alt screen/키 입력
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    // the CLI is the product: installDist -> build/install/simul/bin/simul
    applicationName = "simul"
    mainClass.set("dev.nate.uiagent.cli.CliKt")
}

tasks.test {
    useJUnitPlatform()
}

// ad-hoc agent entry point: ./gradlew runAgent --args="\"웹툰 탭을 눌러\""
tasks.register<JavaExec>("runAgent") {
    group = "application"
    description = "Run the koog tool-calling agent on a single natural-language command"
    mainClass.set("dev.nate.uiagent.agent.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    isIgnoreExitValue = true // FAILED verdict exits 1; that's a result, not a build failure
}
