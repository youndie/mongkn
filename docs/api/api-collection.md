---
id: api-collection
title: Публичный API — client / database / collection
type: api
status: active
owner: unassigned
implemented_by:
  - mongkn-core
mirrors: com.mongodb.kotlin.client.coroutine (mongodb-driver-kotlin-coroutine 5.9.1)
---

# API — client / database / collection

> **Статус: реализовано.** Шесть операций, 39 тестов — из них 3 дифференциальных, сверяющих
> нас с официальным драйвером, 7 стрессовых и 4 на утечки. Поверхность рукописная;
> откуда берётся совпадение с официальным API — раздел 4.

Здесь «API» — не HTTP, а публичная поверхность библиотеки: то, что видит пользователь `mongkn`.
Форма снята с официального корутинного драйвера (`com.mongodb.kotlin.client.coroutine`), потому
что именно её должен уметь зеркалить генератор M5. Отличия от официального драйвера перечислены
в конце и каждое имеет причину в [ресёрче](../research/research-architecture.md).

## 1. Жизненный цикл драйвера

| Сигнатура | Где |
|---|---|
| `Mongkn.initialize()` — идемпотентен; обычно не нужен, [MongoClient] делает сам | [Mongkn.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/Mongkn.kt) |
| `Mongkn.shutdown()` — **терминален на весь процесс**, повторный `initialize()` бросит `IllegalStateException` | там же |
| `Mongkn.driverVersion` / `Mongkn.bsonVersion` | там же |
| `MongoException(domain, code, message)` | [MongoException.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/MongoException.kt) |

Коды ошибок — проверенные, не выдуманные: `domain` — значение `mongoc_error_domain_t`
из `mongoc/mongoc-error.h`, `code` для ошибок сервера совпадает с кодом MongoDB. Наблюдалось
в прогоне (ресёрч §1.3): дубликат `_id` → `domain=12` (`MONGOC_ERROR_COLLECTION`), `code=11000`.

## 2. Поверхность API

### 2.1 Значения BSON

Sealed-иерархия вместо `Map<String, Any>`; причина — решение Р4 ресёрча (round-trip с проверкой
равенства недостижим на `Any`). Код — [bson/BsonValue.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/bson/BsonValue.kt).

Документы строятся билдером: `document { put("name", "x"); putDocument("n") { put("a", 1) } }` —
[bson/DocumentBuilder.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/bson/DocumentBuilder.kt).

Типы за пределами списка (binary, decimal128, regex, code) при чтении дают
`UnsupportedBsonTypeException` — осознанная граница прототипа, задача M-24.

```
BsonValue
 ├ BsonString  ├ BsonInt32   ├ BsonInt64   ├ BsonDouble
 ├ BsonBoolean ├ BsonNull    ├ BsonObjectId├ BsonDateTime
 ├ BsonDocument (упорядоченные пары key → BsonValue)
 └ BsonArray
```

`Document` = `BsonDocument`. Порядок ключей значим — BSON его хранит, и сервер местами на него
опирается.

### 2.2 Ресурсы

| Сигнатура | Смысл |
|---|---|
| `MongoClient(connectionString: String, ioThreads: Int = 4)` | владеет `mongoc_client_pool_t` и собственным пулом потоков |
| `MongoClient.close()` | `mongoc_client_pool_destroy` + закрытие пула потоков; не потокобезопасен по контракту libmongoc. `Mongkn.shutdown()` **не** зовёт |
| `MongoClient.getDatabase(name): MongoDatabase` | |
| `MongoDatabase.getCollection(name): MongoCollection` | |

Клиент на время операции берётся `mongoc_client_pool_pop()` и возвращается `push()` в `finally`.
Причина, по которой это пул, а не один клиент, — решение Р2 ресёрча.

### 2.3 Операции

| Сигнатура | Заметки |
|---|---|
| `suspend fun insertOne(document: Document): InsertOneResult` | драфт предлагал `Boolean`; сервер отдаёт `insertedId` (Р3) |
| `suspend fun insertMany(documents: List<Document>): InsertManyResult` | пустой список отвергается до обращения к серверу |
| `suspend fun updateOne(filter: Document, update: Document): UpdateResult` | обновление **документом**, не агрегационным конвейером — выбор перегрузки сделан генератором механически (§1.10) |
| `suspend fun deleteOne(filter: Document): DeleteResult` | |
| `suspend fun countDocuments(filter: Document = Document()): Long` | единственная операция, где libmongoc отдаёт результат возвращаемым значением, а ошибку — отрицательным числом |
| `fun find(filter: Document = Document()): Flow<Document>` | как в драфте |

`InsertOneResult` несёт `insertedId: BsonValue` — `BsonObjectId` от сервера либо то значение
`_id`, которое задал вызывающий (проверено тестом).

Ошибки: провал операции — всегда `MongoException`, никогда возвращаемое значение. `false`
в схеме «Boolean + исключение» недостижим, поэтому его нет.

## 3. Отличия от официального драйвера

Сверено по jar `mongodb-driver-kotlin-coroutine` 5.9.1 (`javap` по
`com/mongodb/kotlin/client/coroutine/MongoCollection.class` и `FindFlow.class`), задача M-13.

Что там есть в цифрах: у `MongoCollection` каждая операция продублирована перегрузкой
с `ClientSession`, у `find` вдобавок есть варианты с `Class<R>`; `FindFlow` несёт 23 метода
чейнинга (`limit`, `skip`, `sort`, `projection`, `hint`, `collation`, `batchSize`, …) при 34
публичных методах всего. Мы зеркалим базовые формы без сессии и без опций.

**Главное, что дала сверка:** `FindFlow<T> implements Flow<T>` — по делегированию. Значит наш
`find(): Flow<Document>` не альтернативная форма, а **подмножество**: заменить возвращаемый тип
на `FindFlow` можно потом, не сломав ни одного вызывающего. Открытый вопрос 2 закрыт этим фактом
(решение Р8).

| Официальный драйвер | mongkn | Почему |
|---|---|---|
| `find()` возвращает `FindFlow` с 23 методами чейнинга | голый `Flow<Document>` | `FindFlow` **является** `Flow`, поэтому апгрейд будет source-совместимым — решение Р8 |
| маппинг data-классов, `kotlinx.serialization` | только `BsonValue` | вне скоупа MVP — открытый вопрос 1 |
| отмена операции доходит до сокета | отмена не прерывает вызов | драйвер синхронный — риск 2 |
| `MongoClient` можно делить между потоками свободно | так же, но за счёт пула внутри | `mongoc_client_t` не потокобезопасен — §1.4 |
| `MongoCollection<T>` параметризован классом документа | только `Document` | типизированные коллекции — веха M7 (M-21) |
| у каждой операции есть перегрузка с `ClientSession` | нет | транзакции вне скоупа MVP |
| `insertOne(doc, InsertOneOptions)` | `insertOne(doc)` | параметры-опции отбрасываются генератором явным списком, а не молча |
| отдельный артефакт `mongodb-driver-kotlin-extensions` с infix-DSL (`Person::age gt 18`) | нет | это веха M7 и отдельный модуль, а не часть ядра — решение Р7 |

## 4. Откуда берётся совпадение с официальным драйвером

`MongoCollection` — **рукописный** файл. До решения Р9 его печатал KSP-процессор; генерация
удалена (M-33), потому что гарантировала форму API, а не поведение, и стоила двух JVM-модулей
на критическом пути сборки.

Совпадение с официальным драйвером теперь держится на двух вещах:

1. **Эта таблица** (раздел 3) и KDoc в
   [MongoCollection.kt](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/MongoCollection.kt) —
   текст, который может протухнуть. Это признанная цена решения Р9.
2. **Дифференциальные тесты** ([mongkn-difftest](../services/mongkn-difftest.md)) — сверка
   с самим драйвером как с эталоном. Сильнее пункта 1: проверяют, что мы делаем то же самое,
   а не что мы так же назвали параметр.

Правила, по которым форма снималась (они же — инструкция при добавлении операций):

* из перегрузок берётся базовая: **без** `ClientSession` и **без** `*Options`;
* `org.bson.conversions.Bson` и параметр типа документа → `Document`;
* `FindFlow<T>` → `Flow<Document>` (решение Р8).

**Отдельно про ловушку.** У `updateOne` две перегрузки одинаковой длины: `(Bson, Bson)` —
обновление документом — и `(Bson, List<Bson>)` — агрегационным конвейером. Взята первая.
Генератор разрешал это механически; теперь единственная защита — дифференциальный тест.
При добавлении операций проверяйте одноимённые перегрузки по jar, а не по памяти.

## 5. Почему форма снята с чужого API, а не спроектирована своя

Короткий ответ — потому что это два разных слоя, и верхнеуровневый DSL кладётся **поверх**
зеркала, а не вместо него. Развёрнутое обоснование с фактами (в частности, что к этой же
схеме пришёл сам вендор, вынеся DSL в отдельный артефакт) — решение Р7 ресёрча.

Практическое следствие для этого документа: любая сигнатура здесь должна оставаться
расширяемой снаружи. Фильтры принимают `Document`; точки расширения не закрываются
`internal` / `final`. Иначе слой M7 придётся встраивать внутрь генератора.
