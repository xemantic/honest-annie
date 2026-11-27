// The API pasted into the constitutions, see generateConstitution of the harness:
// the agency of the scripts of intents and the cogitation, see Api.kt, and the navigation of JSON, see Json.kt,
// what the harness and the executor share beyond it, and the implementations, being in honest-annie-protocol,
// multiplatform, so that a harness, or a cognizer, of another platform than the JVM can use it,
// and serializable, as the protocol between the harness and the executor carries the cogitation.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("annie.convention")
}

kotlin {

    // implMain, shared by every target, holds the actual implementations of the expect declarations of commonMain,
    // so that the constitutions, pasting the sources of commonMain, show the API without its implementation
    applyDefaultHierarchyTemplate {
        common {
            group("impl") {
                withJvm()
                withNative()
            }
        }
    }

    jvm()
    // native, so that a harness compiled natively can use it, the executor staying on the JVM,
    // switched off for now
    //macosArm64()
    //linuxX64()
    //linuxArm64()

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
