import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// Публикация в приватный Reposilite — общая для всех выкладываемых модулей.

/*
 * Выбор хостового таргета повторяет `:mongkn-core` — вынести в общий скрипт стоит, когда
 * модулей станет больше двух; пока дублирование дешевле лишней абстракции.
 */
kotlin {
    // OPTED IN OUT LOUD. `sborka.kmp` compiles with `allWarningsAsErrors`, and these two APIs were
    // being used with the compiler asking to be told so on every build — a warning nobody read
    // because nothing failed on it. Saying it here is the same statement the annotation would make
    // at each use site, made once and visible: this library depends on APIs its authors may change.
    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }

    val hostTarget: KotlinNativeTarget =
        when {
            System.getProperty("os.name") == "Mac OS X" && System.getProperty("os.arch") == "aarch64" -> {
                macosArm64()
            }

            System.getProperty("os.name") == "Mac OS X" -> {
                macosX64()
            }

            System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "aarch64" -> {
                error(
                    "mongkn: Kotlin/Native не умеет компилировать на linux-aarch64 — см. :mongkn-core",
                )
            }

            System.getProperty("os.name") == "Linux" -> {
                linuxX64()
            }

            else -> {
                error("mongkn: неподдерживаемый хост ${System.getProperty("os.name")}")
            }
        }
    hostTarget.compilations // таргет объявлен; cinterop здесь не нужен — модуль чисто Kotlin

    sourceSets {
        commonMain.dependencies {
            api(project(":mongkn-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
