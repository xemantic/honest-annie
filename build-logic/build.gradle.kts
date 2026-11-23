plugins {
    `kotlin-dsl`
}

val javaTarget = libs.versions.javaTarget.get()

java {
    val version = JavaVersion.toVersion(javaTarget)
    sourceCompatibility = version
    targetCompatibility = version
}

tasks.compileJava {
    options.release = javaTarget.toInt()
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(javaTarget)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.power.assert)
}

gradlePlugin {
    plugins {
        register("AnnieConventionPlugin") {
            id = "annie.convention"
            implementationClass = "com.xemantic.ai.annie.buildlogic.AnnieConventionPlugin"
        }
    }
}
