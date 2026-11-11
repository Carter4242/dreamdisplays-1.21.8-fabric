plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":shared"))
    api(libs.kotlinxSerializationProtobuf)
    compileOnly(libs.slf4jApi)
    // Display policy JSON; every runtime that loads it (server plugin, client mods) already ships this
    compileOnly(libs.kotlinxSerializationJson)
    // The /display command tree; every server runtime (Paper, Fabric, NeoForge) ships Brigadier
    compileOnly("com.mojang:brigadier:1.3.10")
    testImplementation("com.mojang:brigadier:1.3.10")
    testImplementation(libs.slf4jApi)
    testImplementation(libs.kotlinxSerializationJson)
}

// Regenerates the committed .proto schema artifact from the @Serializable wire classes.
tasks.register<JavaExec>("generateProto") {
    group = "build"
    description = "Regenerates src/main/proto/dreamdisplays.proto from the packet classes."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.dreamdisplays.core.protocol.common.SchemaExporterKt")
    args(layout.projectDirectory.file("src/main/proto/dreamdisplays.proto").asFile.absolutePath)
}
