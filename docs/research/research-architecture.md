---
id: research-architecture
title: mongkn — архитектурный research
status: active
date: 2026-08-04
---

# research — архитектура mongkn

`mongkn` — обвязка MongoDB для Kotlin/Native поверх официального C-драйвера (`libmongoc`).
Ниша узкая и вполне конкретная: на Kotlin/Native официального драйвера MongoDB нет вообще —
`mongodb-driver-kotlin-coroutine` живёт только на JVM. Всё, что остаётся нативному приложению
сегодня, — писать `cinterop` руками. `mongkn` пытается сделать так, чтобы этот `cinterop` был
написан один раз и спрятан за API, формой повторяющим официальный корутинный драйвер: тот же
`MongoClient → MongoDatabase → MongoCollection`, те же `suspend fun insertOne` и `fun find(): Flow`.

Отправная точка — [исходный драфт](source-draft.md), разбитый на 4 фазы. Он не руководство
к действию, а вход в ресёрч. Ресёрч подтвердил две фазы из четырёх практически как есть
и нашёл **три места, где драфт приведёт к неработающему или небезопасному коду** — они собраны
в разделе 2 как отклонения.

Документ фиксирует **проверенные факты** (что реально прочитано в заголовках, скомпилировано и
запущено), **принятые решения** и **риски**. Всё, что не проверено, помечено как гипотеза.

---

## 1. Проверенные факты

### 1.1 Установленный драйвер — 2.x, а не 1.x, и линкуется он иначе

Драфт исходит из libmongoc 1.x: `linkerOpts = -lmongoc-1.0 -lbson-1.0` и заголовки прямо
в `/opt/homebrew/include`. На машине разработки стоит 2.x, и там всё иначе.

| Факт | Где проверено |
|---|---|
| Установлена версия 2.1.1 (в brew уже 2.3.3) | `brew info mongo-c-driver` |
| Библиотеки называются `libmongoc2` / `libbson2`, а не `libmongoc-1.0` | `/opt/homebrew/lib/libmongoc2.dylib`, `libbson2.dylib` |
| Заголовки лежат в **версионированном** каталоге | `/opt/homebrew/include/mongoc-2.1.1/mongoc/mongoc.h`, `/opt/homebrew/include/bson-2.1.1/bson/bson.h` |
| pkg-config-описания есть, а самого `pkg-config` в системе нет | `/opt/homebrew/lib/pkgconfig/mongoc2.pc` существует; `pkg-config --cflags mongoc2` → `command not found` |
| `.def`-файл cinterop не поддерживает pkg-config: только `compilerOpts` / `linkerOpts` и их платформенные варианты (`linkerOpts.osx`) | [kotlinlang.org — Definition file](https://kotlinlang.org/docs/native-definition-file.html) |

**Следствие 1.** Пути и имена библиотек нельзя хардкодить в `.def`: `-lmongoc-1.0` не слинкуется,
а `-I/opt/homebrew/include` не найдёт `mongoc/mongoc.h`. Ещё хуже — версия зашита в путь, и
обычный `brew upgrade mongo-c-driver` ломает сборку.

**Следствие 2.** Раз pkg-config недоступен, разрешение путей уехало в Gradle: `findIncludeDir()`
ищет каталог, в котором лежит `mongoc/mongoc.h`, перебирая префиксы и их версионированные
подкаталоги, а `findLibName()` выводит `mongoc2` / `mongoc-1.0` из того, что реально лежит
в `<prefix>/lib`. Код — [mongkn-core/build.gradle.kts](../../mongkn-core/build.gradle.kts).
Переопределяется через `-Pmongkn.prefix=…` или `MONGKN_PREFIX`. В самом
[mongoc.def](../../mongkn-core/src/nativeInterop/cinterop/mongoc.def) остались только `headers`,
`headerFilter` и `package`.

### 1.2 cinterop и линковка работают — проверено компиляцией и запуском

Не «должно завестись», а завелось: сначала напрямую тулчейном `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.10/bin/cinterop`
+ `kotlinc-native` (мимо Gradle, чтобы отделить проблемы сборки от проблем линковки), потом
через Gradle.

| Факт | Где проверено |
|---|---|
| `cinterop` по этому `.def` даёт klib на 186 КБ без ошибок | прямой прогон `cinterop -def mongoc.def -target macos_arm64` |
| Слинкованный бинарник запускается и печатает версии рантайма `2.1.1` / `2.1.1` | прогон `probe.kexe` |
| `bson_new` / `bson_append_utf8` / `bson_append_int32` / `bson_iter_*` / `bson_as_relaxed_extended_json` доступны из Kotlin и дают `{ "hello" : "world", "n" : 42 }` | тот же прогон |
| Сборка Gradle зелёная, тест хостового таргета проходит | `./gradlew :mongkn-core:build` → `BUILD SUCCESSFUL`, задача `macosArm64Test` |

**Следствие.** Фаза 1 драфта закрыта, но с поправкой из 1.1. Живой код — [Mongkn.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/Mongkn.kt).

### 1.3 Сетевой путь проверен против настоящего mongod

Прогон против `mongo:8` в Docker: `ping` → `insert_one` → ошибка дубликата → курсор.

| Факт | Где проверено |
|---|---|
| `mongoc_client_command_simple` с `{ping:1}` возвращает `{ "ok" : 1.0 }` | прогон `net.kexe` |
| `mongoc_collection_insert_one` кладёт в `reply` **`{ "insertedCount" : 1, "insertedId" : { "$oid" : "…" } }`** | тот же прогон |
| Дубликат `_id` даёт `ok=false`, `domain=12`, `code=11000`, `message = "E11000 duplicate key error collection: …"` | тот же прогон |
| `domain=12` — это `MONGOC_ERROR_COLLECTION` (12-й элемент enum, нумерация явно начата с `= 1`) | `/opt/homebrew/include/mongoc-2.1.1/mongoc/mongoc-error.h`, `mongoc_error_domain_t` |
| `mongoc_collection_find_with_opts` + `mongoc_cursor_next` отдают вставленные документы, `mongoc_cursor_error` → `false` | тот же прогон |
| `bson_error_t.message` — `char[512]`, читается из Kotlin как `err.message.toKString()` без ручной возни с байтами | `bson/error.h`: `typedef struct _bson_error_t { uint32_t domain; uint32_t code; char message[…]; uint8_t reserved; }`, `BSON_STATIC_ASSERT2 (error_t, sizeof (bson_error_t) == 512)` |
| `bson_t` — 128 байт (`uint32 flags; uint32 len; uint8 padding[120]`), рассчитан на размещение на стеке | `bson/bson_t.h` |

**Следствие 1.** `insertOne` может честно возвращать вставленный `_id`, а не `Boolean` из драфта, —
сервер и так его отдаёт. Это заодно сближает форму API с официальным драйвером, где возвращается
`InsertOneResult`.

**Следствие 2.** Раз `bson_t` — обычная 128-байтовая структура, out-параметры (`reply`) корректно
размещаются через `alloc<bson_t>()` в `memScoped`. Но освобождать содержимое всё равно надо
явным `bson_destroy` **и при успехе тоже**: `memScoped` вернёт 128 байт стека, а не то, что
`libbson` доаллоцировал в куче.

**Следствие 3.** Ошибки поднимаются из `bson_error_t` без потерь — отсюда форма
[MongoException](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/MongoException.kt)
с `domain` + `code` + текстом.

### 1.4 `mongoc_client_t` не потокобезопасен — а `Dispatchers.Default` многопоточный

Два факта, которые по отдельности безобидны, а вместе ломают ровно ту схему, что предлагает драфт.

| Факт | Где проверено |
|---|---|
| «Since `mongoc_client_t` structures are not thread-safe, `mongoc_client_pool_t` is used to retrieve a new `mongoc_client_t` for a given thread» | [MongoDB Docs — Connection Pools](https://www.mongodb.com/docs/languages/c/c-driver/current/connect/connection-options/connection-pools/) |
| `mongoc_client_pool_t` потокобезопасен, кроме `mongoc_client_pool_destroy()` | там же |
| API пула есть и в 2.x: `mongoc_client_pool_new_with_error`, `_pop`, `_try_pop`, `_push`, `_max_size`, `_destroy` | `/opt/homebrew/include/mongoc-2.1.1/mongoc/mongoc-client-pool.h` |
| На Kotlin/Native `Dispatchers.Default` подкреплён пулом потоков по числу ядер — с kotlinx.coroutines **1.7.0** (#3366) | [CHANGES.md kotlinx.coroutines](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/master/CHANGES.md) |
| ~~`Dispatchers.IO` доступен на Kotlin/Native с **1.7.0** (#3205)~~ — **неверно для 1.11.0**, см. §1.8 | там же |
| Актуальная версия kotlinx.coroutines — 1.11.0 | `maven-metadata.xml` в Maven Central |

**Следствие.** Драфтовое «оберни вызов в `withContext(Dispatchers.Default)`» при одном общем
`mongoc_client_t` — это undefined behaviour, а не просто неоптимальность: соседние вызовы уедут
на разные потоки пула и полезут в один и тот же неблокируемый клиент. Причём воспроизводиться
будет редко и не в тестах. Отсюда решение [Р2](#р2-ресурсная-модель--пул-клиентов-а-не-один-общий-клиент).

Дополнительно: `Dispatchers.Default` — процессорный пул, а вызовы `libmongoc` **блокирующие**
(асинхронного API у C-драйвера нет вовсе). Занимать им процессорный пул неправильно и по этой
причине тоже — блокирующему вводу-выводу место в `Dispatchers.IO`.

### 1.5 KSP видит нативный classpath, а не JVM-jar официального драйвера

Фаза 4 драфта устроена так: положить `mongodb-driver-kotlin-coroutine` как `compileOnly`,
попросить у KSP `resolver.getClassDeclarationByName("com.mongodb.kotlin.client.coroutine.MongoCollection")`
и сгенерировать зеркальный нативный API.

| Факт | Где проверено |
|---|---|
| KSP поддерживает нативные таргеты; процессор подключается **по таргету**: `add("kspMacosArm64", …)` | [kotlinlang.org — KSP with Kotlin Multiplatform](https://kotlinlang.org/docs/ksp-multiplatform.html) |
| Общая конфигурация `ksp(…)` в KSP2 объявлена deprecated — «You must configure each target explicitly» | там же |
| KSP заводит отдельные задачи обработки на каждую пару таргет × компиляция (`main` / `test`); обработки `commonMain` нет | там же |
| С версии 2.3.0 KSP отказался от схемы `<kotlin>-<ksp>`: последняя — просто `2.3.11` | `maven-metadata.xml` `com.google.devtools.ksp:symbol-processing-api` |
| В 2.3.10 есть фикс «works with Kotlin 2.4.0 default module names» | [релизы KSP](https://github.com/google/ksp/releases) |

**Следствие.** Процессор, подключённый как `kspMacosArm64`, работает над classpath **нативной**
компиляции — а это klib'ы. JVM-jar официального драйвера туда не попадает и попасть не может,
значит `getClassDeclarationByName(...)` вернёт `null`. Схема из драфта не заработает не из-за
мелочи в конфигурации, а по устройству. Отсюда [Р5](#р5-генерация-живёт-в-jvm-модуле-и-отдаёт-исходники-в-nativemain).

### 1.6 Версии инструментов на 2026-08-04

| Факт | Где проверено |
|---|---|
| Kotlin stable — 2.4.10 (`2.4.20-Beta2` в бете) | `maven-metadata.xml` `org.jetbrains.kotlin:kotlin-gradle-plugin` |
| KGP 2.4.0–2.4.10 поддерживает Gradle **7.6.3–9.5.0** | [kotlinlang.org — Configure a Gradle project](https://kotlinlang.org/docs/gradle-configure-project.html) |
| Актуальный Gradle — 9.6.1, то есть **выше** поддерживаемого потолка | `https://services.gradle.org/versions/current` |
| В `~/.konan` уже лежит `kotlin-native-prebuilt-macos-aarch64-2.4.10` | листинг каталога |
| KotlinPoet — 2.3.0, `mongodb-driver-kotlin-coroutine` — 5.9.1 | `maven-metadata.xml` в Maven Central |

**Следствие.** Wrapper зафиксирован на **9.5.0**, а не на «последнем» 9.6.1 —
[gradle-wrapper.properties](../../gradle/wrapper/gradle-wrapper.properties). Тулчейн Kotlin/Native
уже прогрет, первая сборка не тянет 1.5 ГБ.

### 1.7 Что уже делали до нас

[knMongoc](https://github.com/exertionriver/knMongoc) — демонстрация CRUD через `cinterop`
к libmongoc. Собран на Kotlin 1.4.30 и mongo-c-driver 1.17.3, то есть до нового менеджера памяти
Kotlin/Native и до 2.x C-драйвера. Как ориентир по составу вызовов годится, как основа — нет:
всё, что касается потоков и памяти, там из другой эпохи.

### 1.8 Найдено при реализации M1–M3

Четыре факта, которых ресёрч «на бумаге» не дал: три из них всплыли только на компиляции и
на живых тестах, а один прямо опровергает запись из §1.4.

#### `mongoc_init()` / `mongoc_cleanup()` — одноразовые на процесс

| Факт | Где проверено |
|---|---|
| «Call `mongoc_init()` exactly once at the beginning of your program… Note that `mongoc_init()` does **not** reinitialize the driver after `mongoc_cleanup()`» | [mongoc_init — документация](https://mongoc.org/libmongoc/current/mongoc_init.html) |
| Нарушение роняет процесс на следующем же сетевом вызове: `_mongoc_handshake_freeze(): assertion failed: pthread_mutex_lock ((&gHandshakeLock)) == 0` | падение `MongoIntegrationTest` при первом прогоне |

**Поправка к M0.** В M-03 `Mongkn` считал ссылки: `mongoc_cleanup()` на нуле, `mongoc_init()` на
подъёме с нуля. Модель выглядела аккуратно и была неверной. Поймалось это не тестом инициализации
(он проходил в одиночку), а порядком запуска: `MongknInitTest` опускал счётчик до нуля, и
следующий за ним интеграционный тест получал драйвер с уже уничтоженным глобальным мьютексом.
Сейчас — автомат без возврата `NEW → INITIALIZING → READY → SHUT_DOWN`, повторная инициализация
после `shutdown()` даёт `IllegalStateException`. Отсюда же: `MongoClient.close()` **не** зовёт
`Mongkn.shutdown()`, иначе закрытие одного клиента ломало бы все остальные.

#### `Dispatchers.IO` на Kotlin/Native — `internal`

| Факт | Где проверено |
|---|---|
| В `kotlinx-coroutines-core-macosArm64Main` 1.11.0 объявлено `internal final val IO: CoroutineDispatcher` | `klib dump-metadata` по артефакту из `~/.gradle/caches` |
| То же самое в 1.10.2 — это не регрессия свежей версии | тот же приём по артефакту 1.10.2 |
| При этом [публичная документация](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html) утверждает: «available on the JVM and Native targets» | — |
| `newFixedThreadPoolContext` и `newSingleThreadContext` на Native **публичны** | тот же дамп |

**Следствие.** Запись в §1.4 про доступность `Dispatchers.IO` с 1.7.0 верна для changelog и неверна
для сегодняшнего артефакта: компилятор отвечает `Cannot access 'val IO': it is internal`. Хороший
пример, почему память и changelog источником не являются, а артефакт — является.

**Как решено.** [MongoClient](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/MongoClient.kt)
владеет собственным `newFixedThreadPoolContext` и закрывает его в `close()`. Побочная выгода:
размер пула потоков стал явным и привязан к времени жизни клиента, а не глобальным. Число потоков
по умолчанию (4) намеренно меньше размера пула клиентов libmongoc (100): клиент занят всё время
жизни курсора, а поток — только пока идёт вызов.

#### Мелочи, стоившие по сборке каждая

| Факт | Где проверено |
|---|---|
| C-энум `bson_type_t` cinterop отрендерил не Kotlin-энумом, а `typealias` на `UInt` с top-level-константами — из-за `BSON_TYPE_MINKEY = 0xFF` | `klib dump-metadata` по cinterop-klib |
| Ручное создание source set'а `nativeMain` ломает стандартный шаблон иерархии KMP: компиляция начинает резолвиться против common-метаданных | предупреждение `Default Kotlin Hierarchy Template Not Applied Correctly` + неверный резолв `Dispatchers.IO` |
| Имя теста в обратных кавычках на Kotlin/Native не может содержать запятую | `e: Name contains illegal characters: ","` |

### 1.9 Найдено при реализации M4–M5

| Факт | Где проверено |
|---|---|
| `FindFlow<T> implements kotlinx.coroutines.flow.Flow<T>` (через делегирование) | `javap -p` по `FindFlow.class` из jar `mongodb-driver-kotlin-coroutine` 5.9.1 |
| `FindFlow` несёт 23 метода чейнинга при 34 публичных методах | тот же `javap` |
| У каждой операции `MongoCollection` есть перегрузка с `ClientSession`; у `find` вдобавок варианты с `Class<R>` | `javap -p` по `MongoCollection.class` |
| KSP **2.3.11 работает с Kotlin 2.4.10**: `kspKotlin` отрабатывает, `getClassDeclarationByName` резолвит класс из jar на classpath | прогон `./gradlew :mongkn-api-spec:kspKotlin` |
| `resolver.getClassDeclarationByName` принимает `KSName`, а не `String` — для строки есть `resolver.getKSNameFromString` | ошибка компиляции процессора |
| KSP добавляет свой выходной каталог в source set **после** конфигурации build-файла, поэтому `setSrcDirs` его не убирает | сборка `:mongkn-api-spec` падала на `Unresolved reference 'bson'` в сгенерированном файле |

**Следствие 1 (закрывает открытый вопрос 2).** Раз `FindFlow` — это `Flow`, выбор между
«голый `Flow<Document>`» и «`FindFlow` с чейнингом» оказался не развилкой, а порядком работ:
второе расширяет первое без единого ломающего изменения. Отсюда решение Р8.

**Следствие 2 (снимает риск 1).** Совместимость KSP с Kotlin 2.4.10 была гипотезой с планом
отступления на рефлексию по jar. Отступление не понадобилось: связка собралась. Гипотеза §1.5
о том, что процессор обязан жить на JVM, тоже подтвердилась — но с другой стороны, чем ожидалось:
проблема не в том, что KSP не увидел бы jar, а в том, что JVM-модуль пытается **скомпилировать**
сгенерированный нативный код. Лечится исключением каталога из задачи компиляции.

### 1.10 Измерение выгоды генератора — премиса Р7 не подтвердилась

Решение Р7 обосновывало генерацию тем, что «зеркало даёт **покрытие** дёшево (сотни методов,
печатаются генератором)». Это утверждение было принято без проверки: генератор тогда печатал две
операции. Проверено расширением до шести (`insertOne`, `insertMany`, `updateOne`, `deleteOne`,
`countDocuments`, `find`).

**Что стоило добавление четырёх операций:**

| Что | Строк |
|---|---|
| сгенерировано (прирост `MongoCollection.kt`) | **+30** |
| рукописная реализация в `CollectionOps` | +112 |
| новые типы результата (`InsertManyResult`, `UpdateResult`, `DeleteResult`) | +36 |
| правки самого процессора (маппинг типов, выбор перегрузок) | +36 |

Итого примерно **1 сгенерированная строка на 6 рукописных**. Стоимость операции в реализации:
`deleteOne` — 13 строк, `countDocuments` — 15, `insertMany` — 21, `updateOne` — 24.

**Вывод: покрытие генератор не удешевляет.** Каждая операция всё равно требует рукописной
реализации на libmongoc, потому что C-драйвер неоднороден: `count_documents` отдаёт результат
возвращаемым значением и сигналит об ошибке отрицательным числом, `insert_many` принимает
`const bson_t **`, `update_one` возвращает в `reply` три поля. Дешёвого «зеркалирования сотен
методов» здесь не бывает — генератор печатает фасад, а работа вся под ним.

**Что генератор всё-таки даёт — и это подтвердилось:**

- имена операций и параметров, признак `suspend` и форма результата берутся из артефакта,
  а не угадываются. `updateOne(filter, update)` названо так не потому, что мне так показалось;
- **механически разрешена неоднозначность, которую человек бы проглядел**: у `updateOne` две
  перегрузки одинаковой длины — `(Bson, Bson)` и `(Bson, List<Bson>)`, то есть обновление
  документом и агрегационным конвейером. Выбор «по глазам» здесь — монетка;
- незнакомый тип параметра **роняет сборку**, так что молча разойтись с официальным API нельзя;
- расхождение видно в диффе: изменится драйвер — изменится сгенерированный файл.

То есть генератор покупает **точность и защиту от расхождения**, а не дешёвое покрытие.

**Честная оценка целесообразности.** 241 строка процессора плюс два дополнительных модуля плюс
усложнение сборки — ради 68 строк фасада. На шести операциях это примерно безубыточно, и если
считать только строки — скорее в минус. Оправдано это будет либо при заметном росте числа
операций (стоимость процессора разово амортизируется), либо если защита от расхождения
с официальным API считается самостоятельной ценностью. Ни того, ни другого прототип пока
не доказал — см. правку к Р7.

### 1.11 Чем проверяют соответствие драйвера

| Факт | Где проверено |
|---|---|
| У MongoDB есть каноническая проверка соответствия: CRUD-тесты в [unified test format](https://github.com/mongodb/specifications/blob/master/source/unified-test-format/unified-test-format.md) — платформонезависимые JSON/YAML-сценарии, которые прогоняет каждый официальный драйвер | [mongodb/specifications](https://github.com/mongodb/specifications/tree/master/source/crud/tests) |
| libbson умеет читать эти файлы сам: `bson_new_from_json`, `bson_json_reader_new_from_file` | `bson/bson.h:166`, `bson/bson-json.h:77` |
| Хук аллокатора в libbson **существует**: `bson_mem_vtable_t`, `bson_mem_set_vtable`, `bson_mem_restore_vtable` | `bson/memory.h:28,38,41` |
| В KGP есть встроенная ABI-валидация: `kotlin { abiValidation { } }`, задачи `checkKotlinAbi` (цепляется к `check`) и `updateKotlinAbi`, помечена `@OptIn(ExperimentalAbiValidation::class)` | [kotlinlang.org — Binary compatibility validation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html) |
| Если хост не собирает все таргеты, плагин **достраивает** ABI недостающих по имеющимся; отключается `keepLocallyUnsupportedTargets.set(false)` | там же |
| `binary-compatibility-validator` 0.18.1 в maintenance mode и вытеснен встроенной валидацией | [репозиторий BCV](https://github.com/Kotlin/binary-compatibility-validator) |

**Следствие 1.** Риск 3 перестал быть гипотезой в части наличия API: счётчик аллокаций libbson
построить можно. Непроверенным осталось только то, ложится ли `bson_mem_vtable_t` на
`staticCFunction`.

**Следствие 2 (важное взаимодействие с Р6).** Мы собираем только хостовый таргет, поэтому
`abiValidation` по умолчанию **выдумает** дамп для `linuxX64` из `macosArm64`. То есть ABI-дамп
будет либо ложью, либо (при `keepLocallyUnsupportedTargets = false`) красной сборкой. Включать
ABI-валидацию имеет смысл только вместе с реальной сборкой под Linux — задача M-17.

**Следствие 3.** Поддержку klib встроенной валидацией страница явно не подтверждает. Проверяется
одним прогоном; пока это гипотеза.

### 1.12 Таймаут корутины не прерывает блокировку внутри C

Проверено намеренной поломкой при сдаче M-29: из `MongoClient.withClient` убран
`mongoc_client_pool_push` в `finally`, то есть клиенты перестали возвращаться в пул.

| Факт | Где проверено |
|---|---|
| Пул (100 клиентов по умолчанию) вычерпывается, и прогон **зависает**, а не падает | прогон `:mongkn-core:build` с поломкой — упёрся в 10-минутный внешний лимит |
| `runTest(timeout = 90.seconds)` не срабатывает | там же |

**Причина.** Поток стоит внутри `mongoc_client_pool_pop` — это обычный блокирующий C-вызов,
а не точка приостановки корутины. Отменять там нечего: механизм отмены Kotlin работает только
на границах suspend-функций. Это риск 2 ресёрча, проявившийся не в прикладном коде, а в самой
тестовой обвязке.

**Следствие 1.** У дедлока пула нет сигнала лучше, чем «сборка не закончилась». Ни таймаут теста,
ни `withTimeout` его не превратят в красный тест. В CI это надо закрывать таймаутом job'ы,
а не таймаутом теста.

**Следствие 2 (повышает важность M-23).** Известное ограничение «долгий `find` держит клиента
всё время сбора потока» перестаёт быть теоретическим: превышение размера пула одновременно
живущими курсорами даёт не деградацию, а зависание намертво. Граница — 100 одновременных
курсоров по умолчанию; стресс-тест намеренно держит 32.

### 1.13 Ожидание клиента можно сделать отменяемым — семафором перед пулом

Прямое следствие §1.12: раз блокировку внутри `mongoc_client_pool_pop` отменить нечем, надо
сделать так, чтобы до неё не доходило.

| Факт | Где проверено |
|---|---|
| `mongoc_client_pool_max_size(pool, n)` задаёт размер пула; вызывается до первого `pop` | `mongoc/mongoc-client-pool.h:60`, вызов в [MongoClient](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/MongoClient.kt) |
| Если держать `kotlinx.coroutines.sync.Semaphore` с числом разрешений ровно в размер пула, `pop` не блокирует никогда: разрешение уже гарантирует свободного клиента | тест `waiting for a client is cancellable instead of blocking a thread` |
| Ожидание разрешения — обычная приостановка: `withTimeout` его снимает, поток при этом свободен | тот же тест |
| Курсоров может быть больше, чем клиентов, — лишние ждут и дожидаются | тест `more concurrent cursors than the pool allows still complete` |

**Как тест это доказывает.** `runTest` крутит виртуальное время, и `withTimeout` срабатывает,
только когда планировщику нечего выполнять — то есть когда корутина действительно припаркована.
Будь поток заблокирован внутри C, виртуальное время не сдвинулось бы и тест завис, ровно как
в §1.12. Быстрое прохождение здесь и есть доказательство.

**Чего это не чинит.** Курсор по-прежнему держит клиента всё время сбора потока: `mongoc_cursor_t`
принадлежит `mongoc_client_t`, вернуть клиента в пул с открытым курсором нельзя. Это ограничение
libmongoc, и убрать его невозможно — batching с возвратом клиента между порциями потребовал бы
уничтожать курсор, то есть терять снимок. Изменился **режим отказа**: перегрузка была зависанием
намертво, стала backpressure.

**Граница гарантии.** Инвариант «есть разрешение — есть клиент» держится ровно пока `useClient`
возвращает клиента в `finally`. Сломай этот `finally` — разрешения продолжат выдаваться,
а клиентов не останется, и §1.12 вернётся. Семафор защищает от честной перегрузки, а не от бага
в возврате.

### 1.14 Счётчик аллокаций libbson работает из Kotlin/Native

Гипотеза §1.11 («ложится ли vtable из указателей на функции на `staticCFunction`») проверена
реализацией.

| Факт | Где проверено |
|---|---|
| `bson_mem_vtable_t` содержит **пять** указателей: `malloc`, `calloc`, `realloc`, `free`, `aligned_alloc` — плюс `void *padding[3]` | `bson/memory.h:28` |
| `staticCFunction` заполняет все пять; счётчик держится в top-level `AtomicLong`, потому что захват контекста в `staticCFunction` недоступен | [BsonAllocations](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/bson/BsonAllocations.kt) |
| Round-trip `Document → bson_t → Document` возвращает **ровно** все блоки: 100 проходов, дельта 0 | тест `round trip returns every allocation` |
| Исключение посреди сборки документа тоже не оставляет блоков | тест `a failure midway through building does not leak the partial document` |

**`aligned_alloc` заполнять обязательно.** В libbson 2.x он есть в vtable, и NULL в этом поле —
не мягкая деградация, а падение на первом же выравненном выделении. Реализован через
`posix_memalign`: он доступен везде и освобождается обычным `free`, в отличие от C11
`aligned_alloc` с его краевыми случаями.

**Проверка на пустышку встроена в набор.** Тест `the counter itself notices a deliberate leak`
намеренно не зовёт `bson_destroy` и требует, чтобы счётчик это заметил. Без него остальные три
теста показывали бы ноль и на исправном коде, и на дырявом.

**Поправка, найденная при написании.** Первая версия теста на путь исключения ронялась
на `BsonObjectId(ByteArray(0))` — и была пустышкой: конструктор бросает при **сборке документа**,
до того как кодек начнёт работу, так что ни одного `bson_new` не случалось. Исключение пришлось
впрыскивать в сам обход — списком, который бросает на N-м элементе, и обязательно внутри
вложенного документа, чтобы задеть сразу оба места очистки.

### 1.15 Кодек молча терял строки с NUL — нашёл property-тест

Единственный настоящий баг, найденный тестами за всё время работы, и найден он ровно тем видом
проверки, который для этого и заводился (M-32).

| Факт | Где проверено |
|---|---|
| BSON-строки длиннопрефиксные, NUL внутри значения формат допускает | подтверждено эталоном: официальный драйвер хранит `"a\u0000b"` как есть — тест `mongkn writes a document for the official driver to verify`, поле `embeddedNul` |
| `bson_append_utf8(…, value, -1)` считает длину через `strlen` и обрезает на первом NUL | тест `strings containing NUL survive` падал до фикса |
| Симметрично при чтении: `bson_iter_utf8(iter, null)` + `toKString()` теряют хвост | тот же тест |
| cinterop по умолчанию превращает `const char*` в Kotlin String, и сырой указатель туда не передать | ошибка компиляции `actual type is 'CPointer<ByteVarOf<Byte>>', but 'String?' was expected` |

**Как починено.** В `.def` добавлен `noStringConversion = bson_append_utf8`, длина строки
передаётся явно (`encodeToByteArray().size` + `usePinned`), а на чтении берётся из
`bson_iter_utf8(iter, length.ptr)`. Ключи — отдельная история: `e_name` в BSON это C-строка,
и NUL в ней **не представим**, поэтому кодек его теперь явно отвергает, а не обрезает молча.

**Чему это учит про сами тесты.** Ни один из существовавших тестов этот баг не видел и не мог:
[BsonRoundTripTest](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/bson/BsonRoundTripTest.kt)
гоняет значения, подобранные руками, — то есть ровно те, о которых автор подумал; дифференциальный
тест сравнивал документ, в котором NUL не было; счётчик аллокаций проверяет память, а не
содержимое. Баг жил в зазоре между «о чём подумал автор» и «что допускает формат», и закрывается
такой зазор только генерацией входов, а не аккуратностью.

NUL добавлен и в алфавит генератора, и в эталонный документ дифференциального теста — чтобы
починенное оставалось починенным.

### 1.16 Spec-тесты MongoDB под CC BY-NC-SA — вендорить нельзя

| Факт | Где проверено |
|---|---|
| «All the specs in this repository are available under the Creative Commons Attribution-NonCommercial-ShareAlike 3.0 United States License» | [README mongodb/specifications](https://github.com/mongodb/specifications) |
| В CRUD-наборе unified-формата 175 файлов; под наши операции подходят 6 «простых» | листинг `source/crud/tests/unified` через GitHub API |
| Файлы читаются libbson напрямую (`bson_json_reader_new_from_file`) — парсер тащить не надо | [SpecTestRunner](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/spec/SpecTestRunner.kt) |

**Следствие.** NonCommercial + ShareAlike несовместимы с распространением библиотеки под
пермиссивной лицензией, а mongkn рассчитывает на публикацию (M-18). Поэтому файлы **не лежат
в репозитории**: задача `fetchSpecTests` кладёт их в `build/spec-tests`, который в `.gitignore`
и стирается `clean`. Использовать их для проверки соответствия — можно; распространять копию
в своём дистрибутиве — уже вопрос лицензии, которого мы не хотим.

Цена: первый прогон требует сети. Дальше файлы переиспользуются из `build/`.

### 1.17 Что дал прогон spec-тестов

Первый же прогон нашёл поведенческий пробел, которого не видел ни один наш тест.

| Факт | Где проверено |
|---|---|
| `insertMany` возвращал **пустой** `insertedIds` | тест `insertMany.json :: InsertMany with non-existing documents` |
| `reply` от `mongoc_collection_insert_many` идентификаторов не содержит — в отличие от `insert_one`, где `insertedId` есть (§1.3) | там же |
| Официальные драйверы генерируют `_id` **на клиенте**, до отправки | поведение эталона в том же сценарии |

**Как починено.** `insertMany` теперь проставляет `_id` первым полем каждому документу, у которого
его нет, и возвращает эти идентификаторы. Это не косметика: без клиентской генерации вернуть
`insertedIds` попросту неоткуда.

**Вторая находка — про сам раннер.** Первая версия проверяла только *имя* операции и молча
игнорировала аргументы. Сценарий `InsertMany continue-on-error behavior with unordered` при этом
падал — но мог бы и «пройти», если бы результат случайно совпал, притом что `ordered: false`
мы не реализуем вовсе. Теперь у каждой операции есть белый список аргументов, и незнакомый
аргумент **пропускает тест**, а не игнорируется.

**Честный итог покрытия:** 5 сценариев выполнено, 17 пропущено. Под spec-покрытием оказались
`deleteOne`, `insertOne`, `find`; **без покрытия — `countDocuments`, `insertMany`, `updateOne`**:
их сценарии целиком отсеиваются по `expectEvents`, `runOnRequirements` и неподдержанным
аргументам. Отчёт печатает это явно — «5 сценариев прошло» без такой строки звучало бы как
покрытие, которым не является.

### 1.18 Linux: чем обернулась проверка «на другой машине»

Все факты §1.1–§1.17 получены на одной машине — macOS, Apple Silicon, mongo-c-driver 2.1.1.
Проверка в контейнере нашла четыре вещи, каждая из которых делала соответствующее утверждение
неверным.

#### Kotlin/Native не компилирует на хосте linux-aarch64 — и Gradle это скрывает

| Факт | Где проверено |
|---|---|
| `The Kotlin/Native compiler does not support your current host platform: linux-aarch64` | прогон в контейнере `eclipse-temurin:21-jdk-noble` на Apple Silicon |
| При этом все задачи компиляции получают `SKIPPED`, а сборка заканчивается `BUILD SUCCESSFUL` за 8 секунд | там же |
| Под `--platform linux/amd64` (эмуляция) всё компилируется нормально | прогон `compileKotlinLinuxX64`, 4 минуты |

**Молчаливо зелёная сборка — худший из возможных исходов**: в CI такое выглядело бы как успех,
не собрав ни строчки. Теперь конфигурация на linux-aarch64 падает явно, с указанием на
`--platform linux/amd64` или x86_64-раннер.

#### Ubuntu даёт libmongoc 1.x — ветка Р1 наконец исполнилась

| Факт | Где проверено |
|---|---|
| `libmongoc-dev` в Ubuntu 24.04 — версия **1.26.0**, то есть ветка 1.x | `dpkg -s libmongoc-dev` в контейнере |
| Заголовки: `/usr/include/libmongoc-1.0/mongoc/mongoc.h`, библиотеки: `/usr/lib/<triplet>/libmongoc-1.0.so` | `find` в контейнере |
| `bson_mem_vtable_t` в libbson 1.26 **уже содержит** `aligned_alloc` — опасение, что счётчик аллокаций (§1.14) не соберётся под 1.x, не подтвердилось | `bson/bson-memory.h:34` в контейнере |
| cinterop и компиляция под libmongoc 1.26 проходят | задача `cinteropMongocLinuxX64` |

**Следствие.** Матрица macOS + Linux — не для галочки: на одном хосте проверяется 2.x, на другом
1.x, и решение Р1 перестаёт быть обещанием.

#### Резолвер библиотек был сломан для Linux в трёх местах

Все три нашлись сразу, как только код впервые оказался на Linux:

1. **Multiarch.** Debian и Ubuntu кладут библиотеки в `/usr/lib/aarch64-linux-gnu`, а мы искали
   только в `<prefix>/lib`. Теперь просматривается и уровень ниже.
2. **Версионный суффикс.** Фильтр «отбросить имена с точкой после основы» задумывался против
   `libmongoc2.2.dylib`, а заодно отбрасывал `mongoc-1.0` — то есть **всю** ветку 1.x. Фильтр
   убран: выбор самого короткого имени и так делает то, что нужно.
3. **`libmongocrypt`.** Он тоже начинается с `libmongoc`, и его имя той же длины, что
   `mongoc-1.0`. При выборе «по кратчайшему» линковка могла уехать в libmongocrypt. Теперь после
   основы допускаются только цифры, точки и дефисы — то есть версия, а не другое слово.

Ни одна из трёх не всплыла бы без реального Linux: на macOS резолвер работал правильно
по совпадению.

#### Линковка против системной libbson требует `--allow-shlib-undefined`

| Факт | Где проверено |
|---|---|
| `ld.lld: error: undefined reference: strlcpy@GLIBC_2.38 >>> referenced by /usr/lib/x86_64-linux-gnu/libbson-1.0.so (disallowed by --no-allow-shlib-undefined)`; то же для `pthread_once@GLIBC_2.34` | прогон `linkDebugTestLinuxX64` в контейнере |
| С `-Wl,--allow-shlib-undefined` линковка проходит, тесты запускаются | следующий прогон |

**Почему так.** Kotlin/Native линкует своим sysroot с намеренно старой glibc — ради переносимости
бинарника. Системная libbson собрана против glibc дистрибутива, которая новее, и тянет символы,
которых в этом sysroot нет. `ld.lld` по умолчанию считает неразрешённые ссылки **внутри чужой
`.so`** ошибкой.

Разрешать их на этапе линковки незачем: динамический загрузчик найдёт их в настоящей glibc
системы при запуске. Флаг снимает проверку только для разделяемых библиотек — неразрешённые
символы **нашего** кода по-прежнему остаются ошибкой.

#### `insert_one` не возвращает `insertedId` на ветке 1.x

Самая содержательная находка: под неё пришлось менять код, а не сборку.

| Факт | Где проверено |
|---|---|
| На libmongoc 1.26 `reply` от `mongoc_collection_insert_one` — это `{insertedCount: 1}`, **без** `insertedId` | падение 5 интеграционных тестов на Linux: `в ответе сервера нет поля "insertedId"` |
| На 2.1.1 `insertedId` в ответе есть (§1.3) | прогон на macOS |

**Следствие.** Решение Р3 (`insertOne` возвращает `insertedId`) стояло на поведении, которого
в 1.x нет, — то есть утверждение §1.3 «сервер и так его отдаёт» верно ровно для одной ветки
драйвера. Заявление Р1 о поддержке обеих веток при этом было ложным, и обнаружилось это только
на реальном Linux.

**Как починено — тем же приёмом, что подсказали spec-тесты для `insertMany` (§1.17):** `_id`
проставляется на клиенте, и `InsertOneResult` берёт его из документа, который мы сами отправили.
Это не обход, а то, как устроены официальные драйверы; заодно исчезла зависимость от ветки.

#### Alpine в качестве базы не годится

Kotlin/Native собран под glibc: на musl не работает ни компилятор, ни произведённые бинарники.
Issue открыты годами — [KT-38891](https://youtrack.jetbrains.com/issue/KT-38891/Support-the-KotlinNative-compiler-on-Alpine-Linux)
и [KT-38876](https://youtrack.jetbrains.com/issue/KT-38876/Support-running-KotlinNative-produced-binaries-on-Alpine-Linux).
Обходится доустановкой compat-библиотек, но это борьба с платформой. База образа —
Debian/Ubuntu ([ci/Dockerfile](../../ci/Dockerfile)).

### 1.19 ABI-валидация: дамп привязан к хосту, а не к API

| Факт | Где проверено |
|---|---|
| Встроенная в KGP валидация включается **самим вызовом** `abiValidation { }`; свойства `enabled` больше нет — «Property was removed, to enable ABI validation call function abiValidation()» | ошибка компиляции build-файла на KGP 2.4.10 |
| Задачи называются `checkKotlinAbi` и `updateKotlinAbi`; `checkKotlinAbi` цепляется к `check` | `javap` по `AbiValidationTaskSet` из jar плагина + прогон |
| klib-дамп поддерживается: 277 строк, заголовок `// Klib ABI Dump`, `Signature version: 2` | [mongkn-core/api/mongkn-core.klib.api](../../mongkn-core/api/mongkn-core.klib.api) |
| Дамп, снятый на macOS, **не проходит** проверку на Linux | прогон `checkKotlinAbi` в контейнере |
| Различие ровно одно: `-// Targets: [macosArm64]` против `+// Targets: [linuxX64]`. Все 276 строк объявлений совпадают | тот же прогон |

**Следствие.** Публичный API действительно одинаков на обоих таргетах — расходится только строка
заголовка. Значит проверять его достаточно на одном хосте: любое изменение API поймается там же.
`checkKotlinAbi` ограничен эталонным хостом (macosArm64) через `onlyIf` с явной причиной, иначе
Linux-сборка падала бы на строке заголовка.

**Про `keepLocallyUnsupportedTargets = false`.** Ожидалось (§1.11), что без него плагин достроит
ABI недостающих таргетов. На деле достраивать нечего: мы объявляем **один** таргет на хост
(решение Р6), и «локально неподдержанных» в проекте просто нет. Флаг оставлен как страховка
на случай, если таргетов станет больше, но сегодня он ни на что не влияет — предсказание §1.11
о том, что M-31 блокируется M-17, оказалось верным по выводу и неверным по механизму.

---

## 2. Решения

### Р1. Целевая версия драйвера — 2.x; 1.x поддерживается только разрешением имён

Драфт: 1.x с фиксированными `-lmongoc-1.0` / `-lbson-1.0`.
Решение: целимся в 2.x, а различие 1.x/2.x прячем в `findLibName()` / `findIncludeDir()`.

Почему:

- на машине разработки стоит 2.1.1, а в brew уже 2.3.3 — 1.x пришлось бы ставить специально;
- различие сводится ровно к двум вещам: имя библиотеки и расположение заголовков (§1.1), обе
  вычислимы во время конфигурации;
- цена: реальной проверки на 1.x нет — резолвер имён под неё написан, но не прогонялся. Это
  явная гипотеза, а не факт.

**Поправка, найденная при реализации M-17.** Гипотеза оказалась неверной трижды: резолвер
не находил библиотеки в multiarch-каталоге, отбрасывал имя `mongoc-1.0` собственным фильтром
и мог выбрать `libmongocrypt`; сверх того `insert_one` на 1.x не возвращает `insertedId`,
на чём стояло решение Р3. Всё перечисленное починено, и теперь 1.x **проверяется в CI**
на Ubuntu, а 2.x — на macOS (§1.18). Утверждение из гипотезы стало фактом только после этого.

### Р2. Ресурсная модель — пул клиентов, а не один общий клиент

Драфт: один `mongoc_client_t` + `withContext(Dispatchers.Default)` вокруг каждого вызова.
Решение: `mongoc_client_pool_t`; на время операции клиент берётся `mongoc_client_pool_pop()` и
возвращается `mongoc_client_pool_push()` в `finally`; всё это на `Dispatchers.IO`.

Почему:

- один клиент из нескольких потоков — прямое нарушение контракта libmongoc (§1.4), а не
  «неоптимально»;
- альтернатива «один клиент, закреплённый за однопоточным диспетчером» безопасна и заметно проще,
  но сериализует **все** операции приложения в одну очередь — при таком раскладе вся затея с
  корутинами теряет смысл;
- пул — то, что сам вендор называет «the basis for multi-threading in the MongoDB C driver»,
  и он же сам держит фоновый мониторинг топологии;
- цена: `mongoc_client_pool_pop()` **блокирует** поток, когда пул исчерпан (по умолчанию 100
  клиентов). На `Dispatchers.IO` это переживаемо, но это блокировка внутри suspend-функции —
  см. риск 2.

### Р3. `insertOne` возвращает `InsertOneResult`, а не `Boolean`

Драфт: `suspend fun insertOne(document: Document): Boolean`.
Решение: возвращаем вставленный `_id`; провал — исключение, а не `false`.

Почему:

- сервер и так кладёт `insertedId` в `reply` (§1.3) — терять его незачем;
- `Boolean` + исключение при ошибке — избыточная пара: `false` в такой схеме недостижим;
- так же устроен официальный корутинный драйвер, а форму API мы с него и снимаем.

**Поправка (§1.18).** Изначально `insertedId` читался из ответа драйвера — и это работало только
на 2.x: libmongoc 1.26 кладёт в `reply` лишь `insertedCount`. Теперь `_id` проставляется
на клиенте и возвращается оттуда, как это делают официальные драйверы. Решение Р3 осталось
прежним, изменился источник значения.

### Р4. `Document` — упорядоченный список пар с типизированным `BsonValue`, а не `Map<String, Any>`

Драфт: «typealias или обёртка над `Map<String, Any>`».
Решение: sealed-иерархия значений (`BsonString`, `BsonInt32`, `BsonInt64`, `BsonDouble`,
`BsonBoolean`, `BsonNull`, `BsonObjectId`, `BsonDateTime`, `BsonDocument`, `BsonArray`) плюс
DSL-билдер для человеческой записи.

Почему:

- критерий приёмки самой фазы 2 в драфте — **round-trip с проверкой равенства**: Kotlin →
  `bson_t` → Kotlin. С `Map<String, Any>` он недостижим: `Any` не различает int32 и int64
  (`bson_append_int32` против `bson_append_int64`), а `ObjectId` и `DateTime` в `Any` вообще не
  выражаются — обратно они приедут не тем, чем уехали;
- BSON хранит порядок ключей, и сервер этот порядок в ряде мест использует (например, ключи
  команд); `Map` его формально не гарантирует;
- цена: многословнее в записи. Гасится DSL-билдером и операторами — задача M-05.

### Р5. Генерация живёт в JVM-модуле и отдаёт исходники в `nativeMain` *(отменено — см. Р9)*

> **Отменено решением Р9.** Раздел сохранён: рассуждение о том, почему обращаться к официальному
> драйверу можно только с JVM, осталось верным — и теперь работает уже на дифференциальные тесты.
> Отменена только генерация исходников.

Драфт: KSP-процессор, подключённый к нативному модулю, читает JVM-драйвер из `compileOnly`.
Решение: отдельный JVM-модуль, где официальный драйвер лежит на classpath по-настоящему;
результат KotlinPoet складывается в каталог, который нативный модуль подключает как `srcDir`
с зависимостью по задаче.

Почему:

- в нативной компиляции JVM-jar на classpath отсутствует, `getClassDeclarationByName` вернёт
  `null` (§1.5) — драфтовая схема нерабочая по устройству, а не по настройке;
- цена: связь между модулями становится «задача → каталог», а не «плагин»; порядок сборки надо
  прописывать руками;
- запасной вариант, если KSP не сойдётся с Kotlin 2.4.10 (риск 1): те же сигнатуры достаются
  рефлексией по jar официального драйвера в обычной Gradle-задаче. KSP здесь — удобство, а не
  необходимость: он нужен только чтобы прочитать чужие сигнатуры, а не чтобы обработать наш код.

### Р6. В сборке — только хостовый таргет

Решение: `macosArm64` / `macosX64` / `linuxX64` выбирается по хосту; матрицы таргетов нет.

Почему:

- `cinterop` требует заголовков **целевой** платформы, а на macOS заголовков Linux-сборки
  mongo-c-driver нет — объявленный `linuxX64` уронит сборку на этапе конфигурации;
- цена: кросс-платформенность проверяется только в CI на соответствующих раннерах — M-17.

### Р7. DSL — отдельный слой **над** зеркалом, а не вместо него

Вопрос: раз копирование формы официального драйвера — технический приём, не стоит ли вместо
этого сразу спроектировать своё, более верхнеуровневое Kotlin-API с DSL?
Решение: нет. Зеркало остаётся спиной API, DSL кладётся поверх и в MVP не входит — кроме
минимального билдера документов, который в MVP входит по другой причине (см. ниже).

Почему:

- к этой же архитектуре пришёл сам вендор: `org.mongodb:mongodb-driver-kotlin-extensions` 5.9.1
  (обновлён 2026-07-23, проверено в Maven Central) — **отдельный артефакт** с типобезопасным
  infix-DSL поверх основного драйвера, а не его замена;
- [KMongo](https://litote.org/kmongo/), чьим главным продуктом был как раз DSL
  (`Person::name eq "x"`), официально deprecated с 2023-06-28 в пользу официального драйвера,
  а его нотацию драйвер втянул в extensions. То есть DSL как слой прижился, DSL как
  альтернативная объектная модель — нет;
- зеркало и DSL решают разные задачи и не конкурируют: зеркало отвечает за форму базового API,
  DSL — за **эргономику** для тех немногих операций, которыми пользуются каждый день;
- цена: две точки входа в API вместо одной. Принимаем.

**Поправка, найденная при измерении (§1.10).** Здесь изначально стояло «зеркало даёт покрытие
дёшево (сотни методов, печатаются генератором)» — **это неверно**. Замер на шести операциях дал
одну сгенерированную строку на шесть рукописных: реализацию на libmongoc за нас никто не пишет,
и дешёвого покрытия не возникает. Генератор оправдывается не экономией, а точностью формы
и защитой от расхождения с официальным API. На вывод самого Р7 (DSL — отдельный слой над
зеркалом) это не влияет; на оправданность вехи M5 — влияет, и честная оценка там же, в §1.10.

**Важное уточнение про билдер документов.** Минимальный билдер (M-05) — это не «первый кусочек
DSL», а прямая цена решения Р4: отказавшись от `Map<String, Any>`, мы получили фильтр вида
`Document("age" to BsonDocument("\$gt" to BsonInt32(18)))`. Без билдера слой BSON непригоден
к употреблению, поэтому он в MVP. Полноценный язык запросов — нет: его форму диктует набор
реально нужных операторов, а до работающего `find` этот набор неизвестен.

**Следствие для генератора (M5).** Сгенерированный API обязан расширяться снаружи, не требуя
правок в сгенерированном коде: параметры-фильтры принимают `Document`, точки расширения не
закрыты `internal`/`final`. Иначе extensions придётся встраивать в генератор, и он из «зеркала
чужих сигнатур» превращается в компилятор нашего DSL — а это совсем другая по стоимости вещь.
Требование дешевле зафиксировать сейчас, чем обнаружить в M-16.

**Что осталось за скоупом.** Property-reference нотация (`Person::age gt 18`) требует
типизированных коллекций `MongoCollection<T>`, то есть маппинга data-классов, то есть
открытого вопроса 1. Это кусок больше всего текущего MVP.

### Р8. `find` возвращает `Flow<Document>`, а `FindFlow` — потом *(выполнено)*

> **Вторая половина сделана в M-34.** `find` возвращает `FindFlow`, у него `limit`, `skip`,
> `sort`, `projection`, `batchSize`. Предсказание оправдалось буквально: `FindFlow` реализует
> `Flow`, и **ни один существовавший вызов править не пришлось** — `find().toList()`,
> `find().first()`, `find().count()` продолжили работать как есть. Реализация — делегированием,
> как у официального драйвера: наследоваться от `Flow` напрямую нельзя без opt-in во внутренний
> API корутин.

Открытый вопрос 2: копировать ли `FindFlow` с чейнингом или отдавать голый `Flow`.
Решение: голый `Flow<Document>` сейчас, `FindFlow` — когда понадобятся `limit`/`sort`/`projection`.

Почему:

- `FindFlow<T>` **реализует** `Flow<T>` (§1.9), поэтому это не два несовместимых варианта,
  а два шага одной лестницы: смена возвращаемого типа на подтип не ломает вызывающих;
- 23 метода чейнинга — это 23 набора опций, которые надо было бы прокинуть в `mongoc_collection_find_with_opts`
  через `opts`-документ; без реальной потребности их форма угадывается вслепую;
- цена: до тех пор генератор зеркалит форму не один в один, и это видно в
  [api-collection](../api/api-collection.md), раздел 3.

### Р9. От генерации отказываемся; официальный драйвер остаётся эталоном для тестов

Драфт (фаза 4) и решение Р5: KSP + KotlinPoet печатают поверхность `MongoCollection` по
сигнатурам официального драйвера.
Решение: **генерацию убираем**, `MongoCollection` пишется руками. Официальный JVM-драйвер
из проекта **не уходит** — он меняет роль: был источником сигнатур, становится эталонной
реализацией для дифференциальных тестов.

Почему:

- замер (§1.10) показал одну сгенерированную строку на шесть рукописных. Обещанного дешёвого
  покрытия не возникает: C-драйвер неоднороден, и реализацию всё равно пишут руками;
- **генератор отвечал не на тот вопрос.** «Верно ли реализован Mongo API» распадается на *форму*
  (те же имена и сигнатуры) и *поведение* (делает ли `updateOne` то, что MongoDB под этим
  понимает). Генератор гарантирует только форму и с одинаковым успехом напечатал бы безупречную
  сигнатуру над реализацией, которая удаляет коллекцию;
- цена при этом вполне материальна: два JVM-модуля **на критическом пути сборки** — сегодня
  `:mongkn-core` без них не собирается вообще, — плюс 241 строка процессора ради 68 строк фасада;
- то, что генератор всё-таки давал (§1.10 — механическое разрешение неоднозначности перегрузок,
  запрет молча разойтись с официальным API), покрывается дифференциальными и spec-тестами,
  причём они же покрывают и поведение.

Цена решения — назову честно:

- знание о переводе типов (`Bson` → `Document`, отбрасывание `*Options` и `ClientSession`,
  `FindFlow` → `Flow`) перестаёт быть исполняемым и переезжает в
  [api-collection](../api/api-collection.md), раздел 3, — то есть в текст, который может протухнуть;
- ловушку вроде двух перегрузок `updateOne` (документом против агрегационного конвейера) теперь
  ловит не компилятор, а тест поведения. Слабее по немедленности, сильнее по существу: тест
  проверяет, что мы обновляем **документом**, а не что мы так назвали параметр.

Рассматривалась и отвергнута промежуточная форма — «генератор превращается в сверяльщик» (тот же
маппинг, но вместо печати кода — проверка соответствия). Отвергнута потому, что сохраняет всю
сложность процессора ради проверки формы, которая вторична по отношению к поведению.

**Выполнено (M-33).** Генератор удалён: модули `:mongkn-codegen` и `:mongkn-api-spec` снесены,
KSP и KotlinPoet вычищены из сборки, `MongoCollection` живёт рукописным файлом рядом
с `CollectionOps`. Официальный драйвер остался в `:mongkn-difftest`.

Замер чистой сборки на macosArm64: **34 с / 24 задачи до, 30 с / 18 задач после**. Прирост
скромный и это ожидаемо — работа KSP была копеечной на фоне компиляции и линковки Kotlin/Native.
Настоящая выгода не в секундах, а в том, что `:mongkn-core` снова собирается сам по себе,
без JVM-модулей на критическом пути.

Знание, которое было исполняемым в процессоре, переехало в KDoc
[MongoCollection](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/MongoCollection.kt)
и в [api-collection](../api/api-collection.md), раздел 3: правила снятия формы, отбрасываемые
параметры и — отдельным абзацем — ловушка с двумя одноимёнными перегрузками `updateOne`.
Это ровно та цена, которая была названа выше: знание перестало проверяться компилятором.

### Р10. Маппинг классов — свой древесный формат поверх `BsonValue`

Открытый вопрос 1 («нужен ли мост в `kotlinx.serialization`») закрыт: нужен, и написан.

Решение: собственные `Encoder`/`Decoder`, промежуточное представление — наш же [BsonValue],
а не поток байт. Формат «древесный», как `Json.encodeToJsonElement`.

Почему так, а не потоковый формат: байты всё равно собирает libbson, и дублировать её работу
незачем. Побочная выгода — типизированная и нетипизированная коллекции гарантированно видят
один документ, потому что обе проходят через одно и то же `BsonValue`; это проверяется тестом
`typed and untyped views agree on the stored document`.

Что **не** поддержано и почему названо явно, а не забыто:

- **полиморфизм** (`sealed`-иерархии): нужен договор об имени поля-дискриминатора, и его форма
  обязана совпадать с официальным драйвером — это отдельная сверка, а не строчка кода;
- **контекстная сериализация**: нет сценария.

Обе ветки бросают `SerializationException` с внятным текстом, а не пишут мусор.

Цена, которую стоит знать: `BsonObjectId` пришлось пометить `@Serializable(with = …)` —
без этого класс с полем `BsonObjectId` не компилируется, сколько бы наш кодировщик ни умел
в рантайме. При записи сериализатор не работает (кодировщик пропускает `BsonValue` мимо себя,
чтобы `_id` остался ObjectId, а не стал строкой), при чтении — работает. Асимметрия описана
в коде обоих концов.

### Р11. Числа читаются мягко, но не сужаются молча

`Long`-поле принимает и int32, и int64: сервер и другие драйверы вольны положить любое
целочисленное представление, и требовать точного совпадения значило бы ломаться на чужих данных.

Обратное запрещено: int64, не помещающийся в `Int`, даёт `SerializationException`, а не
обрезанное значение. Тихая порча данных хуже отказа.

### Р12. Координаты — `ru.workinprogress.mongkn`, пакеты переименованы под них

Было `io.github.mongkn` и в пакетах, и в `group`. Решение: `ru.workinprogress.mongkn` —
как у соседних проектов, публикуемых в тот же приватный Reposilite, — и пакеты переименованы
следом, чтобы groupId и namespace не разъезжались.

Что всплыло при переименовании:

- `group` в корневом build-файле подпроектами **не наследуется**: по умолчанию подпроект берёт
  группой имя корневого проекта, и артефакты уехали бы в `mongkn`. Поймано на
  `publishToMavenLocal` до отправки на сервер; лечится `allprojects { group = … }`;
- пакет cinterop остался `mongkn.cinterop`. Он генерируется и используется только внутри
  модуля, наружу не торчит, а переименование стоило бы правок во всех импортах ради нуля.

Лицензия не выбрана — репозиторий приватный, внешних потребителей нет. Секции `licenses`
в POM поэтому нет: пустая или выдуманная хуже отсутствующей.

### Р13. Полное покрытие типов BSON, кроме двух устаревших; decimal128 — строкой

Читаются все типы, кроме `dbpointer` (удалён из спецификации) и `code with scope` (устаревший).
На них остаётся `UnsupportedBsonTypeException`: полезной нагрузки у обоих столько же, сколько
реальных данных с ними.

Два решения внутри:

**Подтип binary — часть значения, а не деталь кодирования.** `0x04` это UUID, `0x06` —
зашифрованное поле CSFLE, `0x00` — просто байты. Потерять подтип значит превратить UUID
в мешок байт, поэтому он участвует в равенстве.

**`decimal128` хранится канонической строкой, а не числом.** Своей 128-битной десятичной
арифметики у Kotlin/Native нет, а libbson переводит строку туда и обратно без потерь.

Строка **приводится к каноническому виду при создании** — иначе `BsonDecimal128("0.0…01")`
и прочитанное обратно `BsonDecimal128("1E-30")` были бы разными значениями при одном числе.
Поймано тестом round-trip: libbson нормализует запись, и без приведения равенство ломалось бы
именно после обращения к базе, то есть в самый неудобный момент.

**Внешнюю библиотеку больших чисел не берём.** `com.ionspin.kotlin:bignum` 0.3.10 существует
и собирается под нативные таргеты, но:

- это была бы `api`-зависимость, которую унаследует каждый потребитель — для драйвера
  обязательство тяжёлое, а версия ещё до 1.0;
- преобразование decimal128 ↔ BigDecimal **не взаимно однозначно**: у decimal128 есть `NaN`,
  `±Infinity`, отрицательный ноль и значащие хвостовые нули (`1.0` и `1.00` — разные
  представления). Любой такой мост — новый источник тихой потери данных, а их за проект уже
  найдено достаточно;
- арифметика драйверу не нужна: его дело — хранение и передача.

Если понадобится, мост встанет в `:mongkn-extensions` — модуль ровно для такого: подключается
по желанию, ядро остаётся без зависимостей (решение Р7). Задача M-38.

### Р14. Ссылки на свойства работают только в области, где известен дескриптор

Проблема M-36: имя поля бралось из `KProperty1.name`, то есть из имени свойства **Kotlin**,
а в документе лежит **serial-имя**. Обычно совпадают, но `@SerialName("born_year")` их разводит,
и фильтр молча не находил ничего — для MongoDB несуществующее поле это просто «не совпало».

Восстановить serial-имя по ссылке на свойство **нельзя**: `@SerialName` не доживает
до дескриптора как аннотация (плагин её потребляет), а рефлексии, которая связала бы свойство
с элементом дескриптора, на Kotlin/Native нет.

Решение: раз разрешить нельзя — **обнаружить**. Перегрузки по `KProperty1` убраны с верхнего
уровня и живут в `FilterScope` / `UpdateScope`, где дескриптор есть:

```kotlin
collection.find { Person::born gt 1900 }        // имя проверяется
"born_year" gt 1900                             // строкой — как есть, без проверки
```

Если имени свойства среди элементов дескриптора нет, вызов падает и **перечисляет реальные
имена полей**. Тихая пропажа данных превратилась в понятную ошибку, а обходной путь остался:
строковые перегрузки никуда не делись.

Побочная выгода: ловится и опечатка в рефакторинге — переименовали свойство, а фильтр забыли.

Цена: убраны публичные перегрузки, то есть `Person::born gt 1900` на верхнем уровне больше
не компилируется. Для версии `0.1.0-SNAPSHOT` и модуля возрастом в одну веху это дешевле, чем
оставлять API, который умеет молча возвращать пусто.

---

## 3. Риски и открытые вопросы

**Риск 7 — снят требованием, а не решением.** Целевая платформа у mongkn одна — **linuxX64**;
macOS-таргет не публикуется и потребителей на нём нет. Значит «кто публиковал последним, тот
и определяет короткую координату» перестало быть проблемой: публикует всегда Linux.

**Что при этом остаётся верным и важным.** macOS **не уходит** из CI и из локальной разработки:
Homebrew даёт mongo-c-driver **2.x**, а Ubuntu — **1.x**, и это единственное место, где ветка 2.x
вообще проверяется (§1.18). Убрать macOS-джобу как «ненужный таргет» значит перестать проверять
половину решения Р1.

Ниже — исходная формулировка риска, на случай если целевые платформы поменяются.

~~Корневой модуль публикации объявляет только тот таргет, на котором собран.~~
Проверено по `mongkn-core-0.1.0-SNAPSHOT.module`: собранный на macOS он перечисляет
`macosArm64ApiElements-published`, и никаких linux-вариантов. Это прямое следствие Р6 —
таргет только хостовый, потому что cinterop нужны заголовки платформы.

Практическое последствие: **кто публиковал последним, тот и определяет короткую координату.**
Сейчас опубликовано с Linux, поэтому `ru.workinprogress.mongkn:mongkn-core` резолвится только
для `linuxX64`; потребителю на macOS придётся указывать `mongkn-core-macosarm64` напрямую —
таргетный модуль публикуется отдельно и со своими метаданными.

Смягчения нет: собрать оба таргета с одного хоста нельзя, пока cinterop требует заголовков
целевой платформы. Варианты — либо держать заголовки обеих платформ в образе сборки, либо
склеивать метаданные из двух прогонов сторонним инструментом. Задача M-37.



**Риск 1 — снят.** ~~KSP 2.3.11 не проверен с Kotlin 2.4.10.~~ Проверено прогоном (§1.9): связка
работает, отступление на рефлексию не понадобилось. Ниже — исходная формулировка.

~~KSP 2.3.11 не проверен с Kotlin 2.4.10.~~ Схема версий KSP сменилась на 2.3.0, явной
таблицы совместимости в релизах нет; косвенный признак — фикс в 2.3.10 под имена модулей Kotlin
2.4.0 (§1.5). Смягчение: KSP не лежит на критическом пути M0–M4 и намеренно **не подключён**
к сборке — в [libs.versions.toml](../../gradle/libs.versions.toml) он объявлен, но не используется.
Если не сойдётся — откат на рефлексию по jar (Р5), стоимость перехода низкая, потому что вход
у обоих вариантов один: сигнатуры официального драйвера.

**Риск 2. Отмена корутины не отменяет сетевой вызов.** `libmongoc` синхронный, и `cancel()`
Flow-коллекции не прервёт уже начатый `mongoc_cursor_next` — поток `Dispatchers.IO` останется
занятым до таймаута. То же самое у блокирующего `mongoc_client_pool_pop()` при исчерпанном пуле.
Смягчение: обязательные `serverSelectionTimeoutMS` и `socketTimeoutMS` в URI как верхняя граница
ожидания (в прогоне §1.3 использовался `serverSelectionTimeoutMS=3000`), и `try_pop` с явным
таймаутом вместо голого `pop`. Открыто: какие дефолты выставлять, если пользователь их не задал.

**Риск 3 — снят для кодека.** ~~Утечки на пути исключения между `bson_new()` и `bson_destroy()`.~~
Закрыт задачей M-06: аллокатор libbson подменяется на считающий, и «сколько выделено — столько
освобождено» стало проверяемым утверждением (§1.14). Гипотеза про `staticCFunction`
подтвердилась.

**Что осталось непокрытым.** Сетевые операции: `mongoc_client_pool_t` заводит фоновые потоки
мониторинга топологии, которые аллоцируют через ту же libbson, поэтому точный баланс там дал бы
мигающий тест, а не проверку. То есть `bson_destroy(reply)` в шести операциях по-прежнему
держится на чтении кода, а не на утверждении.

**Риск 4 — снят: его не было.** ~~`brew upgrade mongo-c-driver` меняет путь к заголовкам,
и задача `cinteropMongoc…` этого не заметит.~~ Проверено при разборе M-20: Kotlin Gradle Plugin
ведёт собственную проверку актуальности cinterop — файл
`build/classes/kotlin/<target>/main/cinterop/mongoc/cinterop-headers-hash.json` хранит карту
**абсолютный путь → хеш содержимого**:

```json
{"/opt/homebrew/include/mongoc-2.1.1/mongoc/mongoc.h": "…", "/opt/homebrew/include/bson-2.1.1/bson/bson.h": "…"}
```

Ключ — версионированный путь, значение — хеш. `brew upgrade` меняет оба, поэтому задача
пересобирается сама; в логе `--info` это видно строкой «CInterop task uses custom Up-To-Date
check for content of headers instead of Gradle mechanisms».

Гипотеза была сформулирована как открытый вопрос, а не как факт, — и хорошо: попытка «починить»
добавила бы дублирующий `inputs.property` с комментарием, объясняющим несуществующую проблему.

**Риск 5 — снят.** ~~Ресурсная модель (Р2) не покрыта ни одним тестом.~~ Закрыт задачей M-29:
`MongoConcurrencyTest` гоняет 200 одновременных операций на одном `MongoClient` при 16 потоках —
вставки, смешанную нагрузку, поток падающих операций, 32 одновременных курсора и одновременное
создание клиентов. Ни потерянных записей, ни порчи памяти.

**Но проверка вскрыла кое-что неприятное про сам способ проверки** (§1.12): если сломать возврат
клиента в пул, тест не краснеет, а **вешает прогон**. `runTest(timeout = …)` бессилен — поток
стоит внутри `mongoc_client_pool_pop`, это C-вызов, а не точка приостановки. То есть на дедлоки
пула у нас нет сигнала лучше, чем «сборка не закончилась».

**Открытый вопрос 1 — закрыт.** Мост в `kotlinx.serialization` написан (решение Р10), вместе
с ним закрыта и веха M7. Ниже исходная формулировка.

~~**Открытый вопрос 1. Нужен ли мост в `kotlinx.serialization`?**~~ Официальный драйвер умеет
маппить data-классы. Собственная иерархия `BsonValue` (Р4) — это уровень «сырого BSON»;
слой над ней в скоуп MVP не входит. Гипотеза: `BsonValue` достаточно близок по форме к
`kotlinx.serialization` `SerialFormat`, чтобы мост написался поверх, а не вместо, — но проверять
это до закрытия M4 незачем. Ответ «да» — предпосылка всей вехи M7: типобезопасный DSL вида
`Person::age gt 18` без типизированных коллекций не существует (Р7). Задача — M-21.

**Открытый вопрос 2 — закрыт.** Насколько буквально копировать форму официального драйвера?
Ответ дал сам артефакт: `FindFlow` — это `Flow`, поэтому вопрос оказался не про выбор, а про
порядок (§1.9, решение Р8).

---

## 4. Что делать дальше

Порядок работ и приёмка — в [BACKLOG.md](../../BACKLOG.md).

Раньше всего фиксируется то, от чего зависит остальное:

Вехи M0–M5а закрыты. Дальше приоритет — **не функциональность, а проверки**: прототип доказал,
что архитектура работает, но почти все утверждения о корректности сейчас держатся на моих
ожиданиях, а не на эталоне.

1. **Дифференциальные тесты (M-28)** — тот же сценарий через официальный JVM-драйвер и через
   mongkn на одном mongod. Заменяет «я решил, что `deletedCount` должен быть 1» на «эталон
   согласен». 80% ценности за 10% усилий.
2. **Конкурентный стресс-тест (M-29)** — закрывает риск 5, самую большую непроверенную область.
3. **Счётчик аллокаций (M-06)** — единственное, что ловит утечки.
4. **Раннер spec-тестов (M-30)** — то, чем MongoDB определяет «драйвер реализован верно».
   По объёму сопоставим со всем, что написано в проекте: entity map, операции с аргументами,
   matchers, `runOnRequirements`. Это веха, а не задача, и браться за неё стоит после M-28.
5. **Удаление генератора (M-33)** — следствие Р9.
