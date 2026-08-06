#!/usr/bin/env bash
# Доводит запущенный шардированный кластер до рабочего состояния: три `rs.initiate` и два
# `addShard`. Ровно то, чего не умеет ни compose, ни `docker run`, — и ровно та причина,
# по которой у тестовых серверов вообще есть обёртка (см. ci/dev-servers.sh).
#
# Идемпотентен: повторный запуск на настроенном кластере ничего не ломает — и `rs.initiate`,
# и `addShard` на уже сделанной работе отвечают ошибкой, которую мы гасим осознанно.
#
# Как звать mongosh, задаёт MONGKN_SHARD_EXEC: в контейнере это `docker exec mongkn-shard`,
# на машине без docker (macOS-раннер) — пусто. Раскладка портов у обоих случаев одна,
# поэтому и команды одни и те же (см. ci/shard/start.sh).
set -euo pipefail

# Порты берутся из окружения со сдвигом MONGKN_PORT_OFFSET. Внутри контейнера сдвиг не нужен
# и равен нулю, а вот на машине **без** docker (macOS-раннер) все четыре процесса живут
# на хостовых портах — и там контур прогона обязан разъехаться с контуром разработчика.
# Сдвиг применяется всегда — и внутри контейнера тоже: адрес члена реплика-сета обязан быть
# одним и тем же с обеих сторон, поэтому порт сдвигается везде, а публикуется один в один.
EXEC="${MONGKN_SHARD_EXEC:-}"
OFFSET="${MONGKN_PORT_OFFSET:-0}"
CONFIG_PORT=$((27031 + OFFSET))
SHARD_A_PORT=$((27032 + OFFSET))
SHARD_B_PORT=$((27033 + OFFSET))
CLUSTER_PORT=$((27021 + OFFSET))

evaluate() {
  # Без кавычек вокруг $EXEC намеренно: это префикс команды из нескольких слов, а не одно имя.
  # shellcheck disable=SC2086
  $EXEC mongosh --quiet --port "$1" --eval "$2"
}

wait_for_port() {
  local port="$1" what="$2"
  for _ in $(seq 1 45); do
    evaluate "$port" 'db.runCommand({ping:1})' >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "не дождались $what на порту $port" >&2
  return 1
}

wait_for_primary() {
  local port="$1" what="$2"
  for _ in $(seq 1 45); do
    evaluate "$port" 'quit(db.hello().isWritablePrimary ? 0 : 1)' >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "$what не стал первичным на порту $port" >&2
  return 1
}

# Адрес члена задаётся явно, как и в одноузловом replica set основного контура: без этого
# сервер объявит себя под hostname контейнера, и mongos пойдёт по недостижимому адресу.
initiate() {
  local port="$1" name="$2" extra="$3"
  wait_for_port "$port" "$name"
  evaluate "$port" \
    "try { rs.initiate({_id:'$name',$extra members:[{_id:0,host:'127.0.0.1:$port'}]}) } catch (e) {}" >/dev/null
  wait_for_primary "$port" "$name"
}

initiate "$CONFIG_PORT" mongkn-cfg 'configsvr:true,'
initiate "$SHARD_A_PORT" mongkn-a ''
initiate "$SHARD_B_PORT" mongkn-b ''

# mongos отвечает на ping и до того, как узнает про конфигурацию, поэтому ждём не его,
# а успешного listShards — единственного признака, что кластер собран.
wait_for_port "$CLUSTER_PORT" mongos
evaluate "$CLUSTER_PORT" \
  "try { sh.addShard('mongkn-a/127.0.0.1:$SHARD_A_PORT') } catch (e) {}
   try { sh.addShard('mongkn-b/127.0.0.1:$SHARD_B_PORT') } catch (e) {}" >/dev/null

# Коллекции `config.system.sessions` здесь нет, и заводить её не нужно — проверено:
# `refreshLogicalSessionCacheNow` на свежем кластере отвечает «Session collection is not sharded»
# (её шардирует балансировщик, когда дойдут руки), а транзакции и `startSession` работают
# и без неё. Строчка «на всякий случай» тут выглядела бы разумной и ломала бы поднятие контура.

shards="$(evaluate "$CLUSTER_PORT" 'print(db.adminCommand({listShards:1}).shards.length)' | tr -d '[:space:]')"
if [ "$shards" != "2" ]; then
  echo "шардов в кластере $shards, а нужно 2" >&2
  exit 1
fi
