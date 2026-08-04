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
 * Каталоги с библиотеками.
 *
 * Кроме `<prefix>/lib` обязательно смотрим на уровень ниже: Debian и Ubuntu кладут библиотеки
 * в multiarch-каталог `/usr/lib/aarch64-linux-gnu`, и без этого на Linux ничего не находится.
 */
val libDirs: List<File> = mongocPrefixes
    .map { File(it, "lib") }
    .filter { it.isDirectory }
    .flatMap { lib -> listOf(lib) + (lib.listFiles()?.filter { it.isDirectory } ?: emptyList()) }

/**
 * Имя библиотеки для линковки зависит от мажорной версии драйвера:
 * 2.x — `libmongoc2` / `libbson2`, 1.x — `libmongoc-1.0` / `libbson-1.0`.
 * Выводим из того, что реально лежит рядом.
 *
 * Точность здесь не педантизм: `libmongocrypt.so` тоже начинается с `libmongoc`, и при наивном
 * сравнении по длине его можно выбрать вместо `libmongoc-1.0`. Поэтому после основы допускаются
 * только цифры, дефисы и точки — то есть версия, а не другое слово.
 */
fun findLibName(stem: String): String? {
    val allowed = Regex("^" + Regex.escape(stem) + "[-0-9.]*$")
    return libDirs.firstNotNullOfOrNull { libDir ->
        libDir.listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith(".dylib") || it.endsWith(".so") }
            ?.map { it.removePrefix("lib").substringBefore(".dylib").substringBefore(".so") }
            ?.filter { allowed.matches(it) }
            ?.minByOrNull { it.length }
    }
}

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
        // Kotlin/Native не поддерживает linux-aarch64 как **хост**: компилятора под него нет.
        // Объявить таргет мало — Gradle пропустит все задачи компиляции и отрапортует
        // BUILD SUCCESSFUL, не собрав ни строчки. Молчаливо зелёная сборка хуже красной,
        // поэтому падаем явно.
        System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "aarch64" -> error(
            "mongkn: Kotlin/Native не умеет компилировать на linux-aarch64. " +
                "В контейнере на Apple Silicon запускайте образ как --platform linux/amd64, " +
                "либо собирайте Linux-таргет в CI на x86_64-раннере."
        )
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

            /*
             * На Linux нужен --allow-shlib-undefined, и это не перестраховка.
             *
             * Kotlin/Native линкует своим sysroot с намеренно старой glibc — ради переносимости
             * бинарника. Системная libbson собрана против glibc дистрибутива, которая новее,
             * и тянет символы вроде strlcpy@GLIBC_2.38 и pthread_once@GLIBC_2.34. ld.lld по
             * умолчанию считает неразрешённые ссылки **внутри чужой .so** ошибкой и падает.
             *
             * Разрешать их на этапе линковки незачем: динамический загрузчик найдёт их в реальной
             * glibc системы при запуске. Флаг снимает именно эту проверку и только для
             * разделяемых библиотек — неразрешённые символы нашего кода по-прежнему ошибка.
             */
            val platformOpts = if (System.getProperty("os.name") == "Linux") {
                listOf("-Wl,--allow-shlib-undefined")
            } else {
                emptyList()
            }
            linkerOpts(
                libDirs.flatMap { listOf("-L${it.absolutePath}") } +
                    listOf("-l$mongoc", "-l$bson") + platformOpts
            )
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
    // Адрес mongod в CI и в Linux-контейнере не 127.0.0.1 — см. support/TestServer.kt.
    providers.environmentVariable("MONGKN_TEST_HOST").orNull?.let { environment("MONGKN_TEST_HOST", it) }
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
