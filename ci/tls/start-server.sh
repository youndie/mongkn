#!/usr/bin/env bash
# Поднимает mongod, требующий TLS, и заводит пользователя x509 (M-75).
#
# Оформлено скриптом, а не строчкой в документации, потому что шагов четыре и три из них
# неочевидны:
#
# 1. сертификаты **копируются внутрь** контейнера, а не монтируются: mongod не станет читать
#    ключ, доступный кому попало, а права смонтированного файла задаёт хост;
# 2. владелец меняется на `mongodb` уже внутри — `docker cp` кладёт файлы от root, и mongod
#    падает с "Permission denied" на собственном ключе;
# 3. `--tlsAllowConnectionsWithoutCertificates` обязателен. Указание `--tlsCAFile` само по себе
#    делает клиентский сертификат **обязательным**, и обычное TLS-подключение без него сервер
#    молча закрывает — снаружи это выглядит как "socket error or timeout", а не как отказ;
# 4. пользователь x509 заводится до включения проверок и **под тем самым subject**, который
#    напечатал generate.sh.
#
# Использование: ci/tls/start-server.sh <имя-контейнера> <каталог-сертификатов> [docker-аргументы…]
set -euo pipefail

NAME="$1"; CERTS="$2"; shift 2

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker create --name "$NAME" "$@" --entrypoint sh mongo:8 -c '
  chown -R mongodb:mongodb /etc/mongo-tls && chmod 400 /etc/mongo-tls/*
  exec docker-entrypoint.sh mongod \
    --tlsMode requireTLS \
    --tlsCertificateKeyFile /etc/mongo-tls/server.pem \
    --tlsCAFile /etc/mongo-tls/ca.pem \
    --tlsAllowConnectionsWithoutCertificates \
    --setParameter enableTestCommands=1' >/dev/null
docker cp "$CERTS/." "$NAME":/etc/mongo-tls/ >/dev/null
docker start "$NAME" >/dev/null

for _ in $(seq 1 30); do
  docker exec "$NAME" mongosh --quiet --tls --tlsCAFile /etc/mongo-tls/ca.pem \
    --host localhost --eval 'db.runCommand({ping:1})' >/dev/null 2>&1 && break
  sleep 2
done

SUBJECT="$(cat "$CERTS/client-subject.txt")"
docker exec "$NAME" mongosh --quiet --tls --tlsCAFile /etc/mongo-tls/ca.pem \
  --host localhost --eval \
  "db.getSiblingDB('\$external').runCommand({createUser: '$SUBJECT', roles: [{role:'root', db:'admin'}]})" >/dev/null
echo "$NAME готов, пользователь x509: $SUBJECT"
