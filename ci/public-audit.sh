#!/usr/bin/env bash
#
# Проверка перед публикацией репозитория наружу.
#
# Отвечает на один вопрос: **не уедет ли вместе с кодом то, чему снаружи не место** — имена
# внутренних проектов, адреса инфраструктуры, ключи, учётные данные.
#
# Проверяются две разные вещи, и вторая важнее:
#
# * **рабочее дерево** — то, что видно сразу. Правится обычным коммитом;
# * **вся история** — то, что видно любому, кто сделает `git clone`. Правится только
#   перезаписью истории, а после публикации не правится уже никак: форки и кэши остаются.
#
# Выход ненулевой, если что-то нашлось. Находка не обязательно проблема — скрипт не знает,
# что у вас секрет, а что нет; он показывает, где смотреть.
#
# Использование:
#   ./ci/public-audit.sh                     # проверить текущий репозиторий
#   MARKERS="foo bar" ./ci/public-audit.sh   # свой список внутренних имён

set -uo pipefail

cd "$(dirname "$0")/.."

# Имена внутренних проектов и инфраструктуры. Дополняйте под свой контур: скрипт не угадает,
# какие слова у вас внутренние.
# Регулярные выражения, а не просто слова: «10.0.» иначе ловит номера версий, а не адреса.
MARKERS="${MARKERS:-внутренний сервис projectC projectB org projectA projectD projectE projectF maven kotlin[.]website 192[.]168[.][0-9] 10[.][0-9]+[.][0-9]+[.][0-9]+ user@}"

# Признаки утёкших значений. Именно значений: имена переменных вроде REPOSILITE_SECRET —
# не находка, они и должны быть в коде.
SECRET_PATTERNS='(AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|gh[pousr]_[A-Za-z0-9]{20,}|BEGIN [A-Z ]*PRIVATE KEY|xox[baprs]-[0-9A-Za-z-]{10,}|-----BEGIN CERTIFICATE-----)'

# Файлы, которых в репозитории быть не должно ни в одном коммите.
FORBIDDEN_FILES='\.(pem|key|p12|jks|keystore|env)$|(^|/)(id_rsa|id_ed25519|\.netrc|\.npmrc)$'

found=0

section() {
    echo
    echo "── $1"
}

# Только файлы под контролем git: каталог сборки наружу не уезжает, а искать в нём —
# гарантированный шум. Сам скрипт исключён: список маркеров в нём и лежит.
tracked() { git ls-files | grep -v '^ci/public-audit.sh$'; }

section "Рабочее дерево: внутренние имена"
for marker in $MARKERS; do
    hits=$(tracked | tr '\n' '\0' | xargs -0 grep -lE -- "$marker" 2>/dev/null)
    if [ -n "$hits" ]; then
        found=1
        echo "  «$marker»:"
        echo "$hits" | sed 's/^/    /'
    fi
done
[ "$found" = 0 ] && echo "  чисто"

section "История: внутренние имена"
history_hits=0
for marker in $MARKERS; do
    commits=$(git log --all -S"$marker" --oneline 2>/dev/null | wc -l | tr -d ' ')
    messages=$(git log --all --grep="$marker" --oneline 2>/dev/null | wc -l | tr -d ' ')
    if [ "$commits" != "0" ] || [ "$messages" != "0" ]; then
        history_hits=1
        found=1
        echo "  «$marker»: в содержимом $commits коммитов, в сообщениях $messages"
    fi
done
[ "$history_hits" = 0 ] && echo "  чисто"

section "История: похожее на утёкшие значения"
leaks=$(git log -p --all 2>/dev/null | grep -nE "^\+.*$SECRET_PATTERNS" | head -20)
if [ -n "$leaks" ]; then
    found=1
    echo "$leaks" | cut -c1-160 | sed 's/^/  /'
else
    echo "  чисто"
fi

section "История: файлы, которым здесь не место"
files=$(git log --all --diff-filter=A --name-only --format="" 2>/dev/null | sort -u | grep -E "$FORBIDDEN_FILES")
if [ -n "$files" ]; then
    found=1
    echo "$files" | sed 's/^/  /'
else
    echo "  чисто"
fi

section "Обязательное для публичного репозитория"
[ -f LICENSE ] || { echo "  нет LICENSE"; found=1; }
grep -q "self-hosted" .github/workflows/*.yml 2>/dev/null && {
    echo "  self-hosted раннер в workflow — на публичном репозитории это исполнение чужого кода"
    echo "  на своей машине: PR из форка запускает то, что в нём написано"
    found=1
}
[ "$found" = 0 ] && echo "  всё на месте"

section "Авторы коммитов (попадут в публичную историю)"
git log --format='%an <%ae>' | sort | uniq -c | sed 's/^/  /'

echo
if [ "$found" = 0 ]; then
    echo "Находок нет."
else
    echo "Есть находки — разберите каждую до публикации."
    echo "Правки в дереве закрываются коммитом; правки в истории — только её перезаписью,"
    echo "и только ДО того, как репозиторий стал публичным."
fi
exit "$found"
