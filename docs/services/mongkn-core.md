---
id: mongkn-core
title: mongkn-core
type: service
status: active
module: :mongkn-core
tech_stack: [Kotlin/Native, cinterop, kotlinx.coroutines]
targets: [macosArm64, macosX64, linuxX64]
owner: unassigned
depends_on:
  - libmongoc (системная библиотека, 2.x)
  - libbson (системная библиотека, 2.x)
publishes:
  - klib ru.workinprogress.mongkn:mongkn-core (пока не публикуется)
---

# mongkn-core

## 1. Зона ответственности

Единственный модуль, который знает про C. Держит `cinterop`-биндинги к `libmongoc`/`libbson`,
разрешение путей к системной библиотеке, владение сырыми указателями и границу потоков между
корутинами Kotlin и блокирующим C-драйвером.

Чем **не** занимается:

- маппингом data-классов и `kotlinx.serialization` — это слой над `BsonValue`, вне MVP
  (открытый вопрос 1 ресёрча);
- любыми JVM-зависимостями: `java.*` и `org.bson.*` здесь недоступны физически, а не по договорённости.

Главный инвариант: **ни один сырой `CPointer` не покидает модуль**. Наружу уходят только
Kotlin-значения; всё, что аллоцировано в C, освобождается в том же модуле, в `finally` или
в Arena-обёртке.

## 2. Контракт

Публичный API — [api-collection](../api/api-collection.md). Вехи M1–M3 закрыты: реализованы
модель BSON, клиент на пуле, `insertOne` и `find`.

## 2а. Ключевые файлы (якоря кода)

| Файл | Что там |
|---|---|
| [mongkn-core/build.gradle.kts](../../mongkn-core/build.gradle.kts) | разрешение путей к libmongoc (`findIncludeDir` / `findLibName`), выбор хостового таргета, `linkerOpts` |
| [src/nativeInterop/cinterop/mongoc.def](../../mongkn-core/src/nativeInterop/cinterop/mongoc.def) | какие заголовки попадают в klib; путей и имён библиотек здесь намеренно нет |
| [Mongkn.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/Mongkn.kt) | одноразовый жизненный цикл драйвера: `NEW → INITIALIZING → READY → SHUT_DOWN` |
| [MongoClient.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/MongoClient.kt) | владение `mongoc_client_pool_t`, `withClient` (pop/push), собственный пул потоков |
| [CollectionOps.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/CollectionOps.kt) | реализация `insertOne` и `find`; здесь же вся работа с курсором |
| [MongoCollection.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/MongoCollection.kt) | публичная поверхность; KDoc несёт правила, по которым форма снята с официального драйвера |
| [bson/BsonValue.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/bson/BsonValue.kt) | иерархия значений и `Document` |
| [bson/BsonCodec.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/bson/BsonCodec.kt) | перевод в `bson_t` и обратно; правила владения указателями |
| [bson/DocumentBuilder.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/bson/DocumentBuilder.kt) | минимальный билдер (`document { put(...) }`) |
| [src/nativeMain/kotlin/ru/workinprogress/mongkn/MongoException.kt](../../mongkn-core/src/nativeMain/kotlin/ru/workinprogress/mongkn/MongoException.kt) | подъём `bson_error_t` в исключение |
| [MongoIntegrationTest.kt](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/MongoIntegrationTest.kt) | сценарии фичи против настоящего mongod. Проверяют ожидания автора |
| [MongoDifferentialTest.kt](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/MongoDifferentialTest.kt) | сверка с официальным драйвером — фаза B, см. [mongkn-difftest](mongkn-difftest.md) |
| [MongoConcurrencyTest.kt](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/MongoConcurrencyTest.kt) | стресс-тест пула: 200 одновременных операций на одном клиенте |
| [bson/BsonAllocations.kt](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/bson/BsonAllocations.kt) | считающий аллокатор libbson — единственное, что видит утечки |
| [spec/SpecTestRunner.kt](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/spec/SpecTestRunner.kt) | раннер официальных spec-тестов MongoDB; частичный, отчёт печатает непокрытое |
| [spec-tests.gradle.kts](../../mongkn-core/spec-tests.gradle.kts) | загрузка spec-тестов в `build/` — в репозиторий они не кладутся из-за лицензии |
| [BsonRoundTripTest.kt](../../mongkn-core/src/nativeTest/kotlin/ru/workinprogress/mongkn/bson/BsonRoundTripTest.kt) | критерий приёмки M-04: round-trip без потери типов |

## 3. Как устроено

**Разрешение системной библиотеки.** `.def`-файл cinterop не умеет pkg-config, а самого
pkg-config на машине разработки нет (ресёрч §1.1), поэтому пути вычисляются в Gradle на этапе
конфигурации: `findIncludeDir("mongoc/mongoc.h")` перебирает `/opt/homebrew`, `/usr/local`,
`/usr` и их версионированные подкаталоги вида `mongoc-2.1.1`, а `findLibName("mongoc")` выводит
`mongoc2` (2.x) или `mongoc-1.0` (1.x) из содержимого `<prefix>/lib`. Отсюда же берётся понятная
ошибка конфигурации вместо невнятного отказа линковщика.

**Инициализация.** `mongoc_init()` вызывается ровно один раз на процесс, и после
`mongoc_cleanup()` драйвер **не восстанавливается** — поэтому `Mongkn` это автомат без возврата,
а не счётчик ссылок. Счётчик там сначала и стоял; чем именно он ломался — ресёрч §1.8.
`MongoClient.close()` намеренно не зовёт `Mongkn.shutdown()`.

**Граница потоков.** Вызовы `libmongoc` блокирующие, асинхронного API у C-драйвера нет.
Операции уходят на пул потоков, которым владеет сам `MongoClient` (`newFixedThreadPoolContext`,
4 потока по умолчанию). Ни `Dispatchers.Default` — он процессорный и многопоточный, что при
общем `mongoc_client_t` даёт гонку (§1.4), ни `Dispatchers.IO` — на Kotlin/Native он `internal`
(§1.8). Клиент на время операции берётся из `mongoc_client_pool_t` и возвращается в `finally`;
для `find` — на всё время жизни курсора.

## 4. Зависимости

| Тип | Имя | Для чего |
|---|---|---|
| System library | `libmongoc` 2.x | весь протокол MongoDB |
| System library | `libbson` 2.x | сборка и разбор BSON |
| Library | `kotlinx-coroutines-core` 1.11.0 | `suspend` / `Flow` / `Dispatchers.IO` |
| External | mongod | нужен только интеграционным тестам |

## 5. Конфигурация

| Параметр / ключ | Дефолт | Смысл |
|---|---|---|
| `-Pmongkn.prefix` | автоопределение | префикс установки mongo-c-driver, если он не в стандартном месте |
| `MONGKN_PREFIX` | автоопределение | то же самое переменной окружения |
| `MongoClient(ioThreads = …)` | 4 | потоков под блокирующие вызовы драйвера |
| `MongoClient(maxConcurrentClients = …)` | 100 | сколько операций одновременно держат клиента; перекрывает `maxPoolSize` из строки подключения |

## 6. Инфраструктура и деплой

Библиотека, не сервис. Публикации пока нет — задача M-18.

Модуль собирается сам по себе. Так было не всегда: до решения Р9 `MongoCollection` приезжал
сгенерированным из `:mongkn-api-spec`, и без JVM-модулей `:mongkn-core` не собирался вовсе.

**Что ломается при апгрейде окружения:** `brew upgrade mongo-c-driver` меняет имя каталога
с заголовками (`mongoc-2.1.1` → `mongoc-2.3.3`), а задача `cinteropMongocMacosArm64` не считает
этот путь своим входом. Лечится `./gradlew clean`. Подробнее — риск 4 ресёрча.

## 7. Локальный запуск

Предусловие — установленный C-драйвер:

```bash
brew install mongo-c-driver
```

Сборка и тесты хостового таргета:

```bash
./gradlew :mongkn-core:build
```

Первый прогон дополнительно требует сети: задача `fetchSpecTests` качает официальные spec-тесты
MongoDB в `build/spec-tests`. В репозиторий они не кладутся — `mongodb/specifications` под
CC BY-NC-SA 3.0, а это NonCommercial и ShareAlike (§1.16).

Интеграционным тестам нужен локальный mongod — без него они падают, а не пропускаются:

```bash
docker run -d --name mongkn-it -p 27017:27017 mongo:8
```

## 8. Сознательные ограничения / грабли

* **Linux-сборку локально проверяют в контейнере.** `docker build -t mongkn-ci ci/` и прогон
  с `--platform linux/amd64`: Kotlin/Native не умеет компилировать на хосте linux-aarch64,
  а на Apple Silicon контейнер по умолчанию именно такой (§1.18). Сборка на таком хосте теперь
  падает явно — раньше она молча зеленела, пропустив все задачи компиляции.
* **На Linux в `linkerOpts` добавляется `-Wl,--allow-shlib-undefined`.** Системная libbson
  ссылается на символы новее, чем glibc в sysroot Kotlin/Native; разрешает их динамический
  загрузчик при запуске.
* **В сборке только хостовый таргет.** `cinterop` требует заголовков целевой платформы, поэтому
  объявленный `linuxX64` уронил бы конфигурацию на macOS. Это решение (Р6 ресёрча), а не
  недоделка; матрица таргетов — задача CI, M-17.
* **Отмена корутины не прерывает сетевой вызов.** Драйвер синхронный: `cancel()` не остановит уже
  начатый `mongoc_cursor_next`. Верхняя граница ожидания задаётся только таймаутами в URI —
  риск 2 ресёрча.
* **Строки передаются в libbson с явной длиной, а не через `strlen`.** Для этого в `.def` стоит
  `noStringConversion = bson_append_utf8`. Иначе строка обрезается на первом NUL, а BSON его
  внутри значения допускает — §1.15. Ключи, наоборот, NUL содержать не могут и явно отвергаются.
* **`reply` от `insert_one` надо `bson_destroy` и при успехе.** `alloc<bson_t>()` вернёт стековые
  128 байт, но не то, что libbson доаллоцировал в куче — ресёрч §1.3, следствие 2.
* **`Dispatchers.Default` здесь запрещён, а `Dispatchers.IO` недоступен.** Первый многопоточный
  на Kotlin/Native с coroutines 1.7.0, а `mongoc_client_t` не потокобезопасен; второй объявлен
  `internal` в нативном артефакте coroutines, вопреки собственной документации — §1.8.
* **`Mongkn.shutdown()` терминален на весь процесс.** После него ни один новый `MongoClient`
  не заработает: `mongoc_init()` не восстанавливает драйвер после `mongoc_cleanup()`.
* **Долгий `find` держит клиента из пула всё время сбора потока.** Курсор принадлежит клиенту,
  вернуть того раньше нельзя — ограничение libmongoc. При медленных потребителях лишние операции
  **ждут на семафоре**: отменяемо, `withTimeout` работает, потоки свободны (§1.13). Раньше здесь
  было зависание намертво (§1.12).
* **Ожидание клиента отменяемо ровно до тех пор, пока клиент возвращается в пул.** Инвариант
  «есть разрешение — есть клиент» держится на `finally` в `MongoClient.useClient` — риск 6.
