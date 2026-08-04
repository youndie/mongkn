---
id: coverage
title: Что покрыто, а что нет
status: active
date: 2026-08-04
---

# Покрытие

Документ отвечает на один вопрос: **насколько mongkn можно пользоваться**. Цифры получены
подсчётом по jar официального драйвера `mongodb-driver-kotlin-coroutine` 5.9.1 (`javap`),
а не на глаз.

Короткий ответ: **вертикаль готова, горизонталь — нет.** Всё, что нужно, чтобы операция
вообще работала — cinterop, память, потоки, BSON, маппинг классов, публикация, CI, — сделано
и проверено. Самих операций реализовано около пятой части.

## Операции коллекции — 6 из 30

| Реализовано | Нет |
|---|---|
| `insertOne`, `insertMany` | `bulkWrite` |
| `updateOne` | `updateMany`, `replaceOne` |
| `deleteOne` | `deleteMany` |
| `find`, `countDocuments` | `estimatedDocumentCount`, `distinct`, `aggregate`, `mapReduce` |
| | `findOneAndUpdate`, `findOneAndDelete`, `findOneAndReplace` |
| | `drop`, `renameCollection`, `watch` |
| | индексы: `createIndex(es)`, `dropIndex(es)`, `listIndexes` |
| | поисковые индексы: `createSearchIndex(es)`, `updateSearchIndex`, `dropSearchIndex`, `listSearchIndexes` |

**Настроек коллекции нет вовсе** (13 методов у официального): `withReadConcern`,
`withWriteConcern`, `withReadPreference`, `withTimeout`, `withCodecRegistry`, `withDocumentClass`
и соответствующие геттеры. Всё, что можно задать сегодня, задаётся строкой подключения.

## `FindFlow` — 5 из 22 методов чейнинга

Есть: `limit`, `skip`, `sort`, `projection`, `batchSize`.

Нет: `hint`, `hintString`, `collation`, `comment`, `let`, `max`, `min`, `maxTime`, `maxAwaitTime`,
`noCursorTimeout`, `partial`, `returnKey`, `showRecordId`, `allowDiskUse`, `cursorType`,
`timeoutMode` и прочие.

## База и клиент

`MongoDatabase` — 1 операция из 9: только `getCollection`. Нет `runCommand`, `createCollection`,
`createView`, `drop`, `listCollections`, `listCollectionNames`, `aggregate`, `watch`.

`MongoClient` — создание, `getDatabase`, `close`. Нет `listDatabases`, `watch`, `startSession`.

## Типы BSON — 18 из 20

Не поддержаны только `dbpointer` (удалён из спецификации) и `code with scope` (устаревший);
оба дают понятный отказ, а не порчу данных. Всё остальное читается и пишется, включая `binary`
с подтипом, `decimal128`, `timestamp`, `regex`, `minKey`/`maxKey`.

## Чего нет как подсистем

* **Транзакции и сессии** — `ClientSession` не реализован; у официального драйвера он есть
  перегрузкой у каждой операции.
* **Change streams** (`watch`) — требуют курсора с иным жизненным циклом.
* **Мониторинг команд (APM)** — из-за этого 8 официальных spec-сценариев пропускаются (M-39).
* **GridFS**, **client-side field level encryption**, **агрегации**.

## Что зато сделано целиком

| Область | Состояние |
|---|---|
| cinterop к libmongoc | 2.x и 1.x, обе ветки проверяются в CI |
| Владение памятью | утечки ловятся подменённым аллокатором libbson |
| Многопоточность | пул клиентов + семафор; 200 одновременных операций под тестом |
| Модель BSON | 18 типов, round-trip без потерь, property-тесты |
| Маппинг классов | `kotlinx.serialization`, свой древесный формат |
| Эргономика | infix-DSL с проверкой имён полей |
| Сверка с эталоном | дифференциальные тесты против официального драйвера, 25 полей |
| Соответствие спецификации | 14 официальных сценариев MongoDB, 5 операций из 6 |
| Публикация | приватный Reposilite, `linuxX64` |
| Проверки | 111 тестов на двух платформах, ABI-валидация |

## Как это читать

Добавить операцию сегодня стоит недорого: `deleteMany` или `updateMany` — это ~15 строк
в `CollectionOps` по образцу соседей плюс метод в `MongoCollection`. Дорогими остаются
подсистемы: сессии, агрегации, change streams, APM.

То есть проект прошёл фазу «докажем, что архитектура работает» и упирается не в неизвестность,
а в объём. Порядок дальнейших работ — [BACKLOG.md](../BACKLOG.md).
