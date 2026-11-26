// The API between the harness and the executor: the cogitation, see Cognition.kt,
// the agency of the scripts of intents, see Api.kt, and the ScriptExecutor executing them,
// multiplatform, so that a harness, or a cognizer, of another platform than the JVM can use it,
// and serializable, as the protocol between the harness and the executor.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("annie.convention")
}

kotlin {

    jvm()
    // native, so that a harness compiled natively can use it, the executor staying on the JVM
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {

        commonMain {
            dependencies {
                // the cognition crossing the protocol between the harness and the executor
                api(libs.kotlinx.serialization.json)
                // await of the scripts
                api(libs.kotlinx.coroutines.core)
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
