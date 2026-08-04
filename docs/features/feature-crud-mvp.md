---
id: feature-crud-mvp
title: Минимальный CRUD из Kotlin/Native
type: feature
status: active
owner: unassigned
involved_services:
  - mongkn-core
api:
  - api-collection
tags: [mvp]
---

# Минимальный CRUD из Kotlin/Native

## 1. Суть

Приложению на Kotlin/Native нужно записать документ в MongoDB и прочитать его обратно, не выходя
за пределы Kotlin: без JNI, без JVM, без ручного `cinterop` в прикладном коде. Пользователь пишет
`collection.insertOne(doc)` и `collection.find(filter).collect { … }`, а всё, что касается
указателей, освобождения памяти и блокирующего C-драйвера, остаётся внутри библиотеки.

Это MVP: две операции. Их достаточно, чтобы доказать, что архитектура жизнеспособна — в них
уже есть и запись с разбором ответа, и курсор с обязательным освобождением, и подъём ошибки
сервера, и граница потоков.

## 2. Бизнес-ограничения

* Провал операции — исключение `MongoException` с кодом сервера; операция **никогда** не
  возвращает признак неуспеха отдельным значением.
* Документ, прошедший `Document → bson_t → Document`, равен исходному, включая типы чисел
  и порядок ключей. Это критерий приёмки слоя BSON, а не пожелание.
* Курсор освобождается при любом исходе коллекции Flow, включая отмену и исключение потребителя.
* Ни один сырой `CPointer` не виден снаружи `mongkn-core`.

## 3. Как это работает

1. `MongoClient(uri)` создаёт `mongoc_client_pool_t` (не клиент — см. Р2 ресёрча).
2. Операция уходит на пул потоков, которым владеет клиент (`Dispatchers.IO` на Kotlin/Native
   недоступен — ресёрч §1.8), берёт клиента из пула libmongoc, работает, возвращает клиента
   в `finally`.
3. `insertOne` конвертирует `Document` в `bson_t`, зовёт `mongoc_collection_insert_one`, читает
   `insertedId` из `reply`, освобождает `reply` и документ.
4. `find` открывает курсор `mongoc_collection_find_with_opts`, в `flow { }` крутит
   `mongoc_cursor_next`, **конвертирует каждый документ в Kotlin до эмиссии** (указатель от
   курсора живёт только до следующего `next`), и уничтожает курсор в `finally`.

## 4. Якоря кода

| Модуль | Код |
|---|---|
| mongkn-core | [CollectionOps.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/CollectionOps.kt) — реализация `insertOne` и `find` целиком |
| mongkn-codegen | публичный `MongoCollection` **генерируется** — [mongkn-codegen](../services/mongkn-codegen.md) |
| mongkn-core | [MongoClient.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/MongoClient.kt) — пул клиентов и граница потоков |
| mongkn-core | [bson/BsonCodec.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/bson/BsonCodec.kt) — конверсия и владение указателями |
| mongkn-core | [Mongkn.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/Mongkn.kt) — жизненный цикл драйвера |

## 5. Сценарии (BDD)

Все сценарии автоматизированы в
[MongoIntegrationTest](../../mongkn-core/src/nativeTest/kotlin/io/github/mongkn/MongoIntegrationTest.kt)
и прогоняются против настоящего mongod. Значения сверены с кодом и с прогоном.

### Сценарий: запись и чтение документа
* **Дано:** поднят локальный mongod, создан `MongoClient("mongodb://127.0.0.1:27017")`.
* **Когда:** вызван `insertOne(Document("name" to BsonString("kotlin-native")))`, затем
  `find(Document())` собран в список.
* **Тогда:** `insertOne` возвращает `InsertOneResult` с непустым `insertedId` типа `BsonObjectId`,
  а список содержит документ с `name = "kotlin-native"` и полем `_id`, равным `insertedId`.
* **Автоматизирован:** `MongoIntegrationTest.inserts a document and reads it back`

### Сценарий: round-trip BSON без потери типов
* **Дано:** `Document` с полями типов `String`, `Int32`, `Int64`, `Double`, `Boolean`,
  вложенным `BsonDocument` и `BsonArray`.
* **Когда:** документ переведён в `bson_t` и обратно.
* **Тогда:** результат равен исходному по `==`, включая различие `BsonInt32` / `BsonInt64`
  и порядок ключей.
* **Автоматизирован:** `BsonRoundTripTest` (8 тестов) — и `MongoIntegrationTest.nested documents and arrays survive a real round trip through the server` для пути через сервер

### Сценарий: дубликат ключа
* **Дано:** документ с `_id = 1` уже вставлен.
* **Когда:** вставляется второй документ с тем же `_id`.
* **Тогда:** поднимается `MongoException` с `domain = 12`, `code = 11000` и сообщением,
  начинающимся с `E11000 duplicate key error collection:`.
* **Автоматизирован:** `MongoIntegrationTest.duplicate key raises MongoException with the server code`

### Сценарий: сервер недоступен
* **Дано:** URI указывает на порт, где никто не слушает, `serverSelectionTimeoutMS=3000`.
* **Когда:** вызван `insertOne`.
* **Тогда:** не позднее таймаута поднимается `MongoException` с `domain = 15`
  (`MONGOC_ERROR_SERVER_SELECTION`). Значение было выведено из enum, а не из прогона, —
  тест его подтвердил.
* **Автоматизирован:** `MongoIntegrationTest.unreachable server fails within the selection timeout`

### Сценарий: отмена коллекции Flow освобождает курсор
* **Дано:** в коллекции больше документов, чем потребитель собирается прочитать.
* **Когда:** `find(...).take(1).collect { }`.
* **Тогда:** курсор освобождён, и следующая операция на том же клиенте работает.
* **Автоматизирован:** `MongoIntegrationTest.cancelling the flow does not break the client`.
  Тест проверяет **последствие** (клиент жив), а не сам факт `mongoc_cursor_destroy` —
  прямая проверка через счётчик аллокаций libbson осталась задачей M-06.

## 6. Что не входит в скоуп

* `update`, `delete`, агрегации, транзакции, GridFS, change streams — после того, как MVP
  докажет архитектуру.
* Маппинг data-классов и `kotlinx.serialization` — открытый вопрос 1 ресёрча.
* Чейнинг `find().limit().sort()` — открытый вопрос 2.

## 7. Известные особенности (Quirks)

* **`insertOne` возвращает `insertedId`, хотя драфт обещал `Boolean`.** Сервер кладёт его
  в `reply` сам: `{ "insertedCount" : 1, "insertedId" : { "$oid" : … } }` — проверено прогоном.
* **`domain = 12` у ошибки дубликата — это `MONGOC_ERROR_COLLECTION`, а не «ошибка записи».**
  Нумерация `mongoc_error_domain_t` начинается с 1, и легко ошибиться на единицу, считая её
  с нуля.
* **Отмена Flow не прерывает сетевой вызов, который уже начался.** Курсор освободится, но не
  раньше, чем `mongoc_cursor_next` вернётся сам. Выглядит как зависшая отмена — это ожидаемо.
* **`Dispatchers.Default` в этом коде — баг, даже если тесты зелёные.** Гонка на общем
  `mongoc_client_t` воспроизводится редко и не под нагрузкой тестов. `Dispatchers.IO` тоже
  не вариант — на Kotlin/Native он `internal` (ресёрч §1.8); используется пул потоков клиента.
* **`Mongkn.shutdown()` убивает драйвер на весь процесс.** `mongoc_init()` после
  `mongoc_cleanup()` не восстанавливает библиотеку — следующий сетевой вызов упадёт по
  assertion внутри libmongoc. Поэтому `MongoClient.close()` его не зовёт.
* **Интеграционные тесты падают без mongod, а не пропускаются.** Тест, зеленеющий без
  сервера, ничего не проверяет.
