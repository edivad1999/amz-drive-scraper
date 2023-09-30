import io.ktor.plugin.features.*
import java.io.FileInputStream
import java.util.*

val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "1.9.10"
    id("io.ktor.plugin") version "2.3.4"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.10"
}

group = "edivad1999.com"
version = "0.0.1"

application {
    mainClass.set("edivad1999.com.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=false")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-cio-jvm")
    implementation("io.ktor:ktor-client-core:2.2.4")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.2.4")
    implementation("io.ktor:ktor-client-content-negotiation:2.2.4")
    implementation("io.ktor:ktor-client-encoding:2.2.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.0")

    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-client-cio-jvm:2.3.4")
    implementation("io.ktor:ktor-client-logging-jvm:2.3.4")

    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")
    implementation("org.xerial:sqlite-jdbc:3.43.0.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}
ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_17)
        localImageName.set("amz-scraper")

        imageTag.set("1.0.10")

        environmentVariable("configPath", "/data/config.json")


        externalRegistry.set(
            DockerImageRegistry.externalRegistry(

                project = provider { "amz-scraper" },
                username = provider { getLocalProperty("username") },
                password = provider { getLocalProperty("password") },
                hostname = provider { "ghcr.io" },
                namespace = provider { "edivad1999" },

                )
        )

    }
}
fun getLocalProperty(property: String) = Properties().apply {
    load(FileInputStream(File(rootProject.rootDir, "local.properties")))
}.getProperty(property)!!
