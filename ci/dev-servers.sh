#!/usr/bin/env bash
# Поднимает и настраивает все тестовые серверы mongkn.
#
# Compose описывает контейнеры, но не умеет то, что делается **после** старта: инициировать
# replica set, завести пользователей, собрать шардированный кластер. Поэтому обёртка:
# `docker compose up` плюс команды, каждая из которых обязательна и ни одна не очевидна.
#
# Использование:
#   ci/dev-servers.sh up     — поднять и настроить
#   ci/dev-servers.sh down   — остановить и убрать
#   ci/dev-servers.sh env    — напечатать адреса для тестов (eval "$(ci/dev-servers.sh env)")
#
# MONGKN_PORT_OFFSET сдвигает все порты, MONGKN_PREFIX — имена контейнеров. Нужно, чтобы прогон
# CI и контур разработчика уживались на одной машине.
#
# На x86-хосте (включая WSL2) заранее задайте MONGKN_PLATFORM=linux/amd64 — умолчание в compose
# рассчитано на arm64, и на чужой архитектуре образы уйдут в эмуляцию.
set -euo pipefail

cd "$(dirname "$0")/.."
CERTS="$PWD/build/tls"

# Разведение контуров: на одной машине живёт и контур разработчика, и контур прогона CI —
# self-hosted раннер в WSL стоит там же, где работают руками. Без сдвига второй контур
# не поднимется («port is already allocated») либо, что хуже, тесты пойдут в чужие серверы
# и будут ронять друг другу данные.
#
# Сдвигаются и порты, и имена контейнеров: одного порта мало, `docker exec "$PREFIX-it"` нашёл бы
# чужой контейнер.
OFFSET="${MONGKN_PORT_OFFSET:-0}"
PREFIX="${MONGKN_PREFIX:-mongkn}"

# Имя проекта compose — тоже своё, и это не косметика: `docker compose down` сносит **проект**,
# а не контейнеры по именам. Пока проект был общим, остановка сдвинутого контура уносила
# с собой контур разработчика — проверено, ровно так и случилось.
export COMPOSE_PROJECT_NAME="$PREFIX"
export MONGKN_PREFIX="$PREFIX"
export MONGKN_PORT_RS=$((27017 + OFFSET))
export MONGKN_PORT_AUTH=$((27019 + OFFSET))
export MONGKN_PORT_TLS=$((27020 + OFFSET))
export MONGKN_PORT_MONGOS=$((27021 + OFFSET))

wait_for() {
  for _ in $(seq 1 40); do
    docker exec "$1" mongosh --quiet ${2:-} --eval 'db.runCommand({ping:1})' >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "не дождались $1" >&2
  return 1
}

case "${1:-up}" in
  up)
    # Сертификаты нужны compose'у ещё до старта — он их монтирует.
    [ -f "$CERTS/server.pem" ] || ./ci/tls/generate.sh "$CERTS" >/dev/null
    docker compose up -d

    wait_for "$PREFIX-it" "--port $MONGKN_PORT_RS"
    # Адрес члена — явный. Иначе сервер объявит себя под hostname контейнера, и драйвер,
    # выполнив обнаружение топологии, уйдёт по недостижимому адресу.
    docker exec "$PREFIX-it" mongosh --quiet --port "$MONGKN_PORT_RS" --eval \
      "try { rs.initiate({_id:'rs0',members:[{_id:0,host:'127.0.0.1:$MONGKN_PORT_RS'}]}) } catch (e) {}" >/dev/null
    for _ in $(seq 1 30); do
      docker exec "$PREFIX-it" mongosh --quiet --port "$MONGKN_PORT_RS" \
        --eval 'quit(db.hello().isWritablePrimary ? 0 : 1)' && break
      sleep 2
    done

    wait_for "$PREFIX-auth" "--port $MONGKN_PORT_AUTH -u mongkn_test -p mongkn_secret --authenticationDatabase admin"
    # Второй пользователь — с паролем из символов, значащих для URI. Ради теста на процентное
    # кодирование: это самая частая причина «пароль верный, а не пускает».
    docker exec "$PREFIX-auth" mongosh --quiet --port "$MONGKN_PORT_AUTH" -u mongkn_test -p mongkn_secret \
      --authenticationDatabase admin --eval \
      "try { db.getSiblingDB('admin').createUser({user:'mongkn_odd', pwd:'p@ss:w/rd?#1', roles:[{role:'root',db:'admin'}]}) } catch (e) {}" >/dev/null

    wait_for "$PREFIX-tls" "--port $MONGKN_PORT_TLS --tls --tlsCAFile /etc/mongo-tls/ca.pem"
    # Имя пользователя x509 — subject сертификата в RFC2253, посимвольно как его видит mongod.
    SUBJECT="$(cat "$CERTS/client-subject.txt")"
    docker exec "$PREFIX-tls" mongosh --quiet --port "$MONGKN_PORT_TLS" --tls --tlsCAFile /etc/mongo-tls/ca.pem --eval \
      "try { db.getSiblingDB('\$external').runCommand({createUser: '$SUBJECT', roles: [{role:'root', db:'admin'}]}) } catch (e) {}" >/dev/null

    # Шардированный кластер: три `rs.initiate` и два `addShard` внутри контейнера.
    # Вынесено отдельным скриптом, потому что тем же скриптом кластер собирается в CI
    # на macOS-раннере, где docker недоступен и процессы запускаются напрямую.
    MONGKN_SHARD_EXEC="docker exec $PREFIX-shard" ./ci/shard/init.sh

    echo "серверы готовы: $MONGKN_PORT_RS (replica set), $MONGKN_PORT_AUTH (SCRAM), \
$MONGKN_PORT_TLS (TLS), $MONGKN_PORT_MONGOS (mongos)"
    ;;
  env)
    # Адреса для тестов одной строкой: `eval "$(ci/dev-servers.sh env)"`. Тесты берут их
    # из окружения (см. support/TestServer.kt), и печатать их здесь надёжнее, чем повторять
    # арифметику сдвига в каждом вызывающем месте.
    echo "export MONGKN_TEST_HOST=127.0.0.1:$MONGKN_PORT_RS"
    echo "export MONGKN_TEST_AUTH_HOST=127.0.0.1:$MONGKN_PORT_AUTH"
    echo "export MONGKN_TEST_TLS_HOST=127.0.0.1:$MONGKN_PORT_TLS"
    echo "export MONGKN_TEST_SHARD_HOST=127.0.0.1:$MONGKN_PORT_MONGOS"
    ;;
  down)
    docker compose down -v
    ;;
  *)
    echo "использование: $0 [up|down|env]" >&2
    exit 1
    ;;
esac
