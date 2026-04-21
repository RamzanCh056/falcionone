allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)

    // flutter_bluetooth_serial 0.4.0 has no namespace (required by AGP 8+).
    plugins.withId("com.android.library") {
        if (name != "flutter_bluetooth_serial") return@withId
        val androidExt = extensions.findByName("android") ?: return@withId
        val setNs =
            androidExt.javaClass.methods.find { m ->
                m.name == "setNamespace" && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
            }
        try {
            setNs?.invoke(androidExt, "io.github.edufolly.flutterbluetoothserial")
        } catch (_: Exception) {
        }
    }
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
