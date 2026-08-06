<div align="center">

# 🍃 mongkn

**MongoDB для Kotlin/Native — по-настоящему, а не «когда-нибудь допилим».**

Официального драйвера MongoDB для Kotlin/Native нет: `mongodb-driver-kotlin-coroutine` живёт
только на JVM. `mongkn` пишет `cinterop` к `libmongoc` один раз и прячет его за
`suspend fun insertOne(…)` и `fun find(…): Flow<Document>`.

`Kotlin/Native` · `libmongoc` · `kotlinx.coroutines` · `kotlinx.serialization`

</div>

---

## Что умеет

- 🗂 **Операции коллекции — 23 из 30**: вставка, обновление, удаление, `find`, агрегации,
  индексы, `bulkWrite`, `findOneAnd*`, `distinct`
- 🔁 **Транзакции и сессии** — `withTransaction` с повторами по меткам сервера
- 📡 **Потоки изменений** — `watch` на коллекции, базе и клиенте, с автоматическим возобновлением
- 🧭 **Все топологии** — standalone, replica set, шардированный кластер через `mongos`
- 🔐 **SCRAM, TLS, x509** — проверено на серверах с `--auth` и `--tlsMode requireTLS`
- 📊 **Наблюдаемость** — `CommandListener` (APM) и обработчик логов драйвера
- 🧩 **Свои типы** — `BsonEncoder` / `BsonDecoder` как точка расширения
- 🧪 **251 тест** и **71 официальный spec-сценарий MongoDB** из 75

## Быстрый старт

```kotlin
dependencies {
    implementation("ru.workinprogress.mongkn:mongkn-core:0.1.6")
    // не обязателен, но пример ниже без него не соберётся: infix-DSL живёт здесь
    implementation("ru.workinprogress.mongkn:mongkn-extensions:0.1.6")
}
```

```kotlin
@Serializable
data class Person(val name: String, val born: Int)

fun main() = runBlocking {
    MongoClient("mongodb://127.0.0.1:27017").use { client ->
        val people = client.getDatabase("app").getCollection<Person>("people")

        people.insertOne(Person("Ada", 1815))
        people.find { Person::born lt 1900 }.collect { println(it) }
    }
}
```

Без маппинга тоже можно: `getCollection("people")` даёт `MongoCollection<Document>`.

## Свои типы

Точка расширения устроена как `JsonEncoder.encodeJsonElement` в `kotlinx-serialization-json`:
сериализатор проверяет тип кодировщика и отдаёт готовый `BsonValue`. Нужно тем типам,
у которых в BSON есть точное представление — деньги в `decimal128` тому примером.

```kotlin
object MoneySerializer : KSerializer<Money> {
    override val descriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Money) {
        val bson = encoder as? BsonEncoder
            ?: throw SerializationException("Money сериализуется только в BSON")
        bson.encodeBsonValue(BsonDecimal128(value.toPlainString()))
    }

    override fun deserialize(decoder: Decoder): Money {
        val bson = decoder as? BsonDecoder
            ?: throw SerializationException("Money читается только из BSON")
        return Money((bson.decodeBsonValue() as BsonDecimal128).value)
    }
}
```

## Устройство

| Слой | |
|---|---|
| **cinterop** | `libmongoc` 1.26+ и 2.x; имена библиотек и пути к заголовкам разрешаются в Gradle — у веток они разные |
| **Ресурсы** | `mongoc_client_pool_t` плюс семафор: `mongoc_client_t` не потокобезопасен, а `pool_pop` блокирует неотменяемо |
| **Потоки** | свой пул под блокирующие вызовы — `Dispatchers.IO` на Kotlin/Native `internal`, вопреки документации |
| **BSON** | 18 типов из 20, своя древесная модель и формат `kotlinx.serialization` поверх неё |
| **Курсоры** | `Flow`, освобождаемый при любом исходе сбора, включая отмену |

| Модуль | |
|---|---|
| `mongkn-core` | cinterop, BSON, клиент, операции |
| `mongkn-extensions` | infix-DSL фильтров и обновлений |
| `mongkn-difftest` | JVM-эталон: официальный драйвер для дифференциальных тестов |

## Сборка и тесты

```bash
./gradlew build
```

```bash
./ci/dev-servers.sh up
```

Интеграционным тестам нужен не один mongod, а четыре контура: replica set (иначе нет транзакций
и потоков изменений), сервер с `--auth`, сервер с обязательным TLS и шардированный кластер.
Без них тесты **падают, а не пропускаются**.

Совпадение с официальным драйвером проверяется **дифференциальными тестами**: один документ
проходит через JVM-драйвер и через mongkn на одном mongod, результаты сверяются в обе стороны.
Плюс официальные spec-тесты MongoDB, стресс пула, счётчик аллокаций libbson и property-тесты
кодека.

## Ограничения

- **Публикуется только `linuxX64`.** macOS-таргет собирается для разработки и чтобы в CI
  проверялась ветка драйвера 2.x — но наружу не выкладывается
- **Минимум libmongoc — 1.26** (Ubuntu 24.04 LTS). Ветка 2.x на Linux из пакетов не ставится
  нигде, только сборка из исходников
- **На Windows не собирается**: хостовый таргет там `mingwX64`, которого нет. Работайте из WSL2
- Нет: GridFS, шифрования на стороне клиента, поисковых индексов Atlas

## Документация

| | |
|---|---|
| [docs/coverage.md](docs/coverage.md) | что умеет, а что нет — цифрами |
| [docs/performance.md](docs/performance.md) | сколько стоит обвязка: на записи неразличимо, на чтении +52 % |
| [docs/research/](docs/research/) | решения и почему очевидное здесь трижды неверно |
| [BACKLOG.md](BACKLOG.md) | что дальше |
| [CLAUDE.md](CLAUDE.md) | как собрать, поднять серверы и опубликовать |

---

<div align="center"><sub>Сделано, чтобы Kotlin/Native наконец умел в Mongo.</sub></div>
