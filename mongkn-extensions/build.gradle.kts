import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Публикация в приватный Reposilite — общая для всех выкладываемых модулей.
apply(from = rootProject.file("publishing.gradle.kts"))

/*
 * Выбор хостового таргета повторяет `:mongkn-core` — вынести в общий скрипт стоит, когда
 * модулей станет больше двух; пока дублирование дешевле лишней абстракции.
 */
kotlin {
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
