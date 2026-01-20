plugins {
    kotlin("jvm") version "2.2.21"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("com.gradleup.shadow") version "9.3.1"
    //id("xyz.jpenilla.run-paper") version "3.0.2" // Adds runServer and runMojangMappedServer tasks for testing
    //id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.3.0" // Generates plugin.yml based on the Gradle config
}

group = "com.panling"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    // [关键修复] PaperMC 官方仓库，托管 Paperweight 核心及 Paper 服务端构件
    maven("https://repo.papermc.io/repository/maven-public/")
    // [关键修复] Sonatype 快照库，用于解析 x.x.x-SNAPSHOT 版本的依赖
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.10-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))
}

java {
    // Configure the java toolchain. This allows gradle to auto-provision JDK 21 on systems that only have JDK 11 installed for example.
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    compileJava {
        // Set the release flag. This configures what version bytecode the compiler will emit, as well as what JDK APIs are usable.
        // See https://openjdk.java.net/jeps/247 for more information.
        options.release = 21
    }
    javadoc {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
    }

    // Only relevant for 1.20.4 or below, or when you care about supporting Spigot on >=1.20.5:
    /*
    reobfJar {
      // This is an example of how you might change the output location for reobfJar. It's recommended not to do this
      // for a variety of reasons, however it's asked frequently enough that an example of how to do it is included here.
      outputJar = layout.buildDirectory.file("libs/PaperweightTestPlugin-${project.version}.jar")
    }
     */
}