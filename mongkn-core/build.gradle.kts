import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/**
 * Сгенерированный `MongoCollection` приезжает из `:mongkn-api-spec` — там на classpath лежит
 * официальный JVM-драйвер, с которого снимается форма API (решение Р5).
 *
 * Передаётся конфигурацией, а не путём в чужой `buildDir`: так Gradle сам знает, что перед
 * компиляцией надо прогнать `kspKotlin`, и межпроектная связь остаётся объявленной.
 */
val nativeApiSources: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "mongkn-native-api-sources"))
    }
}

dependencies {
    nativeApiSources(project(":mongkn-api-spec"))
}

/**
 * Где искать заголовки и библиотеки mongo-c-driver.
 *
 * pkg-config в системе может отсутствовать (на машине разработки его нет — brew ставит только
 * .pc-файлы, но не сам pkgconf), поэтому пути ищутся перебором известных префиксов.
 * Переопределяется через `-Pmongkn.prefix=/path` или `MONGKN_PREFIX`.
 */
val mongocPrefixes: List<File> = run {
    val explicit = providers.gradleProperty("mongkn.prefix").orNull
        ?: providers.environmentVariable("MONGKN_PREFIX").orNull
    if (explicit != null) listOf(File(explicit))
    else listOf("/opt/homebrew", "/usr/local", "/usr").map(::File)
}

/**
 * Homebrew 2.x кладёт заголовки в версионированные каталоги:
 * `<prefix>/include/mongoc-2.1.1/mongoc/mongoc.h`. Системная установка 1.x — в
 * `<prefix>/include/libmongoc-1.0/mongoc/mongoc.h`. Ищем каталог, в котором лежит `<rel>`.
 */
fun findIncludeDir(rel: String): File? = mongocPrefixes
    .map { File(it, "include") }
    .filter { it.isDirectory }
    .flatMap { include -> listOf(include) + (include.listFiles()?.filter { it.isDirectory } ?: emptyList()) }
    .firstOrNull { File(it, rel).isFile }

/**
 * Имя библиотеки для линковки зависит от мажорной версии драйвера:
 * 2.x — `libmongoc2` / `libbson2`, 1.x — `libmongoc-1.0` / `libbson-1.0`.
 * Выводим из того, что реально лежит в `<prefix>/lib`.
 */
fun findLibName(stem: String): String? = mongocPrefixes
    .map { File(it, "lib") }
    .filter { it.isDirectory }
    .firstNotNullOfOrNull { libDir ->
        libDir.listFiles()
            ?.map { it.name }
            ?.filter { it.startsWith("lib$stem") && (it.endsWith(".dylib") || it.endsWith(".so")) }
            ?.map { it.removePrefix("lib").substringBefore(".dylib").substringBefore(".so") }
            // "mongoc2.2" (версионированный симлинк) отбрасываем в пользу "mongoc2"
            ?.filter { !it.substringAfter(stem).contains('.') }
            ?.minByOrNull { it.length }
    }

val libDirs: List<File> = mongocPrefixes.map { File(it, "lib") }.filter { it.isDirectory }

kotlin {
    // Кросс-компиляция cinterop требует заголовков целевой платформы, которых на хосте нет,
    // поэтому собираем только хостовый таргет. Матрица таргетов — задача CI, см. M-13.
    val hostTarget: KotlinNativeTarget = when {
        System.getProperty("os.name") == "Mac OS X" && System.getProperty("os.arch") == "aarch64" -> macosArm64()
        System.getProperty("os.name") == "Mac OS X" -> macosX64()
        System.getProperty("os.name") == "Linux" -> linuxX64()
        else -> error("mongkn: неподдерживаемый хост ${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
    }

    hostTarget.apply {
        compilations.getByName("main") {
            cinterops.create("mongoc") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/mongoc.def"))
                packageName("mongkn.cinterop")

                val mongocInclude = findIncludeDir("mongoc/mongoc.h")
                val bsonInclude = findIncludeDir("bson/bson.h")
                if (mongocInclude == null || bsonInclude == null) {
                    error(
                        "mongkn: не найдены заголовки mongo-c-driver. " +
                            "Поставьте драйвер (`brew install mongo-c-driver`) или укажите " +
                            "-Pmongkn.prefix=<префикс установки>. Искали в: $mongocPrefixes"
                    )
                }
                includeDirs(mongocInclude, bsonInclude)
            }
        }

        binaries.all {
            val mongoc = findLibName("mongoc") ?: error("mongkn: не найдена libmongoc в $libDirs")
            val bson = findLibName("bson") ?: error("mongkn: не найдена libbson в $libDirs")
            linkerOpts(libDirs.flatMap { listOf("-L${it.absolutePath}") } + listOf("-l$mongoc", "-l$bson"))
        }
    }

    // Source set'ы `nativeMain` / `nativeTest` заводить руками нельзя: их уже создаёт
    // стандартный шаблон иерархии KMP, и ручной `by creating` его ломает — компиляция
    // начинает резолвиться против common-метаданных, где `Dispatchers.IO` объявлен internal.
    // Каталоги `src/nativeMain/kotlin` и `src/nativeTest/kotlin` подхватываются шаблоном сами.
    sourceSets {
        nativeMain {
            kotlin.srcDir(nativeApiSources)
        }
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
