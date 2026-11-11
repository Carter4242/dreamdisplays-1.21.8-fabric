plugins {
    id("dreamdisplays.kotlin-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":util"))
    compileOnly(libs.slf4jApi)
    // DXGI screen list on Windows; Minecraft ships JNA
    compileOnly(libs.jna)
    testImplementation(libs.jna)
    testImplementation(libs.slf4jApi)
}
