import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Загрузка официальных spec-тестов MongoDB — вынесена, потому что там своя история
// про лицензию и про то, почему файлы не лежат в репозитории.
apply(from = "spec-tests.gradle.kts")

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

/**
 * Дифференциальные тесты (M-28): нативная сторона — вторая из трёх фаз.
 *
 * До неё эталон должен записать документ и выгрузить фикстуру, после — проверить написанное нами.
 * Порядок задаётся зависимостями задач, а не соглашением «запускайте вручную по очереди»:
 * дифференциальный тест, который молча прошёл на вчерашней фикстуре, хуже отсутствующего.
 */
val seedDiffReference = ":mongkn-difftest:seedDiffReference"
val verifyDiffWritten = ":mongkn-difftest:verifyDiffWritten"

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
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    dependsOn(seedDiffReference)
    // Корректность этих тестов зависит от состояния mongod, а его Gradle не отслеживает.
    // Без этой строки задача может оказаться UP-TO-DATE и не отработать, тогда как фаза A
    // уже вычистила коллекции, — и фаза C падает на пустой `written`. Ровно так и случилось
    // при первом же инкрементальном прогоне после удаления генератора.
    outputs.upToDateWhen { false }
    // Путь к фикстуре нативный тест получает через окружение: Gradle-свойства ему недоступны.
    dependsOn("fetchSpecTests")
    environment(
        "MONGKN_SPEC_TESTS",
        layout.buildDirectory.dir("spec-tests").get().asFile.absolutePath,
    )
    environment(
        "MONGKN_DIFF_FIXTURE",
        project(":mongkn-difftest").layout.buildDirectory.file("diff/reference.json").get().asFile.absolutePath,
    )
    finalizedBy(verifyDiffWritten)
}
