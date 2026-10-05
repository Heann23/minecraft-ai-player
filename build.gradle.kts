import java.util.Properties
import java.util.zip.ZipFile

plugins {
    java
    // NMS(서버 내부 코드) 접근을 위해 필요하다. Maven 에서는 지원되지 않는다.
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "me.herry"
version = "1.8.0"
description = "Autonomous AI player"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 테스트 서버(paper-26.3-140)와 동일한 빌드에 맞춘다.
    paperweight.paperDevBundle("26.3.build.140-beta")

    implementation("net.dv8tion:JDA:6.7.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("club.minnced:jdave-api:0.1.8")
    runtimeOnly("club.minnced:jdave-native-win-x86-64:0.1.8")
    runtimeOnly("club.minnced:jdave-native-linux-x86-64:0.1.8")
    runtimeOnly("club.minnced:jdave-native-linux-aarch64:0.1.8")
    runtimeOnly("club.minnced:jdave-native-darwin:0.1.8")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.add("-Xlint:deprecation")
    }

    compileTestJava {
        options.encoding = "UTF-8"
    }

    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version, "description" to project.description)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// 테스트 서버의 plugins 폴더는 개발 PC 마다 다르므로 빌드 스크립트에 적지 않는다.
// 우선순위: -PpluginsDir=... > local.properties 의 pluginsDir > 환경 변수 MINECRAFT_AI_PLUGINS_DIR
val pluginsDir: File? = run {
    val local = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use { local.load(it) }
    val path = (findProperty("pluginsDir") as String?)
        ?: local.getProperty("pluginsDir")
        ?: System.getenv("MINECRAFT_AI_PLUGINS_DIR")
    path?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
}

// 폴더가 설정되어 있고 실제로 있을 때만 실행된다. 다른 PC 나 CI 에서는 건너뛴다.
val deployPlugin = tasks.register("deployPlugin") {
    val jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    dependsOn(tasks.shadowJar)
    val target = pluginsDir
    onlyIf { target != null && target.isDirectory }
    doLast {
        val source = jarFile.get().asFile
        // 같은 플러그인의 jar 가 둘 있으면 서버가 하나만 불러오므로, 넣기 전에 이전 버전의 jar 를 지운다.
        target!!.listFiles { f -> f.name.startsWith("MinecraftAI-") && f.name.endsWith(".jar") && f.name != source.name }?.forEach {
            // 서버가 켜져 있으면 jar 가 잠겨 있어서 지울 수 없다.
            if (!it.delete()) logger.warn("Could not delete ${it.name} (is the server running?). Remove it before restarting the server.")
        }
        source.copyTo(File(target, source.name), overwrite = true)
        logger.lifecycle("Deployed ${source.name} to $target")
    }
}

// Discord 네이티브·서비스를 포함한 파일이 배포 대상이다. Paper/NMS는 compileOnly로 유지한다.
tasks.jar {
    archiveClassifier = "plain"
}
tasks.shadowJar {
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "**/module-info.class")
    dependencies { exclude(dependency("org.slf4j:slf4j-api:.*")) }
    relocate("com.google.gson", "me.herry.minecraftAI.discord.internal.gson")
    relocate("com.fasterxml.jackson", "me.herry.minecraftAI.discord.internal.jackson")
    failOnDuplicateEntries = true
    finalizedBy(deployPlugin)
}

// 실제 배포 파일에 음성 네이티브와 adapter가 있고 서버 자체가 섞이지 않았는지 검사한다.
val verifyDiscordJar = tasks.register("verifyDiscordJar") {
    val artifact = tasks.shadowJar.flatMap { it.archiveFile }
    dependsOn(tasks.shadowJar)
    inputs.file(artifact)
    doLast {
        ZipFile(artifact.get().asFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            listOf("net/dv8tion/jda/api/JDA.class", "club/minnced/discord/jdave/interop/JDaveSessionFactory.class",
                "me/herry/minecraftAI/discord/internal/gson/Strictness.class", "club/minnced/opus/util/OpusLibrary.class",
                "natives/win-x86-64/dave.dll", "natives/linux-x86-64/libdave.so",
                "natives/linux-aarch64/libdave.so", "natives/darwin/libdave.dylib").forEach {
                check(it in entries) { "Discord runtime resource missing: $it" }
            }
            check(entries.none { it.startsWith("org/bukkit/") || it.startsWith("net/minecraft/") }) {
                "Paper/NMS must not be packaged in the plugin"
            }
        }
    }
}
tasks.check { dependsOn(verifyDiscordJar) }

val verifyDiscordRuntime = tasks.register<JavaExec>("verifyDiscordRuntime") {
    dependsOn(tasks.shadowJar, tasks.compileTestJava)
    mainClass = "me.herry.minecraftAI.discord.DiscordJarSmoke"
    classpath = files(sourceSets.test.get().output.classesDirs, tasks.shadowJar.flatMap { it.archiveFile },
        configurations.runtimeClasspath.get().filter { it.name.startsWith("slf4j-api-") })
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.check { dependsOn(verifyDiscordRuntime) }
