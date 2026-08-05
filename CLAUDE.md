# CLAUDE.md — mongkn

Kotlin/Native обвязка над MongoDB C-драйвером (`libmongoc`). Чистый Kotlin/Native: `java.*`
и `org.bson.*` здесь недоступны физически.

## С чего начинать сессию

1. [docs/research/research-architecture.md](docs/research/research-architecture.md) — решения
   и их обоснования. Без этого файла задачи выглядят как «сделай очевидное», а очевидное здесь
   трижды неверно:
   - линковаться надо `-lmongoc2`, а не `-lmongoc-1.0`, и заголовки лежат в **версионированном**
     каталоге — поэтому пути разрешаются в Gradle, а не в `.def` (§1.1);
   - `withContext(Dispatchers.Default)` вокруг вызова драйвера — undefined behaviour, а не
     неоптимальность: `mongoc_client_t` не потокобезопасен, а на Kotlin/Native этот диспетчер
     многопоточный (§1.4). `Dispatchers.IO` тоже не выход — на Kotlin/Native он `internal`,
     вопреки собственной документации, поэтому клиент держит свой пул потоков (§1.8);
   - `mongoc_init()` **не** восстанавливает драйвер после `mongoc_cleanup()`: жизненный цикл
     одноразовый на процесс, `MongoClient.close()` намеренно не зовёт `Mongkn.shutdown()` (§1.8);
   - генерации API **больше нет** (решение Р9, M-33): она гарантировала форму, а не поведение,
     и стоила двух JVM-модулей на критическом пути. `MongoCollection` рукописный, его KDoc несёт
     правила снятия формы — в частности, ловушку с двумя одноимёнными перегрузками `updateOne`;
   - официальный JVM-драйвер остался в `:mongkn-difftest` как **эталон**: дифференциальные тесты
     идут тремя фазами вокруг одного mongod, потому что JVM и Native в одном процессе не сводятся.
2. [BACKLOG.md](BACKLOG.md) — что делать. Закрыты вехи M0–M5а. Порядок дальше:
   **M6 (проверки корректности) → M7 (эргономика) → M8 (выпуск)** — номер вехи не приоритет.
   Приоритет сместился на проверки не случайно: почти все утверждения о корректности сегодня
   держатся на ожиданиях автора, а не на эталоне.
3. [docs/coverage.md](docs/coverage.md) — что уже умеет библиотека, а что нет. Отвечает
   на «можно ли этим пользоваться» цифрами, а не ощущением.
4. Документ слоя, к которому относится задача: [docs/features/](docs/features/),
   [docs/api/](docs/api/), [docs/services/](docs/services/).

## Сборка

```bash
./gradlew build
```

Предусловие — установленный C-драйвер (`brew install mongo-c-driver`). Если он лежит не в
`/opt/homebrew`, `/usr/local` или `/usr`, укажите `-Pmongkn.prefix=<префикс>`.

Собирается **только хостовый таргет**: `cinterop` требует заголовков целевой платформы.
Это решение (Р6), а не недоделка.

Проверить Linux локально (там libmongoc **1.x**, а на macOS 2.x — обе ветки должны работать):

```bash
docker build -t mongkn-ci ci/
docker run --rm --platform linux/amd64 --network mongkn-ci -v "$PWD":/src \
  -e MONGKN_TEST_HOST=mongkn-ci-db:27017 -e MONGKN_TEST_AUTH_HOST=mongkn-ci-auth:27017 \
  mongkn-ci ./gradlew build
```

`--platform linux/amd64` обязателен: Kotlin/Native не компилирует на хосте linux-aarch64 (§1.18).

Интеграционным тестам нужен локальный mongod — **без него они падают, а не пропускаются**.
Поднимать его надо **одноузловым replica set**, а не standalone: change streams на standalone
не работают вовсе, и `ChangeStreamTest` будет падать. Для остальных операций разницы нет.

```bash
docker run -d --name mongkn-it --platform linux/arm64 -p 27017:27017 mongo:8 --replSet rs0 --bind_ip_all --setParameter enableTestCommands=1
```

`enableTestCommands=1` нужен failpoint'ам: `RetryTest` заказывает серверу сбой через
`failCommand`, и без этого параметра сервер откажется.

`--platform` под архитектуру хоста — не украшение. Однажды образ уже оказался `linux/amd64`
на arm-машине, то есть mongod работал под эмуляцией QEMU: прогон тестов шёл вдвое дольше,
а замеры производительности выдавали заведомую чушь. Проверить:
`docker image inspect mongo:8 --format '{{.Architecture}}'`.

```bash
docker exec mongkn-it mongosh --quiet --eval "rs.initiate({_id:'rs0',members:[{_id:0,host:'127.0.0.1:27017'}]})"
```

Адрес члена задаётся **явно**: иначе сервер объявит себя под своим hostname, драйвер пойдёт
по объявленному адресу и не достучится. Для сервера в docker-сети (`mongkn-ci-db`) подставьте
его имя вместо `127.0.0.1`.

Отдельно нужен **второй** сервер — с аутентификацией, на порту 27019. Отдельный, а не тот же
самый: включи мы `--auth` на основном, креды понадобились бы каждому тесту, и `AuthenticationTest`
проверял бы не аутентификацию, а общий фон. Логин и пароль здесь — фикстура одноразового
контейнера, а не секрет; настоящие креды лежат в `~/.zshrc` и в репозиторий не попадают.

```bash
docker run -d --name mongkn-auth -p 27019:27017 -e MONGO_INITDB_ROOT_USERNAME=mongkn_test -e MONGO_INITDB_ROOT_PASSWORD=mongkn_secret mongo:8
```

```bash
docker exec mongkn-auth mongosh --quiet -u mongkn_test -p mongkn_secret --authenticationDatabase admin --eval 'db.getSiblingDB("admin").createUser({user:"mongkn_odd", pwd:"p@ss:w/rd?#1", roles:[{role:"root", db:"admin"}]})'
```

Адрес переопределяется через `MONGKN_TEST_AUTH_HOST` — в docker-сети это `mongkn-ci-auth:27017`.

Ещё две грабли, стоившие по сборке каждая: source set'ы `nativeMain` / `nativeTest` **нельзя**
заводить руками (`by creating`) — их создаёт стандартный шаблон иерархии KMP, а ручной ломает
резолв; и имя теста в обратных кавычках на Kotlin/Native не может содержать запятую.

## Публикация

**Целевая платформа одна — `linuxX64`.** macOS-таргет не публикуется: он нужен для разработки
и для того, чтобы в CI проверялась ветка mongo-c-driver **2.x** (в Ubuntu только 1.x). Не удаляйте
macOS-джобу как «ненужный таргет» — вместе с ней исчезнет половина проверки решения Р1.

`ru.workinprogress.mongkn`, приватный Reposilite. Креды — `REPOSILITE_USER` и `REPOSILITE_SECRET`;
они лежат в `~/.zshrc`, то есть видны **только интерактивной** оболочке, и в репозитории их нет.

Сборка и публикация — из Linux-контейнера:

```bash
zsh -ic 'docker run --rm --platform linux/amd64 --network mongkn-ci \
  -v "$PWD":/src -v mongkn-gradle-amd64:/gradle -v mongkn-konan-amd64:/konan \
  -e REPOSILITE_USER -e REPOSILITE_SECRET mongkn-ci:amd64 \
  ./gradlew :mongkn-core:publishAllPublicationsToReposilitePrivateRepository'
```

Перед отправкой на сервер стоит прогнать `publishToMavenLocal` и посмотреть координаты:
`group` подпроектами не наследуется, и артефакты однажды уже уехали не туда.

## Переносимое знание

Практика обвязки C-библиотеки из Kotlin/Native, добытая на этом проекте, вынесена в скилл
`kotlin-native-cinterop` (`~/.claude/skills/`): `.def` и поиск библиотеки, владение памятью,
блокирующие вызовы и отменяемость, курсоры во `Flow`, сборка под Linux, сверка с эталоном.
Если беретесь за похожую обвязку — начните с него, а не с этого репозитория.

## Форматирование

ktlint в гейте: плагин вешает `ktlintCheck` на `check`, поэтому обычный `./gradlew build`
его прогоняет, и отдельной строки в CI не нужно. Починить формат:

```bash
./gradlew ktlintFormat
```

Версия форматтера задана явно — `ktlint = "1.8.0"` в каталоге; плагин лишь запускает CLI.
Стиль и длина строки — в `.editorconfig` (`ktlint_official`, 120 символов).

Две вещи ktlint не чинит сам и придётся править руками: **KDoc перед выражением** (не перед
объявлением) — в `.kts` это частый случай, меняйте на `/* */`; и **два KDoc подряд** —
обычно след неаккуратной правки.

## Замер производительности

Числа и методика — [docs/performance.md](docs/performance.md). Запуск:

```bash
./gradlew :mongkn-core:runBenchmarkReleaseExecutableMacosArm64
```

Бенчмарк — **отдельный release-исполняемый файл, а не тест**: тестовые бинарники Kotlin/Native
собираются в DEBUG, и снятые на них числа описывают отладочную сборку. Он входит в `assemble`,
то есть добавляет линковку к сборке при изменении тестовых исходников. Это осознанная цена:
невкомпилированный бенчмарк тихо сгниёт на первом же рефакторинге внутренних API — так уже
случилось бы при появлении `Target` в M14.

## Правило про документацию

> **`main` описывает то, что есть.**

Меняешь поведение, описанное в документе, — правь документ тем же PR. Проверяй утверждение
по источнику (заголовок C, прогон, артефакт в Maven Central), а не по соседнему документу.
В ресёрче прогоны, чтение заголовков и гипотезы разделены намеренно — не смешивай их при правке.
