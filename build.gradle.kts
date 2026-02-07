plugins {
    id("java")
}

group = "io.nexstudios.legendperms"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

tasks.named<Jar>("jar") {
    enabled = false
}