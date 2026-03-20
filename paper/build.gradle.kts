plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:1.20.2-R0.1-SNAPSHOT")
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveClassifier.set("")
        archiveFileName.set("Dis2FA-paper-${project.version}.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from({
            configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
        }) {
            exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
            exclude("META-INF/maven/**")
            exclude("META-INF/versions/**")
            exclude("META-INF/*.kotlin_module")
        }
    }
}
