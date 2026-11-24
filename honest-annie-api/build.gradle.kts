// The API between the harness and the executor: the cogitation, see Cognition.kt,
// the agency of the scripts of intents, see Api.kt, and the ScriptExecutor executing them,
// multiplatform, so that a harness, or a cognizer, of another platform than the JVM can use it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("annie.convention")
}

kotlin {

    jvm()

    sourceSets {

        commonMain {
            dependencies {
                // Agency.http, and the navigation of the JSON it receives
                api(libs.ktor.client.core)
                api(libs.ktor.serialization.kotlinx.json)
                // the files of read and of the edits
                implementation(libs.kotlinx.io.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.xemantic.kotlin.test)
            }
        }

    }

}
