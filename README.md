# mongkn

MongoDB для Kotlin/Native — обвязка над официальным C-драйвером (`libmongoc`) с API, форма
которого снята с `mongodb-driver-kotlin-coroutine`.

На Kotlin/Native официального драйвера MongoDB нет: `mongodb-driver-kotlin-coroutine` живёт
только на JVM. `mongkn` пытается сделать так, чтобы `cinterop` к libmongoc был написан один раз
и спрятан за `suspend fun insertOne(...)` и `fun find(...): Flow<Document>`.

**Статус: рабочий прототип.** Шесть операций (`insertOne`, `insertMany`, `updateOne`,
`deleteOne`, `countDocuments`, `find`), 25 тестов, из них 16 интеграционных против настоящего
mongod. Поверхность `MongoCollection` пока генерируется по сигнатурам официального JVM-драйвера
(KSP + KotlinPoet), но от этого плана решено отказаться: выгода оказалась меньше ожидаемой,
а главное — генератор проверяет форму API, а не поведение. Замер и обоснование —
[ресёрч §1.10 и решение Р9](docs/research/research-architecture.md).

Дальше приоритет на проверках корректности: дифференциальные тесты против официального
драйвера и раннер spec-тестов MongoDB. См. [BACKLOG.md](BACKLOG.md).

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
| `mongkn-codegen` | JVM: KSP-процессор, печатающий поверхность API |
| `mongkn-api-spec` | JVM: модуль, на котором запускается процессор (там на classpath официальный драйвер) |

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
