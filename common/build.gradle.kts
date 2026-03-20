plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.18.2-R0.1-SNAPSHOT")

    implementation("net.dv8tion:JDA:5.4.0") {
        exclude("org.slf4j")
    }
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
}
