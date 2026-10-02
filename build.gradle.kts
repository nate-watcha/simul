plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "com.song"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val koog = "1.3.0"

dependencies {
    // LLM 스택은 전부 Koog: AIAgent + 커스텀 strategy, ChatMemory(시나리오당 대화 유지), EventHandler(라이브 로그),
    // OpenAI 호환 클라이언트(llama-server). agents-features-memory는 umbrella에 포함되지 않아 따로 선언한다.
    implementation("ai.koog:koog-agents:$koog")
    implementation("ai.koog:agents-features-memory:$koog")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // agent.run은 suspend — runBlocking 브리지
    // koog가 전이로 쓰는 Ktor 엔진을 직접 구성한다(keep-alive를 llama-server의 5s보다 짧게 — Llm.kt 참조)
    implementation("io.ktor:ktor-client-apache5:3.3.3")
    // 우리 코드는 JsonElement 런타임만 사용 — @Serializable 컴파일러 플러그인은 금지 (Core.kt 참조).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jline:jline:3.26.3")    // TUI: raw 모드/alt screen/키 입력
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")   // koog 내부 로깅(slf4j) 바인딩 — 없으면 stderr 경고가 TUI를 더럽힌다
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
    description = "Run the tool-calling agent on a single natural-language command"
    mainClass.set("dev.nate.uiagent.agent.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    isIgnoreExitValue = true // FAILED verdict exits 1; that's a result, not a build failure
}
