# mongkn

MongoDB для Kotlin/Native — обвязка над официальным C-драйвером (`libmongoc`) с API, форма
которого снята с `mongodb-driver-kotlin-coroutine`.

На Kotlin/Native официального драйвера MongoDB нет: `mongodb-driver-kotlin-coroutine` живёт
только на JVM. `mongkn` пытается сделать так, чтобы `cinterop` к libmongoc был написан один раз
и спрятан за `suspend fun insertOne(...)` и `fun find(...): Flow<Document>`.

**Статус: рабочий прототип.** Шесть операций (`insertOne`, `insertMany`, `updateOne`,
`deleteOne`, `countDocuments`, `find`), типизированные коллекции и infix-DSL, 81 тест — включая дифференциальные против
официального драйвера, стресс-тесты пула соединений, проверку утечек через подменённый
аллокатор libbson и property-тесты кодека. Форма API снята с официального `mongodb-driver-kotlin-coroutine`, а совпадение с ним проверяется
**дифференциальными тестами**: один и тот же документ проходит через официальный JVM-драйвер
и через mongkn на одном mongod, и результаты сверяются в обе стороны.

Генерация API через KSP была и от неё отказались — обоснование в
[ресёрче](docs/research/research-architecture.md), решение Р9. Дальше — раннер spec-тестов
MongoDB и стресс-тест пула, см. [BACKLOG.md](BACKLOG.md).

```kotlin
@Serializable
data class Person(val name: String, val born: Int)

fun main() = runBlocking {
    MongoClient("mongodb://127.0.0.1:27017").use { client ->
        val people = client.getDatabase("app").getCollection<Person>("people")

        people.insertOne(Person("Ada", 1815))
        people.find(Person::born lt 1900).collect { println(it) }
    }
}
```

Документы можно и без маппинга — `getCollection("people")` даёт `MongoCollection<Document>`.

## Модули

| Модуль | Что делает |
|---|---|
| `mongkn-core` | Kotlin/Native: cinterop, BSON, клиент, операции |
| `mongkn-extensions` | Kotlin/Native: infix-DSL фильтров и обновлений |
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

## Подключение

```kotlin
repositories {
    maven("https://maven.internal/private") {
        credentials {
            username = System.getenv("REPOSILITE_USER")
            password = System.getenv("REPOSILITE_SECRET")
        }
    }
}

dependencies {
    implementation("ru.workinprogress.mongkn:mongkn-core:0.1.0-SNAPSHOT")
    implementation("ru.workinprogress.mongkn:mongkn-extensions:0.1.0-SNAPSHOT") // infix-DSL, по желанию
}
```

Публикация:

```bash
./gradlew publishAllPublicationsToReposilitePrivateRepository
```

## Тесты

```bash
./gradlew build
```

Нужен локальный mongod и — на первом прогоне — сеть: официальные spec-тесты MongoDB
скачиваются в `build/` и в репозиторий не кладутся ([CC BY-NC-SA](https://github.com/mongodb/specifications)).

## Документация

[docs/](docs/README.md) — слоёная документация. Начинать с
[research-architecture](docs/research/research-architecture.md): там разобрано, почему
архитектура именно такая и где исходный замысел пришлось поменять.
