plugins {
    id("java")
    id("io.freefair.lombok") version "8.11"
    id("com.gradleup.shadow") version "9.3.0"
    id("maven-publish")
    //id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
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
    //paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    compileOnly ("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // --- Tests (MockBukkit + JUnit5 + Mockito) ---
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("com.zaxxer:HikariCP:7.0.2")
    testImplementation ("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // MockBukkit für 1.21.x (Maven Central)
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.101.0")
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

    test {
        useJUnitPlatform()
    }



    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("LegendPerms")
    }
}