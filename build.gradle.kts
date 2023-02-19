import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("org.springframework.boot") version "2.6.6"
	id("io.spring.dependency-management") version "1.0.11.RELEASE"
	kotlin("jvm") version "1.6.10"
	kotlin("plugin.spring") version "1.6.10"
	kotlin("plugin.jpa") version "1.6.10"
}

group = "com.kk"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
	mavenCentral()
	maven ( "https://jitpack.io")
	maven ("https://mvn.mchv.eu/repository/mchv/")
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1")
	implementation("org.telegram:telegrambots-spring-boot-starter:6.0.1")
	implementation("com.tictactec:ta-lib:0.4.0")
	implementation("org.ta4j:ta4j-core:0.14")
	implementation("org.ta4j:ta4j-examples:0.14")
	testImplementation("org.springframework.boot:spring-boot-starter-test")

	implementation("com.google.code.gson:gson:2.9.0")
	implementation("org.json:json:20220320")
	implementation("com.squareup.okhttp3:okhttp:4.9.3")
	implementation("ru.gildor.coroutines:kotlin-coroutines-okhttp:1.0")
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs = listOf("-Xjsr305=strict")
		jvmTarget = "17"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
