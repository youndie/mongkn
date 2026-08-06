#!/usr/bin/env bash
# Прогон сборки и тестов под Linux (libmongoc 1.26) против серверов, поднятых на хосте.
#
# Главное здесь — проброс портов внутрь контейнера. Одноузловой реплика-сет объявляет себя
# как `127.0.0.1:27017`, и драйвер, выполнив обнаружение топологии, идёт именно по этому адресу.
# Изнутри контейнера `127.0.0.1` — сам контейнер, поэтому без проброса тесты с `replicaSet=rs0`
# не находят сервера. Проброс делает адрес одинаковым с обеих сторон, и переменные окружения
# с адресами не нужны вовсе: прогон под Linux выглядит ровно как прогон на хосте.
#
# Адреса серверов передаются явно и со сдвигом MONGKN_PORT_OFFSET — тем же, что у
# ci/dev-servers.sh. Без этого при сдвинутом контуре тесты пошли бы на умолчания (27017…)
# и не нашли бы серверов.
#
# Использование: ci/linux-build.sh [задачи gradle…]
set -euo pipefail

cd "$(dirname "$0")/.."
TASKS="${*:-:mongkn-core:build}"

OFFSET="${MONGKN_PORT_OFFSET:-0}"

docker run --rm --platform linux/amd64 \
  -v "$PWD":/src \
  -v mongkn-gradle-amd64:/gradle \
  -v mongkn-konan-amd64:/konan \
  -e MONGKN_TEST_HOST=127.0.0.1:$((27017 + OFFSET)) \
  -e MONGKN_TEST_AUTH_HOST=127.0.0.1:$((27019 + OFFSET)) \
  -e MONGKN_TEST_TLS_HOST=127.0.0.1:$((27020 + OFFSET)) \
  -e MONGKN_TEST_SHARD_HOST=127.0.0.1:$((27021 + OFFSET)) \
  mongkn-ci:amd64 sh -c "
    # 27021 — mongos шардированного контура (M-66). Добавлен позже остальных: на Linux-хосте
    # проброс не нужен вовсе, и без него тесты шарда молча не находят кластер.
    #
    # Порты берутся со сдвигом MONGKN_PORT_OFFSET — тем же, что у ci/dev-servers.sh, иначе
    # проброс уткнётся в порты чужого контура.
    for port in \$((27017 + ${MONGKN_PORT_OFFSET:-0})) \$((27019 + ${MONGKN_PORT_OFFSET:-0})) \
                \$((27020 + ${MONGKN_PORT_OFFSET:-0})) \$((27021 + ${MONGKN_PORT_OFFSET:-0})); do
      socat TCP-LISTEN:\$port,fork,reuseaddr,bind=127.0.0.1 TCP:host.docker.internal:\$port &
    done
    sleep 1
    ./gradlew $TASKS
  "
