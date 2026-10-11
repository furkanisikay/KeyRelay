plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
val vectors = rootProject.file("../protocol/test-vectors/frames.json")
tasks.test {
    useJUnitPlatform()
    inputs.file(vectors)
    systemProperty("keyrelay.vectors", vectors.absolutePath)
}
tasks.register<JavaExec>("fakeServer") {
    description = "Runs the test relay server for device checks (P2 Task 12)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.keyrelay.core.FakeRelayServerKt")
    args(providers.gradleProperty("port").getOrElse("47111"))
}
