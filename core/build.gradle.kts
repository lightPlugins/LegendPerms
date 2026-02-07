plugins {
    id("java")
    id("io.freefair.lombok") version "8.11"
    id("com.gradleup.shadow") version "9.3.0"
    id("maven-publish")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

group = "io.nexstudios.legendperms"
version = "1.0-SNAPSHOT"

base {
    archivesName.set("LegendPerms")
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")

    compileOnly("com.zaxxer:HikariCP:7.0.2")
    compileOnly ("org.mariadb.jdbc:mariadb-java-client:3.5.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    processResources {
        from(sourceSets.main.get().resources.srcDirs()) {
            filesMatching("plugin.yml") {
                expand(
                    "name" to "LegendPerms",
                    "version" to version
                )

            }
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }

    compileJava {
        options.encoding = "UTF-8"
    }

    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("LegendPerms")
        //destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
        //relocate("co.aikar.commands", "io.nexstudios.legend.perms.libs.commands")
        //relocate("co.aikar.locales", "io.nexstudios.legend.perms.libs.locales")
    }
}