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
  - klib io.github.mongkn:mongkn-core (пока не публикуется)
---

# mongkn-core

## 1. Зона ответственности

Единственный модуль, который знает про C. Держит `cinterop`-биндинги к `libmongoc`/`libbson`,
разрешение путей к системной библиотеке, владение сырыми указателями и границу потоков между
корутинами Kotlin и блокирующим C-драйвером.

Чем **не** занимается:

- маппингом data-классов и `kotlinx.serialization` — это слой над `BsonValue`, вне MVP
  (открытый вопрос 1 ресёрча);
- генерацией кода — это будущий JVM-модуль (решение Р5 ресёрча), сюда попадают только его
  результаты;
- любыми JVM-зависимостями: `java.*` и `org.bson.*` здесь недоступны физически, а не по договорённости.

Главный инвариант: **ни один сырой `CPointer` не покидает модуль**. Наружу уходят только
Kotlin-значения; всё, что аллоцировано в C, освобождается в том же модуле, в `finally` или
в Arena-обёртке.

## 2. Контракт

Публичный API — [api-collection](../api/api-collection.md) (целевой, реализуется в M2–M4).
Сегодня реализовано только то, что перечислено в 2а.

## 2а. Ключевые файлы (якоря кода)

| Файл | Что там |
|---|---|
| [mongkn-core/build.gradle.kts](../../mongkn-core/build.gradle.kts) | разрешение путей к libmongoc (`findIncludeDir` / `findLibName`), выбор хостового таргета, `linkerOpts` |
| [src/nativeInterop/cinterop/mongoc.def](../../mongkn-core/src/nativeInterop/cinterop/mongoc.def) | какие заголовки попадают в klib; путей и имён библиотек здесь намеренно нет |
| [src/nativeMain/kotlin/io/github/mongkn/Mongkn.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/Mongkn.kt) | `mongoc_init` / `mongoc_cleanup` под счётчиком ссылок, версии рантайма |
| [src/nativeMain/kotlin/io/github/mongkn/MongoException.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/MongoException.kt) | подъём `bson_error_t` в исключение |
| [src/nativeTest/kotlin/io/github/mongkn/MongknInitTest.kt](../../mongkn-core/src/nativeTest/kotlin/io/github/mongkn/MongknInitTest.kt) | тест инициализации; проверяет версию **слинкованной** библиотеки |

## 3. Как устроено

**Разрешение системной библиотеки.** `.def`-файл cinterop не умеет pkg-config, а самого
pkg-config на машине разработки нет (ресёрч §1.1), поэтому пути вычисляются в Gradle на этапе
конфигурации: `findIncludeDir("mongoc/mongoc.h")` перебирает `/opt/homebrew`, `/usr/local`,
`/usr` и их версионированные подкаталоги вида `mongoc-2.1.1`, а `findLibName("mongoc")` выводит
`mongoc2` (2.x) или `mongoc-1.0` (1.x) из содержимого `<prefix>/lib`. Отсюда же берётся понятная
ошибка конфигурации вместо невнятного отказа линковщика.

**Инициализация.** `mongoc_init()` обязан быть вызван ровно один раз на процесс до любого другого
вызова драйвера. `Mongkn.initialize()` считает ссылки атомарно (`kotlin.concurrent.atomics.AtomicInt`),
потому что звать его будут из разных потоков и повторный `mongoc_init()` без парного
`mongoc_cleanup()` — undefined behaviour.

**Граница потоков (целевое, M2).** Вызовы `libmongoc` блокирующие, асинхронного API у C-драйвера
нет. Операции уходят на `Dispatchers.IO`, а не на `Dispatchers.Default`: последний — процессорный
пул, и он же многопоточный, что при одном общем `mongoc_client_t` даёт гонку (ресёрч §1.4).
Клиент на время операции берётся из `mongoc_client_pool_t` и возвращается в `finally`.

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

## 6. Инфраструктура и деплой

Библиотека, не сервис. Публикации пока нет — задача M-18.

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

Интеграционным тестам (с M3) нужен локальный mongod:

```bash
docker run -d --name mongkn-it -p 27017:27017 mongo:8
```

## 8. Сознательные ограничения / грабли

* **В сборке только хостовый таргет.** `cinterop` требует заголовков целевой платформы, поэтому
  объявленный `linuxX64` уронил бы конфигурацию на macOS. Это решение (Р6 ресёрча), а не
  недоделка; матрица таргетов — задача CI, M-17.
* **Отмена корутины не прерывает сетевой вызов.** Драйвер синхронный: `cancel()` не остановит уже
  начатый `mongoc_cursor_next`. Верхняя граница ожидания задаётся только таймаутами в URI —
  риск 2 ресёрча.
* **`reply` от `insert_one` надо `bson_destroy` и при успехе.** `alloc<bson_t>()` вернёт стековые
  128 байт, но не то, что libbson доаллоцировал в куче — ресёрч §1.3, следствие 2.
* **`Dispatchers.Default` здесь запрещён.** Не стилистика: он многопоточный на Kotlin/Native
  с coroutines 1.7.0, а `mongoc_client_t` не потокобезопасен.
