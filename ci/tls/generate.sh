#!/usr/bin/env bash
# Сертификаты для проверки TLS и x509 (M-75).
#
# Генерируются, а **не хранятся в репозитории**, по двум причинам: сертификат протухает, и через
# год закоммиченный набор превратил бы зелёный тест в красный без единой правки кода; а ключ
# в репозитории — плохая привычка, даже когда он одноразовый.
#
# Сюда же встроено единственное неочевидное место всей затеи: имя пользователя x509 — это
# subject сертификата в формате RFC2253, и оно должно совпасть с тем, что вычислит mongod,
# посимвольно. Поэтому оно не пишется руками, а печатается openssl (см. вывод в конце).
set -euo pipefail

OUT="${1:-build/tls}"
DAYS=3650

# Серверный сертификат — короткоживущий, и это не перестраховка.
#
# На macOS mongo-c-driver собран с Secure Transport, а тот применяет политику Apple: серверный
# сертификат со сроком больше ~398 дней отвергается независимо от того, доверяем мы центру
# или нет. Проявляется как `CSSMERR_TP_CERT_SUSPENDED` при рукопожатии — по тексту не догадаться,
# что дело в сроке. На Linux с OpenSSL таких сертификатов никто бы не заметил.
#
# Отсюда же довод в пользу генерации вместо хранения в репозитории: этот набор протухает
# через год, и закоммиченный однажды сломал бы сборку без единой правки кода.
SERVER_DAYS=397
mkdir -p "$OUT"
cd "$OUT"

# Удостоверяющий центр.
openssl req -x509 -newkey rsa:2048 -days "$DAYS" -nodes \
  -keyout ca.key -out ca.pem \
  -subj "/C=RU/O=mongkn/CN=mongkn-test-ca" 2>/dev/null

# Сервер. SAN обязателен: mongod проверяет имя, под которым к нему пришли, и без записи
# и для 127.0.0.1, и для имени в docker-сети один из двух контуров не заработает.
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
  -subj "/C=RU/O=mongkn-server/CN=mongkn-test-server" 2>/dev/null
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -days "$SERVER_DAYS" -out server.crt \
  -extfile <(printf "subjectAltName=DNS:localhost,DNS:mongkn-ci-tls,IP:127.0.0.1\nextendedKeyUsage=serverAuth\nkeyUsage=digitalSignature,keyEncipherment\n") 2>/dev/null
cat server.key server.crt > server.pem

# Клиент — под аутентификацию MONGODB-X509.
#
# Организация (O) обязана отличаться от серверной. Совпадут O, OU и DC — и mongod сочтёт
# такой сертификат членом кластера, а не пользователем: "Cannot create an x.509 user with
# a subjectname that would be recognized as an internal cluster member". Ошибка вылезает
# не при подключении, а при заведении пользователя, и по тексту неочевидно, что дело в O.
openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr \
  -subj "/C=RU/O=mongkn-clients/OU=x509/CN=mongkn-x509" 2>/dev/null
openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -days "$DAYS" -out client.crt \
  -extfile <(printf "extendedKeyUsage=clientAuth\nkeyUsage=digitalSignature\n") 2>/dev/null
cat client.key client.crt > client.pem

# Второй клиент, которого на сервере не заводят, — для отрицательной проверки: без него тест
# «x509 работает» не отличался бы от «сервер пускает кого угодно».
openssl req -newkey rsa:2048 -nodes -keyout unknown.key -out unknown.csr \
  -subj "/C=RU/O=mongkn-clients/OU=x509/CN=mongkn-unknown" 2>/dev/null
openssl x509 -req -in unknown.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -days "$DAYS" -out unknown.crt \
  -extfile <(printf "extendedKeyUsage=clientAuth\nkeyUsage=digitalSignature\n") 2>/dev/null
cat unknown.key unknown.crt > unknown.pem

chmod 600 ./*.key ./*.pem

# Имя пользователя x509 — ровно эта строка, и ничто другое.
openssl x509 -in client.crt -noout -subject -nameopt RFC2253 | sed 's/^subject=//' > client-subject.txt
echo "сертификаты: $(pwd)"
echo "subject клиента: $(cat client-subject.txt)"
