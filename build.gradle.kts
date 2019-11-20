import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.kapt3.base.Kapt
import org.jetbrains.kotlin.kapt3.base.Kapt.kapt


plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.41"
    kotlin("kapt") version "1.3.41"
}

repositories {
    mavenCentral()
    jcenter()
//    maven(url = "https://jitpack.io")
}

dependencies {

    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.slf4j:slf4j-simple:1.7.26")
    implementation("io.github.microutils:kotlin-logging:1.7.6")
    implementation("io.projectreactor:reactor-core:3.3.0.RELEASE")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.0.0.RELEASE")

    testImplementation("org.amshove.kluent:kluent:1.56")
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:2.0.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.5.2")
    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:2.0.8")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}
tasks {
    test {
        useJUnitPlatform {
            includeEngines("spek2")
        }
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
