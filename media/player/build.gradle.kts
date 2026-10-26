plugins {
    id("dreamdisplays.kotlin-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":util"))
    api(libs.commonsCompress)
    api(libs.tukaaniXz)
    compileOnly(libs.slf4jApi)
    compileOnly("org.lwjgl:lwjgl:3.4.1")
    compileOnly("org.lwjgl:lwjgl-openal:3.4.1")
    testImplementation(libs.slf4jApi)
}
