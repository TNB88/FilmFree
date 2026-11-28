import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(if (project.name == "KRX18Provider") "TNB88/FilmPro18" else "TNB88/FilmFree")
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
        testOptions.unitTests.all {
            it.systemProperty("fixtures", rootProject.file("fixtures").absolutePath)
            it.systemProperty("live", providers.gradleProperty("live").orNull ?: "false")
            // Match OkHttp's explicit Origin header in the JVM-only HTTP contract tests.
            it.systemProperty("sun.net.http.allowRestrictedHeaders", "true")
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // These dependencies can include any of those which are added by the app,
        // but you don't need to include any of them if you don't need them.
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts
        implementation(kotlin("stdlib")) // Adds Standard Kotlin Features
        implementation("com.github.Blatzar:NiceHttp:0.4.13") // HTTP Lib
        implementation("org.jsoup:jsoup:1.18.3") // HTML Parser
        // IMPORTANT: Do not bump Jackson above 2.13.1, as newer versions will
        // break compatibility on older Android devices.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // JSON Parser
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
        add("testImplementation", "junit:junit:4.13.2")
        add("testImplementation", files(File(gradle.gradleUserHomeDir, "caches/cloudstream/cloudstream/cloudstream.jar")))
    }

    // AGP 8.7's bundled D8 predates Kotlin 2.3 metadata. Use the installed SDK D8
    // instead of accepting hundreds of metadata-rewrite warnings in released files.
    afterEvaluate {
        val sdk = extensions.getByType<BaseExtension>().sdkDirectory
        val d8 = providers.gradleProperty("d8Jar").orNull?.let(::File)
            ?: File(sdk, "build-tools/37.0.0/lib/d8.jar")
        tasks.named("compileDex") {
            inputs.file(d8)
            actions.clear()
            doLast {
                require(d8.isFile) { "Install Android build-tools 37.0.0 or pass -Pd8Jar=/path/to/d8.jar" }
                val directory = layout.buildDirectory.dir("intermediates").get().asFile
                directory.mkdirs()
                val jarFile = File(directory, "provider-classes.jar")
                val classes = layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile
                java.util.jar.JarOutputStream(jarFile.outputStream()).use { jar ->
                    classes.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                        jar.putNextEntry(java.util.jar.JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(jar) }
                        jar.closeEntry()
                    }
                }
                val dependencyFiles = configurations.getByName("debugCompileClasspath").files
                    .filter { it.extension == "jar" } + File(gradle.gradleUserHomeDir, "caches/cloudstream/cloudstream/cloudstream.jar")
                project.javaexec {
                    classpath = files(d8)
                    mainClass.set("com.android.tools.r8.D8")
                    args("--release", "--min-api", "21", "--output", directory.absolutePath,
                        "--lib", File(sdk, "platforms/android-35/android.jar").absolutePath)
                    dependencyFiles.distinct().forEach { args("--classpath", it.absolutePath) }
                    args(jarFile.absolutePath)
                }.assertNormalExitValue()
            }
        }
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
