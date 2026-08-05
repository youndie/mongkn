---
id: coverage
title: Что покрыто, а что нет
status: active
date: 2026-08-05
---

# Покрытие

Документ отвечает на один вопрос: **насколько mongkn можно пользоваться**. Цифры получены
подсчётом по jar официального драйвера `mongodb-driver-kotlin-coroutine` 5.9.1 (`javap`),
а не на глаз.

Короткий ответ: **вертикаль готова, горизонталь наполовину.** Всё, что нужно, чтобы операция
вообще работала — cinterop, память, потоки, BSON, маппинг классов, публикация, CI, — сделано
и проверено. Операций реализована половина, и почти все они под официальным spec-покрытием.

## Операции коллекции — 23 из 30

| Реализовано | Нет |
|---|---|
| `insertOne`, `insertMany`, `bulkWrite` | |
| `updateOne`, `updateMany`, `replaceOne` | `mapReduce` (объявлен устаревшим) |
| `deleteOne`, `deleteMany` | |
| `find`, `countDocuments`, `estimatedDocumentCount` | |
| `findOneAndUpdate`, `findOneAndDelete`, `findOneAndReplace` | поисковые индексы Atlas: `createSearchIndex(es)`, `updateSearchIndex`, `dropSearchIndex`, `listSearchIndexes` |
| `distinct`, `drop`, `renameCollection` | |
| `aggregate`, `watch` | |
| индексы: `createIndex`, `createIndexes`, `dropIndex`, `dropIndexByKeys`, `dropIndexes`, `listIndexes` | |

Каждая операция принимает параметр `options: Document` — туда уходит всё, что libmongoc
берёт документом опций: `collation`, `hint`, `comment`, `let`, `bypassDocumentValidation`.

**Настройки коллекции — 4 из 13**: `withWriteConcern`, `withReadConcern`, `withTimeout`,
`withReadPreference`;
каждая возвращает копию, а её значения вливаются в опции каждой операции. Нет
`withCodecRegistry` и `withDocumentClass` — оба про JVM-кодеки, у нас их место занимает
`KSerializer`.

## `FindFlow` — 20 из 22 методов чейнинга

Есть: `limit`, `skip`, `sort`, `projection`, `batchSize`, `hint`, `hintString`, `collation`,
`comment`, `let`, `max`, `min`, `maxTime`, `maxAwaitTime`, `noCursorTimeout`, `partial`,
`returnKey`, `showRecordId`, `allowDiskUse`, `cursorType`.

Нет `timeoutMode` и `explain`: первый — часть CSOT, второй — отдельная команда.

## База и клиент

`MongoDatabase` — 7 операций из 9: `getCollection`, `runCommand`, `createCollection`, `drop`,
`listCollectionNames`, `aggregate`, `watch`. Нет `createView` и `listCollections` (полные
документы) — обе доступны через `runCommand`.

`MongoClient` — создание, `getDatabase`, `close`, `listDatabaseNames`, `watch`, `startSession`.
Нет `listDatabases` с полными документами.

## Типы BSON — 18 из 20

Не поддержаны только `dbpointer` (удалён из спецификации) и `code with scope` (устаревший);
оба дают понятный отказ, а не порчу данных. Всё остальное читается и пишется, включая `binary`
с подтипом, `decimal128`, `timestamp`, `regex`, `minKey`/`maxKey`.

## `AggregateFlow` — 10 из 13 методов чейнинга

Есть: `batchSize`, `allowDiskUse`, `bypassDocumentValidation`, `collation`, `comment`, `hint`,
`hintString`, `let`, `maxTime`, `maxAwaitTime`, плюс `toCollection` для конвейеров с `$out`
и `$merge`. Нет `timeoutMode` (CSOT) и `explain`.

## Чего нет как подсистем

* **GridFS**, **client-side field level encryption**.

## Что зато сделано целиком

| Область | Состояние |
|---|---|
| cinterop к libmongoc | 2.x и 1.x, обе ветки проверяются в CI; нижняя граница — 1.26 из Ubuntu 24.04 LTS (M-81) |
| Владение памятью | утечки ловятся подменённым аллокатором libbson |
| Многопоточность | пул клиентов + семафор; 200 одновременных операций под тестом |
| Модель BSON | 18 типов, round-trip без потерь, property-тесты |
| Маппинг классов | `kotlinx.serialization`, свой древесный формат |
| Эргономика | infix-DSL с проверкой имён полей |
| Сверка с эталоном | дифференциальные тесты против официального драйвера, 25 полей |
| Соответствие спецификации | **63** официальных сценария MongoDB, включая проверку отправленных команд; непокрытыми остались 6 по разным мелким причинам |
| Публикация | приватный Reposilite, `linuxX64` |
| Подписки | `watch` на коллекции, базе и клиенте; возобновление после сбоя проверено failpoint'ами |
| Производительность | надбавка поверх C измерена: на записи не различима, на чтении +52 % ([docs/performance.md](performance.md)) |
| Надёжность | ретраи чтений и записей проверены failpoint'ами; домены ошибок разобраны, включена вторая версия API ошибок |
| Диагностика | обработчик логов драйвера ([MongknLog]) и мониторинг команд ([CommandListener]) |
| Аутентификация | SCRAM, TLS и x509 проверены на отдельных серверах |
| Сессии и транзакции | `startSession`, `withTransaction`; изоляция проверена, а не только успех коммита |
| Проверки | 224 теста на двух платформах, ABI-валидация, ktlint в гейте |

## Как это читать

Про сессии стоит знать цену: сессия занимает клиента из пула **и** отдельный поток на всё своё
время, потому что libmongoc привязывает её к конкретному `mongoc_client_t`. Настройки самой
транзакции (`readConcern`, `writeConcern`, `maxCommitTimeMS`) пока не задаются, а упавшая
транзакция не перезапускается сама — см. M-73 и M-74.

Добавить операцию сегодня стоит недорого: ~15 строк в `CollectionOps` по образцу соседей
плюс метод в `MongoCollection`. Дорогими остаются подсистемы: сессии,
change streams, APM.

Одна оговорка про опции. Они проверены на том, что сервер их **видит**: тесты задают значения,
которые он отвергает, и ловят отказ. Это доказывает, что документ опций доезжает до libmongoc
(до M10 `insertOne` и `findOneAnd*` теряли его молча), но не то, что каждый ключ трактуется
как у официального драйвера. Ключи, различимые только на replica set, — `readPreference`
и часть `writeConcern` — не проверены никак: см. M17.

То есть проект прошёл фазу «докажем, что архитектура работает» и упирается не в неизвестность,
а в объём. Пробелы из этого документа разложены по вехам M9–M17 в [BACKLOG.md](../BACKLOG.md)
с оценкой стоимости: дешёвые операции отдельно, дорогие подсистемы отдельно.

**Отдельно стоит смотреть веху M17** — там не недостающие функции, а области, которые просто
никогда не проверялись. Две из них с тех пор закрыты: реплика-сет теперь основной тестовый контур,
а SCRAM-аутентификация покрыта, и производительность измерена ([performance.md](performance.md)).
Остаются шардированный кластер и потребление памяти. Код там может работать, а может и нет;
разница с остальным бэклогом в том, что мы не знаем.
