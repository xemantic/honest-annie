/*
 * honest-annie - A recursive harness for cognizers acting through Kotlin scripts
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.ai.annie.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradleExtension
import org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradlePlugin
import java.io.File

/**
 * The conventions of every module of honest-annie, configuring the Kotlin plugin the module applies itself,
 * `kotlin("multiplatform")`, e.g. the API, or `kotlin("jvm")`, e.g. the executor:
 * compiled for the targets of the version catalog, power-assert,
 * and JUnit tests run with the JVM arguments of the harness, see [annieJvmArgs].
 */
@Suppress("unused") // used by gradle
class AnnieConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.doApply()
    }

}

/**
 * The JVM arguments of everything running scripts of cognizers: the tests, and the harness.
 * The fast JAR file system of the Kotlin compiler calls sun.misc.Unsafe, the option being unknown before JDK 23,
 * and Jep, embedding CPython for agency.python, loads its native library with System.load, restricted since JDK 24.
 */
val annieJvmArgs: List<String> = (
    if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_23)) listOf("--sun-misc-unsafe-memory-access=allow")
    else emptyList()
) + "--enable-native-access=ALL-UNNAMED"

private fun Project.doApply() {

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val javaTarget = libs.findVersion("javaTarget").get().toString()
    val kotlinTarget = KotlinVersion.fromVersion(libs.findVersion("kotlinTarget").get().toString())

    fun KotlinCommonCompilerOptions.configureCommon() {
        apiVersion.set(kotlinTarget)
        languageVersion.set(kotlinTarget)
        freeCompilerArgs.add("-Xcontext-sensitive-resolution")
        extraWarnings.set(true)
        progressiveMode.set(true)
    }

    // set up according to https://jakewharton.com/gradle-toolchains-are-rarely-a-good-idea/
    fun KotlinJvmCompilerOptions.configureJvm() {
        jvmTarget.set(JvmTarget.fromTarget(javaTarget))
        freeCompilerArgs.add("-Xjdk-release=$javaTarget")
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                configureCommon()
                configureJvm()
            }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(javaTarget)
            targetCompatibility = JavaVersion.toVersion(javaTarget)
        }
        applyPowerAssert()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            compilerOptions {
                configureCommon()
            }
            targets.withType(KotlinJvmTarget::class.java).configureEach {
                compilerOptions {
                    configureJvm()
                }
            }
        }
        applyPowerAssert()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaTarget.toInt())
    }

    repositories {
        mavenCentral()
    }

    val asmVersion = libs.findVersion("asm").get().toString()
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.ow2.asm") {
                useVersion(asmVersion)
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs(annieJvmArgs)
    }

}

private fun Project.applyPowerAssert() {
    plugins.apply(PowerAssertGradlePlugin::class.java)
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    extensions.configure<PowerAssertGradleExtension> {
        functions.set(listOf(
            "com.xemantic.kotlin.test.assert",
            "com.xemantic.kotlin.test.have"
        ))
    }
}

/**
 * Runs these tests with the Python virtual environment of the tests, the `.venv` of the root project,
 * with only the jep package matching the org.ninia:jep library installed by the `installPythonPackages` task,
 * like a clean environment of a user, the tests declaring the Python packages they use as dependencies.
 */
fun Test.usePythonVenvOfTests() {
    dependsOn(":installPythonPackages")
    environment("VIRTUAL_ENV", project.rootProject.layout.projectDirectory.dir(".venv").asFile.absolutePath)
    // the dependencies declared by the tests are installed from scratch on every run, not into the user cache
    val dependencyDirectory: File = project.layout.buildDirectory.dir("python-dependencies").get().asFile
    systemProperty("xemantic.kotlin.script.python.dependencyDirectory", dependencyDirectory.absolutePath)
    doFirst {
        dependencyDirectory.deleteRecursively()
    }
}
