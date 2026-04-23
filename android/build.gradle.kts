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

    // Legacy plugins (e.g. flutter_bluetooth_serial) ship compileSdk 30; merged androidx resources
    // reference android:attr/lStar (API 31+). Bump so :verifyReleaseResources succeeds.
    afterEvaluate {
        val androidExt = extensions.findByName("android") ?: return@afterEvaluate
        var bumped = false
        for (methodName in listOf("setCompileSdkVersion", "setCompileSdk")) {
            val m =
                androidExt.javaClass.methods.find {
                    it.name == methodName && it.parameterTypes.size == 1
                }
                    ?: continue
            val p = m.parameterTypes[0]
            if (p != Int::class.javaPrimitiveType && p != Integer::class.java) continue
            try {
                m.invoke(androidExt, 35)
                bumped = true
                break
            } catch (_: Exception) {
            }
        }
        if (!bumped) {
            try {
                val getter = androidExt.javaClass.methods.find { it.name == "getCompileSdk" && it.parameterTypes.isEmpty() }
                val prop = getter?.invoke(androidExt) ?: return@afterEvaluate
                val set = prop.javaClass.methods.find { it.name == "set" && it.parameterTypes.size == 1 }
                set?.invoke(prop, 35)
            } catch (_: Exception) {
            }
        }
    }
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
