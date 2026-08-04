plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

dependencies {
    // Драйвер обязан быть на classpath именно здесь: KSP резолвит символы по classpath
    // обрабатываемого модуля.
    compileOnly(libs.mongodb.driver.kotlin.coroutine)
    ksp(project(":mongkn-codegen"))
}

// jvmToolchain намеренно не фиксируется: модуль существует только на время сборки и работает
// на том JDK, что стоит у разработчика. Фиксированная 17-я на машине с JDK 25 просто роняет
// конфигурацию, а Gradle-репозиториев для скачивания тулчейнов здесь не заведено.

/**
 * Каталог со сгенерированными исходниками для `:mongkn-core`.
 *
 * Генерируется Kotlin/Native-код, и **компилировать его на JVM нельзя** — он ссылается на
 * `kotlinx.cinterop` и на нативные типы. Поэтому каталог исключён из компиляции этого модуля:
 * задача KSP здесь только в том, чтобы файл появился.
 */
val generatedNativeApi: Provider<Directory> =
    layout.buildDirectory.dir("generated/ksp/main/kotlin")

val nativeApiSources: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "mongkn-native-api-sources"))
    }
}

// Имя задачи KSP зависит от версии плагина, поэтому находим её, а не угадываем.
val kspTask = tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }

artifacts {
    add(nativeApiSources.name, generatedNativeApi) {
        builtBy(kspTask)
    }
}

/**
 * Сгенерированное предназначено **нативному** таргету и на JVM не компилируется: оно ссылается
 * на `io.github.mongkn.*` из `:mongkn-core`, которого здесь на classpath нет и быть не должно.
 *
 * KSP сам добавляет свой выходной каталог в source set, причём после того, как отработает
 * конфигурация этого файла, — поэтому `setSrcDirs` не помогает, и приходится исключать по пути
 * на уровне задачи компиляции. Задача `kspKotlin` при этом отрабатывает как обычно: она нам
 * и нужна, а её выход забирает `:mongkn-core` через конфигурацию `nativeApiSources`.
 */
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    val generatedMarker = listOf("generated", "ksp").joinToString(File.separator, prefix = File.separator)
    exclude { it.file.absolutePath.contains(generatedMarker) }
}
