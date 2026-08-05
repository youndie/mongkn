#!/usr/bin/env bash
# Поднимает и настраивает все тестовые серверы mongkn.
#
# Compose описывает контейнеры, но не умеет то, что делается **после** старта: инициировать
# replica set, завести пользователей. Поэтому обёртка: `docker compose up` плюс четыре команды,
# каждая из которых обязательна и ни одна не очевидна.
#
# Использование:
#   ci/dev-servers.sh up     — поднять и настроить
#   ci/dev-servers.sh down   — остановить и убрать
set -euo pipefail

cd "$(dirname "$0")/.."
CERTS="$PWD/build/tls"

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

    wait_for mongkn-it
    # Адрес члена — явный. Иначе сервер объявит себя под hostname контейнера, и драйвер,
    # выполнив обнаружение топологии, уйдёт по недостижимому адресу.
    docker exec mongkn-it mongosh --quiet --eval \
      "try { rs.initiate({_id:'rs0',members:[{_id:0,host:'127.0.0.1:27017'}]}) } catch (e) {}" >/dev/null
    for _ in $(seq 1 30); do
      docker exec mongkn-it mongosh --quiet --eval 'quit(db.hello().isWritablePrimary ? 0 : 1)' && break
      sleep 2
    done

    wait_for mongkn-auth "-u mongkn_test -p mongkn_secret --authenticationDatabase admin"
    # Второй пользователь — с паролем из символов, значащих для URI. Ради теста на процентное
    # кодирование: это самая частая причина «пароль верный, а не пускает».
    docker exec mongkn-auth mongosh --quiet -u mongkn_test -p mongkn_secret \
      --authenticationDatabase admin --eval \
      "try { db.getSiblingDB('admin').createUser({user:'mongkn_odd', pwd:'p@ss:w/rd?#1', roles:[{role:'root',db:'admin'}]}) } catch (e) {}" >/dev/null

    wait_for mongkn-tls "--tls --tlsCAFile /etc/mongo-tls/ca.pem"
    # Имя пользователя x509 — subject сертификата в RFC2253, посимвольно как его видит mongod.
    SUBJECT="$(cat "$CERTS/client-subject.txt")"
    docker exec mongkn-tls mongosh --quiet --tls --tlsCAFile /etc/mongo-tls/ca.pem --eval \
      "try { db.getSiblingDB('\$external').runCommand({createUser: '$SUBJECT', roles: [{role:'root', db:'admin'}]}) } catch (e) {}" >/dev/null

    echo "серверы готовы: 27017 (replica set), 27019 (SCRAM), 27020 (TLS)"
    ;;
  down)
    docker compose down -v
    ;;
  *)
    echo "использование: $0 [up|down]" >&2
    exit 1
    ;;
esac
