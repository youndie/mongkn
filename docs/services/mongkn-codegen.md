---
id: mongkn-codegen
title: mongkn-codegen + mongkn-api-spec
type: service
status: active
module: ":mongkn-codegen", ":mongkn-api-spec"
tech_stack: [Kotlin/JVM, KSP, KotlinPoet]
owner: unassigned
depends_on:
  - mongodb-driver-kotlin-coroutine 5.9.1 (источник сигнатур)
publishes: []
---

# mongkn-codegen + mongkn-api-spec

## 1. Зона ответственности

Печатает публичную поверхность `MongoCollection` для `:mongkn-core`, снимая форму с официального
корутинного драйвера MongoDB.

Чем **не** занимается: не генерирует тела методов и не знает про cinterop. Всё, что трогает
указатели, живёт в рукописном
[CollectionOps](../../mongkn-core/src/nativeMain/kotlin/io/github/mongkn/CollectionOps.kt).
Граница проведена намеренно: генератор отвечает за форму, человек — за опасный код.

Главный инвариант: **имена операций, имена параметров, признак `suspend` и форма результата
читаются из jar, а не зашиты в генератор.** Зашиты только список поддержанных операций и таблица
перевода типов — и обе видны одним взглядом в
[MongoApiGeneratorProcessor](../../mongkn-codegen/src/main/kotlin/io/github/mongkn/codegen/MongoApiGeneratorProcessor.kt).

## 2. Почему два модуля, а не один

KSP-процессор не может обрабатывать модуль, в котором сам лежит. Поэтому:

* `:mongkn-codegen` — сам процессор (KSP API + KotlinPoet);
* `:mongkn-api-spec` — модуль, на котором процессор запускается. Его единственная ценность в том,
  что **на его classpath лежит официальный драйвер**, а значит его сигнатуры резолвятся.

В нативной компиляции JVM-jar на classpath не попадает — отсюда всё это устройство (ресёрч §1.5,
решение Р5).

## 3. Как устроено

| Файл | Что там |
|---|---|
| [MongoApiGeneratorProcessor.kt](../../mongkn-codegen/src/main/kotlin/io/github/mongkn/codegen/MongoApiGeneratorProcessor.kt) | выбор перегрузок, отбрасывание параметров, перевод типов, печать |
| [MongoApiGeneratorProvider.kt](../../mongkn-codegen/src/main/kotlin/io/github/mongkn/codegen/MongoApiGeneratorProvider.kt) | точка входа KSP |
| `mongkn-codegen/src/main/resources/META-INF/services/…SymbolProcessorProvider` | регистрация процессора |
| [mongkn-api-spec/build.gradle.kts](../../mongkn-api-spec/build.gradle.kts) | запуск KSP и отдача результата через конфигурацию `nativeApiSources` |

**Выбор перегрузки.** У официального `MongoCollection` каждая операция продублирована вариантом
с `ClientSession`, а `find` вдобавок имеет варианты с `Class<R>`. Берётся самая короткая
перегрузка без сессии.

**Отбрасывание параметров.** `*Options` и `Class<R>` выкидываются явным списком, а не теряются
в переводе типов: так видно, чего в нативном API нет. Незнакомый тип параметра **роняет сборку** —
молча отобразить его в `Document` значило бы тихо разойтись с официальным API.

## 4. Локальный запуск

```bash
./gradlew :mongkn-api-spec:kspKotlin
```

Результат — `mongkn-api-spec/build/generated/ksp/main/kotlin/io/github/mongkn/MongoCollection.kt`.

## 5. Сознательные ограничения / грабли

* **Сгенерированный код исключён из компиляции `:mongkn-api-spec`.** Он нативный и ссылается на
  `io.github.mongkn.*`, которых на JVM-classpath нет. KSP добавляет свой выходной каталог в source
  set **после** конфигурации build-файла, поэтому `setSrcDirs` не помогает — исключение сделано
  на уровне задачи компиляции.
* **Отдельного теста у генератора нет.** Если он напечатает не то или ничего, `:mongkn-core`
  не скомпилируется. Компиляция и есть проверка — заводить тест, дублирующий её, незачем.
* **`jvmToolchain` не зафиксирован.** Модуль существует только на время сборки; фиксированная
  17-я на машине с JDK 25 просто роняет конфигурацию, а репозиториев для скачивания тулчейнов
  здесь не заведено.
* **Плагины объявлены в корневом `build.gradle.kts` с `apply false`.** Иначе подпроект,
  объявляющий версию сам, получает «plugin is already on the classpath with an unknown version».
