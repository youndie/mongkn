import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// Публикация в приватный Reposilite — общая для всех выкладываемых модулей.

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
val mongocPrefixes: List<File> =
    run {
        val explicit =
            providers.gradleProperty("mongkn.prefix").orNull
                ?: providers.environmentVariable("MONGKN_PREFIX").orNull
        if (explicit != null) {
            listOf(File(explicit))
        } else {
            listOf("/opt/homebrew", "/usr/local", "/usr").map(::File)
        }
    }

/**
 * Homebrew 2.x кладёт заголовки в версионированные каталоги:
 * `<prefix>/include/mongoc-2.1.1/mongoc/mongoc.h`. Системная установка 1.x — в
 * `<prefix>/include/libmongoc-1.0/mongoc/mongoc.h`. Ищем каталог, в котором лежит `<rel>`.
 */
fun findIncludeDir(rel: String): File? =
    mongocPrefixes
        .map { File(it, "include") }
        .filter { it.isDirectory }
        .flatMap { include -> listOf(include) + (include.listFiles()?.filter { it.isDirectory } ?: emptyList()) }
        .firstOrNull { File(it, rel).isFile }

/** Каталог, в котором лежит библиотека с этим именем. */
fun findLibDir(name: String): File? =
    libDirs.firstOrNull { dir ->
        dir.listFiles()?.any { it.name == "lib$name.dylib" || it.name == "lib$name.so" } == true
    }

/**
 * Каталоги с библиотеками.
 *
 * Кроме `<prefix>/lib` обязательно смотрим на уровень ниже: Debian и Ubuntu кладут библиотеки
 * в multiarch-каталог `/usr/lib/aarch64-linux-gnu`, и без этого на Linux ничего не находится.
 */
val libDirs: List<File> =
    mongocPrefixes
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
        libDir
            .listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith(".dylib") || it.endsWith(".so") }
            ?.map { it.removePrefix("lib").substringBefore(".dylib").substringBefore(".so") }
            ?.filter { allowed.matches(it) }
            ?.minByOrNull { it.length }
    }
}

/**
 * Описание cinterop с **вписанными** опциями линковки.
 *
 * Исходный `mongoc.def` их не содержит: имена библиотек и каталоги у 1.x и 2.x разные, и проект
 * разрешает их перебором (решение Р1). Но опции линковки обязаны попасть в **klib**, иначе
 * потребитель получит библиотеку, которую нечем слинковать, — проверено сборкой настоящего
 * потребителя против опубликованного артефакта.
 *
 * Поэтому `.def` собирается: исходный текст плюс `linkerOpts` и `libraryPaths` с уже
 * разрешёнными значениями. Это не отменяет решение Р1, а доводит его до конца — раньше
 * разрешение доезжало только до наших собственных бинарников.
 */
val generatedDefinition: Provider<RegularFile> =
    layout.buildDirectory.file("cinterop/mongoc.def").also { target ->
        val source = project.file("src/nativeInterop/cinterop/mongoc.def")
        val mongoc = findLibName("mongoc") ?: error("mongkn: не найдена libmongoc в $libDirs")
        val bson = findLibName("bson") ?: error("mongkn: не найдена libbson в $libDirs")
        // Только те каталоги, где библиотеки действительно лежат. `libDirs` — это ещё и все
        // подкаталоги `lib`, нужные для перебора; вписывать их полсотни в klib незачем.
        val neededDirs = listOf(mongoc, bson).mapNotNull(::findLibDir).distinct()
        /*
         * На Linux нужен --allow-shlib-undefined, и это не перестраховка.
         *
         * Kotlin/Native линкует своим sysroot с намеренно старой glibc — ради переносимости
         * бинарника. Системная libbson собрана против glibc дистрибутива, которая новее,
         * и тянет символы вроде strlcpy@GLIBC_2.38 и pthread_once@GLIBC_2.34. ld.lld по
         * умолчанию считает неразрешённые ссылки **внутри чужой .so** ошибкой и падает.
         *
         * Флаг тоже обязан ехать в klib: у потребителя та же самая libbson и та же самая
         * старая glibc в sysroot. Пока он стоял только в `binaries.all`, потребитель упирался
         * в эти два символа — проверено.
         */
        val platformLinkerOpts =
            // Без префикса `-Wl,`: из `.def` опции уходят прямо в `ld.lld`, а не через драйвер
            // компилятора, и тот отвечает `unknown argument '-Wl,--allow-shlib-undefined'`.
            if (System.getProperty("os.name") == "Linux") "--allow-shlib-undefined " else ""
        val file = target.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            source.readText() +
                """

                # Ниже — сгенерировано mongkn-core/build.gradle.kts, править здесь бесполезно.
                #
                # Эти строки и делают klib пригодным для потребителя: без них он собирается
                # и публикуется, но не линкуется на чужой стороне.
                #
                # Каталог поиска указан **внутри** `linkerOpts`, а не только в `libraryPaths`,
                # и это не дублирование: `libraryPaths` действует на этапе cinterop, а до
                # линковки у потребителя не доходит. С одним лишь `libraryPaths` ошибка меняется
                # с «undefined symbol» на «unable to find library -lmongoc-1.0» — проверено.
                linkerOpts = $platformLinkerOpts${neededDirs.joinToString(
                    " ",
                ) { "-L" + it.absolutePath }} -l$mongoc -l$bson
                libraryPaths = ${neededDirs.joinToString(" ") { it.absolutePath }}
                """.trimIndent() + "\n",
        )
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
    // OPTED IN OUT LOUD. `sborka.kmp` compiles with `allWarningsAsErrors`, and these two APIs were
    // being used with the compiler asking to be told so on every build — a warning nobody read
    // because nothing failed on it. Saying it here is the same statement the annotation would make
    // at each use site, made once and visible: this library depends on APIs its authors may change.
    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }

    /*
     * Проверка бинарной совместимости, встроенная в KGP (M-31).
     *
     * `keepLocallyUnsupportedTargets = false` — по умолчанию плагин **достраивает** ABI для
     * таргетов, которые хост собрать не может, выводя их из имеющихся. Для библиотеки это
     * означает дамп, часть которого никто никогда не проверял. Пусть лучше падает.
     */
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // `enabled` из ранних версий убрано: сам вызов abiValidation { } и включает проверку.
        keepLocallyUnsupportedTargets.set(false)
    }

    // Кросс-компиляция cinterop требует заголовков целевой платформы, которых на хосте нет,
    // поэтому собираем только хостовый таргет. Матрица таргетов — задача CI, см. M-13.
    val hostTarget: KotlinNativeTarget =
        when {
            System.getProperty("os.name") == "Mac OS X" && System.getProperty("os.arch") == "aarch64" -> {
                macosArm64()
            }

            // Kotlin/Native не поддерживает linux-aarch64 как **хост**: компилятора под него нет.
            // Объявить таргет мало — Gradle пропустит все задачи компиляции и отрапортует
            // BUILD SUCCESSFUL, не собрав ни строчки. Молчаливо зелёная сборка хуже красной,
            // поэтому падаем явно.
            System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "aarch64" -> {
                error(
                    "mongkn: Kotlin/Native не умеет компилировать на linux-aarch64. " +
                        "В контейнере на Apple Silicon запускайте образ как --platform linux/amd64, " +
                        "либо собирайте Linux-таргет в CI на x86_64-раннере.",
                )
            }

            System.getProperty("os.name") == "Linux" -> {
                linuxX64()
            }

            else -> {
                error("mongkn: неподдерживаемый хост ${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
            }
        }

    hostTarget.apply {
        compilations.getByName("main") {
            cinterops.create("mongoc") {
                definitionFile.set(generatedDefinition)
                packageName("mongkn.cinterop")

                val mongocInclude = findIncludeDir("mongoc/mongoc.h")
                val bsonInclude = findIncludeDir("bson/bson.h")
                if (mongocInclude == null || bsonInclude == null) {
                    error(
                        "mongkn: не найдены заголовки mongo-c-driver. " +
                            "Поставьте драйвер (`brew install mongo-c-driver`) или укажите " +
                            "-Pmongkn.prefix=<префикс установки>. Искали в: $mongocPrefixes",
                    )
                }
                includeDirs(mongocInclude, bsonInclude)
            }
        }

        /*
         * Бенчмарк (M-76) — отдельный **release**-исполняемый файл, а не тест.
         *
         * Причина одна и она принципиальная: тестовые бинарники Kotlin/Native собираются
         * в DEBUG, без инлайнинга и с проверками. Числа, снятые на них, описывают отладочную
         * сборку и никого не касаются. Держать же весь тестовый набор ещё и в release значило бы
         * удваивать время каждой сборки ради задачи, которая запускается вручную.
         *
         * Собирается из тестового компиляционного набора: бенчмарку нужен и публичный API,
         * и cinterop напрямую — иначе не с чем сравнивать.
         */
        binaries.executable("benchmark", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE)) {
            compilation = compilations.getByName("test")
            entryPoint = "ru.workinprogress.mongkn.benchmark.main"
        }

        // Опций линковки здесь больше нет: все они в сгенерированном `.def` и потому едут
        // в klib. Пока они стояли тут, наши собственные бинарники собирались, а у потребителя
        // библиотека не линковалась вовсе.
    }

    // Source set'ы `nativeMain` / `nativeTest` заводить руками нельзя: их уже создаёт
    // стандартный шаблон иерархии KMP, и ручной `by creating` его ломает — компиляция
    // начинает резолвиться против common-метаданных, где `Dispatchers.IO` объявлен internal.
    // Каталоги `src/nativeMain/kotlin` и `src/nativeTest/kotlin` подхватываются шаблоном сами.
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            api(libs.serialization.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    dependsOn(seedDiffReference)
    /*
     * `-Pmongkn.skipTls` исключает тесты TLS-контура.
     *
     * Заведено ради одного места — macOS-джобы в CI, — и вот почему без него не обойтись.
     * На macOS-раннере GitHub нет docker, поэтому mongod с TLS приходится поднимать процессом,
     * а он там собран с **Secure Transport** вместо OpenSSL и на наших сертификатах падает
     * с `Abort trap: 6` внутри `SSLManagerApple::initSSLContext`. Это ограничение сервера,
     * а не наша настройка: локально на macOS тот же контур прекрасно работает — в контейнере.
     *
     * Флаг именно в командной строке, а не условие внутри тестов: так в логе сборки видно,
     * что проверено не всё. Тесты по-прежнему **падают** без своего сервера, если их не
     * исключили явно, — это правило не смягчается (см. CLAUDE.md).
     *
     * TLS проверяется на Linux каждым прогоном, поэтому дыры в покрытии не возникает.
     */
    if (providers.gradleProperty("mongkn.skipTls").isPresent) {
        filter.excludeTestsMatching("*TlsTest*")
    }
    // Корректность этих тестов зависит от состояния mongod, а его Gradle не отслеживает.
    // Без этой строки задача может оказаться UP-TO-DATE и не отработать, тогда как фаза A
    // уже вычистила коллекции, — и фаза C падает на пустой `written`. Ровно так и случилось
    // при первом же инкрементальном прогоне после удаления генератора.
    outputs.upToDateWhen { false }
    // Путь к фикстуре нативный тест получает через окружение: Gradle-свойства ему недоступны.
    dependsOn("fetchSpecTests")
    // Адрес mongod в CI и в Linux-контейнере не 127.0.0.1 — см. support/TestServer.kt.
    providers.environmentVariable("MONGKN_TEST_HOST").orNull?.let { environment("MONGKN_TEST_HOST", it) }
    providers.environmentVariable("MONGKN_TEST_AUTH_HOST").orNull?.let { environment("MONGKN_TEST_AUTH_HOST", it) }
    providers.environmentVariable("MONGKN_TEST_TLS_HOST").orNull?.let { environment("MONGKN_TEST_TLS_HOST", it) }
    // Адрес mongos: в Linux-контейнере кластер живёт не на 127.0.0.1, как и остальные серверы.
    providers.environmentVariable("MONGKN_TEST_SHARD_HOST").orNull?.let { environment("MONGKN_TEST_SHARD_HOST", it) }
    // Путь к сертификатам — абсолютный: `tlsCAFile` в строке подключения относительный не простит,
    // а рабочий каталог нативного теста не определён.
    environment(
        "MONGKN_TLS_DIR",
        // Корневой build, а не модульный: сертификаты нужны и серверу в контейнере, и тестам,
        // поэтому лежат в одном очевидном месте на весь репозиторий (см. ci/tls/generate.sh).
        rootProject.layout.buildDirectory
            .dir("tls")
            .get()
            .asFile.absolutePath,
    )
    environment(
        "MONGKN_SPEC_TESTS",
        layout.buildDirectory
            .dir("spec-tests")
            .get()
            .asFile.absolutePath,
    )
    environment(
        "MONGKN_DIFF_FIXTURE",
        project(":mongkn-difftest")
            .layout.buildDirectory
            .file("diff/reference.json")
            .get()
            .asFile.absolutePath,
    )
    finalizedBy(verifyDiffWritten)
}

/*
 * ABI-дамп снимается на **linuxX64** — на той платформе, которая публикуется.
 *
 * Собирается один таргет на хост (решение Р6), поэтому дамп несёт строку `// Targets: [linuxX64]`
 * либо `[macosArm64]`, и закоммитить можно только один. Эталоном обязан быть публикуемый таргет:
 * именно его ABI видит потребитель, и проверять имеет смысл его, а не тот, что остаётся на машине
 * разработчика.
 *
 * Раньше эталоном был macosArm64 — с обоснованием, что различается только строка заголовка.
 * Обоснование устарело незаметно: к моменту проверки дампы разошлись на четыре объявления,
 * и все четыре были свежим публичным API. Причина не в платформах — дамп просто перестали
 * обновлять, а проверка этого не показывала: на Linux она была выключена, а на macOS полная
 * сборка падала раньше, на известном красном тесте M-87, и до `checkKotlinAbi` не доходила.
 * Мораль: красный тест, который «известен», прячет за собой всё, что стоит в очереди после него.
 *
 * На macOS проверка отключается явной причиной, а не молчком: иначе сборка падала бы на строке
 * заголовка. Прогнать её локально с макбука можно за секунды — `./ci/wsl-run.sh
 * :mongkn-core:checkKotlinAbi`.
 */
tasks.matching { it.name == "checkKotlinAbi" }.configureEach {
    onlyIf("эталонный ABI-дамп снимается на linuxX64 — публикуемой платформе") {
        System.getProperty("os.name") == "Linux"
    }
}
