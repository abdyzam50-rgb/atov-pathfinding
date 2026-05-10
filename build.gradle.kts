plugins {
    id("fabric-loom") version "1.16.1"
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    from("LICENSE")
}
