# mongkn

MongoDB для Kotlin/Native — обвязка над официальным C-драйвером (`libmongoc`) с API, форма
которого снята с `mongodb-driver-kotlin-coroutine`.

На Kotlin/Native официального драйвера MongoDB нет: `mongodb-driver-kotlin-coroutine` живёт
только на JVM. `mongkn` пытается сделать так, чтобы `cinterop` к libmongoc был написан один раз
и спрятан за `suspend fun insertOne(...)` и `fun find(...): Flow<Document>`.

**Статус: рабочий прототип.** Шесть операций (`insertOne`, `insertMany`, `updateOne`,
`deleteOne`, `countDocuments`, `find`), 33 теста против настоящего mongod — включая
дифференциальные против официального драйвера и стресс-тест пула соединений. Форма API снята с официального `mongodb-driver-kotlin-coroutine`, а совпадение с ним проверяется
**дифференциальными тестами**: один и тот же документ проходит через официальный JVM-драйвер
и через mongkn на одном mongod, и результаты сверяются в обе стороны.

Генерация API через KSP была и от неё отказались — обоснование в
[ресёрче](docs/research/research-architecture.md), решение Р9. Дальше — раннер spec-тестов
MongoDB и стресс-тест пула, см. [BACKLOG.md](BACKLOG.md).

```kotlin
fun main() = runBlocking {
    MongoClient("mongodb://127.0.0.1:27017").use { client ->
        val people = client.getDatabase("app").getCollection("people")

        val inserted = people.insertOne(document { put("name", "Ada"); put("born", 1815) })
        println(inserted.insertedId)

        people.find(document { put("name", "Ada") }).collect { println(it) }
    }
}
```

## Модули

| Модуль | Что делает |
|---|---|
| `mongkn-core` | Kotlin/Native: cinterop, BSON, клиент, операции |
| `mongkn-difftest` | JVM: эталон для дифференциальных тестов — официальный драйвер |

## Требования

- Kotlin 2.4.10, Gradle 9.5.0 (обёртка в репозитории)
- mongo-c-driver 2.x: `brew install mongo-c-driver`

## Сборка

```bash
./gradlew :mongkn-core:build
```

Интеграционным тестам нужен локальный mongod:

```bash
docker run -d --name mongkn-it -p 27017:27017 mongo:8
```

## Документация

[docs/](docs/README.md) — слоёная документация. Начинать с
[research-architecture](docs/research/research-architecture.md): там разобрано, почему
архитектура именно такая и где исходный замысел пришлось поменять.
