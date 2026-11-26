import java.util.Properties
import java.util.zip.ZipFile

plugins {
    java
    // NMS(서버 내부 코드) 접근을 위해 필요하다. Maven 에서는 지원되지 않는다.
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "me.herry"
version = "1.10.0"
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

    implementation("com.google.code.gson:gson:2.14.0")

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

// 런타임 의존성을 포함한 Shadow JAR를 배포한다. Paper/NMS는 compileOnly로 유지한다.
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
    relocate("com.google.gson", "me.herry.minecraftAI.internal.gson")
    failOnDuplicateEntries = true
    finalizedBy(deployPlugin)
}

// Paper/NMS가 런타임 JAR에 섞이면 서버와 충돌할 수 있다.
val verifyPluginJar = tasks.register("verifyPluginJar") {
    val artifact = tasks.shadowJar.flatMap { it.archiveFile }
    dependsOn(tasks.shadowJar)
    inputs.file(artifact)
    doLast {
        ZipFile(artifact.get().asFile).use { zip ->
            val entries = zip.entries().asSequence()
            check(entries.none { it.name.startsWith("org/bukkit/") || it.name.startsWith("net/minecraft/") }) {
                "Paper/NMS must not be packaged in the plugin"
            }
        }
    }
}
tasks.check { dependsOn(verifyPluginJar) }
