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

**Следствие.** Фаза 1 драфта закрыта, но с поправкой из 1.1. Живой код — [Mongkn.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/Mongkn.kt).

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
[MongoException](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/MongoException.kt)
с `domain` + `code` + текстом.

### 1.4 `mongoc_client_t` не потокобезопасен — а `Dispatchers.Default` многопоточный

Два факта, которые по отдельности безобидны, а вместе ломают ровно ту схему, что предлагает драфт.

| Факт | Где проверено |
|---|---|
| «Since `mongoc_client_t` structures are not thread-safe, `mongoc_client_pool_t` is used to retrieve a new `mongoc_client_t` for a given thread» | [MongoDB Docs — Connection Pools](https://www.mongodb.com/docs/languages/c/c-driver/current/connect/connection-options/connection-pools/) |
| `mongoc_client_pool_t` потокобезопасен, кроме `mongoc_client_pool_destroy()` | там же |
| API пула есть и в 2.x: `mongoc_client_pool_new_with_error`, `_pop`, `_try_pop`, `_push`, `_max_size`, `_destroy` | `/opt/homebrew/include/mongoc-2.1.1/mongoc/mongoc-client-pool.h` |
| На Kotlin/Native `Dispatchers.Default` подкреплён пулом потоков по числу ядер — с kotlinx.coroutines **1.7.0** (#3366) | [CHANGES.md kotlinx.coroutines](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/master/CHANGES.md) |
| `Dispatchers.IO` доступен на Kotlin/Native с **1.7.0** (#3205) | там же |
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

### Р5. Генерация живёт в JVM-модуле и отдаёт исходники в `nativeMain`

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
- зеркало и DSL решают разные задачи и не конкурируют: зеркало даёт **покрытие** дёшево
  (сотни методов, печатаются генератором), DSL — **эргономику** для тех немногих операций,
  которыми пользуются каждый день;
- цена: две точки входа в API вместо одной. Принимаем.

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

---

## 3. Риски и открытые вопросы

**Риск 1. KSP 2.3.11 не проверен с Kotlin 2.4.10.** Схема версий KSP сменилась на 2.3.0, явной
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

**Риск 3. Утечки на пути исключения между `bson_new()` и `bson_destroy()`.** Любой `throw`
посередине — утечка, и обычный тест её не увидит. Смягчение: ни один сырой указатель не живёт
вне `try/finally` или Arena-обёртки; отдельная проверка через `bson_mem_set_vtable` (подмена
аллокатора libbson на считающий) — задача M-06. **Гипотеза:** этот приём применим из Kotlin/Native
через `staticCFunction`; проверяется в M-06, в заголовках `bson/memory.h` наличие vtable-API
пока не подтверждено.

**Риск 4. `brew upgrade mongo-c-driver` меняет путь к заголовкам.** Версия зашита в имя каталога
(§1.1), и задача `cinteropMongoc…` этого не заметит: её входы — `.def` и Kotlin-файлы, а не
содержимое `/opt/homebrew/include`. Смягчение: разрешённые пути входят в конфигурацию сборки,
и при пропаже заголовков конфигурация падает с внятным текстом, а не с ошибкой линковщика.
Открыто: не сделать ли резолвнутые пути явным входом задачи, чтобы апгрейд инвалидировал кеш.

**Открытый вопрос 1. Нужен ли мост в `kotlinx.serialization`?** Официальный драйвер умеет
маппить data-классы. Собственная иерархия `BsonValue` (Р4) — это уровень «сырого BSON»;
слой над ней в скоуп MVP не входит. Гипотеза: `BsonValue` достаточно близок по форме к
`kotlinx.serialization` `SerialFormat`, чтобы мост написался поверх, а не вместо, — но проверять
это до закрытия M4 незачем. Ответ «да» — предпосылка всей вехи M7: типобезопасный DSL вида
`Person::age gt 18` без типизированных коллекций не существует (Р7). Задача — M-21.

**Открытый вопрос 2. Насколько буквально копировать форму официального драйвера?** У него
`find()` возвращает `FindFlow` с чейнингом (`.limit().sort().projection()`), а не голый
`Flow<Document>`. Голый `Flow` проще и честнее для MVP, но тогда генератор из фазы 4 не сможет
зеркалить сигнатуры один в один. Решается при первой реальной генерации — M-11.

---

## 4. Что делать дальше

Порядок работ и приёмка — в [BACKLOG.md](../../BACKLOG.md).

Раньше всего фиксируется то, от чего зависит остальное:

1. **Ресурсная модель (M2)** — пул, владение клиентом, где проходит граница потоков. Всё
   остальное API строится поверх; переделывать её после того, как написаны `insertOne` и `find`,
   дороже всего.
2. **Модель `BsonValue` (M1)** — от неё зависит каждая сигнатура, включая те, что будет
   печатать генератор.
3. Только потом операции (M3) и генерация (M5).
