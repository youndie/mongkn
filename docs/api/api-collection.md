---
id: api-collection
title: Публичный API — client / database / collection
type: api
status: draft
owner: unassigned
implemented_by:
  - mongkn-core
mirrors: com.mongodb.kotlin.client.coroutine (mongodb-driver-kotlin-coroutine 5.9.1)
---

# API — client / database / collection

> **Статус: целевое.** Ничего из перечисленного ниже, кроме `Mongkn.initialize/shutdown`
> и `MongoException`, ещё не реализовано. Документ — контракт, который реализуют вехи M2–M4,
> и вход для генератора из M5. Сигнатуры, помеченные «целевое», сверяются с кодом по мере
> реализации; расходиться с кодом им нельзя — расходиться могут только с этим планом.

Здесь «API» — не HTTP, а публичная поверхность библиотеки: то, что видит пользователь `mongkn`.
Форма снята с официального корутинного драйвера (`com.mongodb.kotlin.client.coroutine`), потому
что именно её должен уметь зеркалить генератор M5. Отличия от официального драйвера перечислены
в конце и каждое имеет причину в [ресёрче](../research/research-architecture.md).

## 1. Реализовано сегодня

| Сигнатура | Где |
|---|---|
| `Mongkn.initialize()` / `Mongkn.shutdown()` | [Mongkn.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/Mongkn.kt) |
| `Mongkn.driverVersion` / `Mongkn.bsonVersion` | там же |
| `MongoException(domain, code, message)` | [MongoException.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/MongoException.kt) |

Коды ошибок — проверенные, не выдуманные: `domain` — значение `mongoc_error_domain_t`
из `mongoc/mongoc-error.h`, `code` для ошибок сервера совпадает с кодом MongoDB. Наблюдалось
в прогоне (ресёрч §1.3): дубликат `_id` → `domain=12` (`MONGOC_ERROR_COLLECTION`), `code=11000`.

## 2. Целевые сигнатуры

### 2.1 Значения BSON — M1

Sealed-иерархия вместо `Map<String, Any>`; причина — решение Р4 ресёрча (round-trip с проверкой
равенства недостижим на `Any`).

```
BsonValue
 ├ BsonString  ├ BsonInt32   ├ BsonInt64   ├ BsonDouble
 ├ BsonBoolean ├ BsonNull    ├ BsonObjectId├ BsonDateTime
 ├ BsonDocument (упорядоченные пары key → BsonValue)
 └ BsonArray
```

`Document` = `BsonDocument`. Порядок ключей значим — BSON его хранит, и сервер местами на него
опирается.

### 2.2 Ресурсы — M2

| Сигнатура (целевое) | Смысл |
|---|---|
| `MongoClient(connectionString: String)` | владеет `mongoc_client_pool_t`, а не `mongoc_client_t` |
| `MongoClient.close()` | `mongoc_client_pool_destroy`; не потокобезопасен по контракту libmongoc |
| `MongoClient.getDatabase(name): MongoDatabase` | |
| `MongoDatabase.getCollection(name): MongoCollection` | |

Клиент на время операции берётся `mongoc_client_pool_pop()` и возвращается `push()` в `finally`.
Причина, по которой это пул, а не один клиент, — решение Р2 ресёрча.

### 2.3 Операции — M3

| Сигнатура (целевое) | Отличие от драфта |
|---|---|
| `suspend fun MongoCollection.insertOne(document: Document): InsertOneResult` | драфт предлагал `Boolean`; сервер отдаёт `insertedId` (Р3) |
| `fun MongoCollection.find(filter: Document = Document()): Flow<Document>` | как в драфте |

`InsertOneResult` несёт `insertedId: BsonValue`.

Ошибки: провал операции — всегда `MongoException`, никогда возвращаемое значение. `false`
в схеме «Boolean + исключение» недостижим, поэтому его нет.

## 3. Отличия от официального драйвера

| Официальный драйвер | mongkn | Почему |
|---|---|---|
| `find()` возвращает `FindFlow` с чейнингом `.limit().sort()` | голый `Flow<Document>` | проще для MVP; но тогда генератор M5 не зеркалит сигнатуру один в один — открытый вопрос 2 ресёрча |
| маппинг data-классов, `kotlinx.serialization` | только `BsonValue` | вне скоупа MVP — открытый вопрос 1 |
| отмена операции доходит до сокета | отмена не прерывает вызов | драйвер синхронный — риск 2 |
| `MongoClient` можно делить между потоками свободно | так же, но за счёт пула внутри | `mongoc_client_t` не потокобезопасен — §1.4 |
