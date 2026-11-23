// The API between the harness and the executor: the cogitation, see Cognition.kt,
// the agency of the scripts of intents, see Api.kt, and the ScriptExecutor executing them.
plugins {
    id("annie.convention")
}

dependencies {
    // Agency.http, and the navigation of the JSON it receives
    api(libs.ktor.client.core)
    api(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.xemantic.kotlin.test)
}
