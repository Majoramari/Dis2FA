plugins {
    kotlin("jvm") version "2.1.0"
}

group = "cc.muhannad"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.dv8tion.net/releases") // JDA repository
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    
    // JDA - Discord API (bundled, not compileOnly)
    implementation("net.dv8tion:JDA:5.4.0") {
        exclude("org.slf4j")
    }
    
    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    
    // SQLite (bundled)
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
    
    // Use regular jar instead of shadowJar due to Kotlin 2.1 compatibility issues
    jar {
        archiveClassifier.set("")
        archiveFileName.set("Dis2FA-${version}.jar")
        
        // Set duplicate handling strategy
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        
        // Include all runtime dependencies
        from(configurations.runtimeClasspath.get().map { 
            if (it.isDirectory) it else zipTree(it) 
        }) {
            exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
            exclude("META-INF/maven/**")
            exclude("META-INF/versions/**")
            exclude("META-INF/*.kotlin_module")
        }
    }
    
    build {
        dependsOn(jar)
    }
}
