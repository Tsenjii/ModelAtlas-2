#!/bin/sh
# Debian/Ubuntu systemd deployments. Never source secrets as shell code.
set -eu
umask 077
fail() { echo "ModelAtlas: $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || fail '請使用 sudo 執行。'
[ "$#" -eq 2 ] || fail '用法：install.sh 或 update.sh RELEASE_DIRECTORY（復原：manage.sh recover RELEASE_DIRECTORY）'
mode=$1
case "$mode" in install|update|recover) ;; *) fail '未知操作。' ;; esac
bundle=$(CDPATH= cd -P "$2" && pwd)
case "$(uname -m)" in x86_64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; *) fail '僅支援 Linux AMD64／ARM64。' ;; esac
[ "$(uname -s)" = Linux ] || fail '僅支援 Linux。'
for cmd in systemctl install sha256sum flock cmp useradd sync; do command -v "$cmd" >/dev/null 2>&1 || fail "缺少必要工具：$cmd"; done
# Also compare the outer archive digest with the trusted Actions artifact.
(cd "$bundle" && sha256sum --strict -c SHA256SUMS.txt >/dev/null) || fail '套件校驗失敗，既有服務未變更。'
candidate=$bundle/bin/modelatlas-vps-linux-$arch
[ -f "$candidate" ] && [ ! -L "$candidate" ] || fail '缺少對應架構的程式。'
chmod 755 "$candidate"
"$candidate" ops bundle-check --directory "$bundle" || fail '套件版本或架構不符。'

# These overrides also allow isolated acceptance against a genuine systemd.
root=${MODELATLAS_DEPLOY_ROOT:-/}
unit=${MODELATLAS_SERVICE:-modelatlas-vps}
case "$root" in /*) ;; *) fail '安裝根目錄必須為絕對路徑。' ;; esac
case "$root" in *[!a-zA-Z0-9/_-]*|*/../*|*/./*) fail '安裝根目錄格式錯誤。' ;; esac
case "$unit" in modelatlas-*) ;; *) fail '服務名稱必須以 modelatlas- 開頭。' ;; esac
case "$unit" in *[!a-z0-9-]*) fail '服務名稱格式錯誤。' ;; esac
root=${root%/}
data=$root/var/lib/modelatlas
etc=$root/etc/modelatlas
opt=$root/opt/modelatlas
backups=$root/var/backups/modelatlas
env=$etc/modelatlas.env
gate=$data/.update-pending
pending=$etc/update-pending
service=/etc/systemd/system/$unit.service
for dir in "$data" "$etc" "$opt" "$backups"; do [ ! -L "$dir" ] || fail '安裝目錄不可為符號連結。'; done
install -d -m 700 "$etc" "$backups"
exec 9>"$etc/deploy.lock"
flock -n 9 || fail '另一個安裝／更新正在進行。'
systemctl show-environment >/dev/null || fail '此主機沒有可用的 systemd；Git 平台請使用 build.sh／run.sh。'
[ -z "$(systemctl show "$unit.service" --property=DropInPaths --value 2>/dev/null || true)" ] || fail '請先將自訂 systemd drop-in 設定移至私密 env，避免套用錯誤的資料目錄。'
if ! id modelatlas >/dev/null 2>&1; then useradd --system --home "$data" --shell /usr/sbin/nologin modelatlas; fi
install -d -m 700 -o modelatlas -g modelatlas "$data"
install -d -m 755 "$opt"
if [ ! -f "$env" ]; then
  [ "$mode" = install ] && [ ! -f "$opt/modelatlas-vps" ] && [ ! -f "$data/atlas.sqlite" ] || fail '缺少原環境檔；請找回原 master key，不會產生替代金鑰。'
  "$candidate" ops init-env --env-file "$env" --default-db "$data/atlas.sqlite" --gate-file "$gate"
fi
[ ! -L "$env" ] || fail '環境檔不可為符號連結。'
chmod 600 "$env"
chown root:root "$env"
db=$("$candidate" ops database --env-file "$env" --default-db "$data/atlas.sqlite" --gate-file "$gate")
case "$db" in "$data"/*) ;; *) fail '此工具只管理安裝資料目錄內的 DB；其他平台路徑請依 UPDATE.md 使用離線 ops。' ;; esac

checkpoint() {
  printf '%s\n' "$1" >"$backup/phase.next" || return 1
  sync -f "$backup/phase.next" || return 1
  mv "$backup/phase.next" "$backup/phase" || return 1
  sync -f "$backup" || return 1
}
wait_previous() {
  count=0
  while [ "$count" -lt 30 ]; do
    if "$candidate" ops ready-previous --env-file "$env" --default-db "$data/atlas.sqlite" >/dev/null 2>&1; then return 0; fi
    count=$((count+1))
    sleep 1
  done
  return 1
}
recover_pending() {
  [ -f "$pending" ] || return 0
  stamp=$(cat "$pending")
  case "$stamp" in upgrade-*) ;; *) echo '更新日誌無效；保留所有檔案。' >&2; return 1 ;; esac
  case "$stamp" in *[!a-zA-Z0-9-]*) return 1 ;; esac
  backup=$backups/$stamp
  [ -d "$backup" ] && cmp -s "$env" "$backup/modelatlas.env" || { echo '環境檔已變動，未覆寫金鑰；請檢查更新備份。' >&2; return 1; }
  phase=$(cat "$backup/phase")
  case "$phase" in prepared|activating|restored|committed) ;; *) echo '更新階段不明，未覆寫任何資料。' >&2; return 1 ;; esac
  if [ "$phase" = committed ]; then
    rm -f "$gate"
    mv "$pending" "$backup/committed" || return 1
    return 0
  fi
  if [ "$phase" = restored ]; then
    rm -f "$gate"
    if [ -f "$backup/was-active" ]; then systemctl start "$unit.service" && wait_previous || return 1; fi
    mv "$pending" "$backup/recovered" || return 1
    return 0
  fi
  systemctl stop "$unit.service" >/dev/null 2>&1 || true
  if systemctl is-active --quiet "$unit.service"; then echo '服務未停止，未覆寫資料。' >&2; return 1; fi
  if [ "$phase" = activating ] && [ -f "$backup/database.sqlite" ]; then
    "$candidate" ops restore --env-file "$env" --default-db "$data/atlas.sqlite" --snapshot "$backup/database.sqlite" || return 1
    chown modelatlas:modelatlas "$db" "$db.lock" || return 1
  fi
  if [ -f "$backup/binary" ]; then
    install -m 755 "$backup/binary" "$opt/.restored-binary" || return 1
    mv -f "$opt/.restored-binary" "$opt/modelatlas-vps" || return 1
  fi
  if [ -f "$backup/unit" ]; then install -m 644 "$backup/unit" "$service" || return 1; fi
  systemctl daemon-reload || return 1
  checkpoint restored || return 1
  rm -f "$gate"
  if [ -f "$backup/was-active" ]; then systemctl start "$unit.service" && wait_previous || return 1; fi
  mv "$pending" "$backup/recovered" || return 1
  echo "已回復更新前的程式／資料，備份保留於 $backup"
}

if [ -f "$pending" ]; then recover_pending || fail '自動復原未完成，服務保持停止；更新日誌與資料全部保留。'; fi
[ "$mode" != recover ] || exit 0
[ "$mode" != update ] || [ -f "$opt/modelatlas-vps" ] || fail '尚未安裝，請先執行 install.sh。'
stamp=upgrade-$(date -u +%Y%m%dT%H%M%S)-$$
backup=$backups/$stamp
install -d -m 700 "$backup"
cp -p "$env" "$backup/modelatlas.env"
[ ! -f "$opt/modelatlas-vps" ] || cp -p "$opt/modelatlas-vps" "$backup/binary"
[ ! -f "$service" ] || cp -p "$service" "$backup/unit"
if systemctl is-active --quiet "$unit.service"; then : >"$backup/was-active"; fi
printf '%s\n' prepared >"$backup/phase"
sync -f "$backup"
printf '%s\n' "$stamp" >"$pending.next"
sync -f "$pending.next"
mv "$pending.next" "$pending"
sync -f "$etc"
committed=0
cleanup() {
  result=$?
  trap - EXIT HUP INT TERM
  if [ "$committed" -ne 1 ]; then
    echo '更新未完成，正在回復更新前版本…' >&2
    recover_pending || { echo '自動回復未完成；請執行 manage.sh recover RELEASE_DIRECTORY。所有備份仍保留。' >&2; exit 1; }
    [ "$result" -ne 0 ] || result=1
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
: >"$gate"
chmod 600 "$gate"
systemctl stop "$unit.service" >/dev/null 2>&1 || { [ ! -f "$backup/unit" ] && [ ! -f "$backup/binary" ]; }
if [ -f "$db" ]; then
  "$candidate" ops backup --env-file "$env" --default-db "$data/atlas.sqlite" --output "$backup/database.sqlite"
  "$candidate" ops preflight --env-file "$env" --default-db "$data/atlas.sqlite" --database "$backup/database.sqlite" --output "$backup/preflight.sqlite"
fi
# Record the phase before touching either the live binary or database.
checkpoint activating
install -m 755 "$candidate" "$opt/.candidate-binary"
mv -f "$opt/.candidate-binary" "$opt/modelatlas-vps"
sed -e "s|/var/lib/modelatlas|$data|g" -e "s|/etc/modelatlas|$etc|g" -e "s|/opt/modelatlas|$opt|g" \
  "$bundle/deploy/modelatlas-vps.service" >"$backup/new-unit"
install -m 644 "$backup/new-unit" "$service"
systemctl daemon-reload
systemctl enable "$unit.service" >/dev/null
systemctl restart "$unit.service"
healthy=0
attempt=0
while [ "$attempt" -lt 30 ]; do
  if "$candidate" ops ready --env-file "$env" --default-db "$data/atlas.sqlite" >/dev/null 2>&1; then healthy=1; break; fi
  attempt=$((attempt+1))
  sleep 1
done
[ "$healthy" -eq 1 ] || fail '新版啟動、認證、版本或 vault 檢查失敗。'
checkpoint committed
rm -f "$gate"
mv "$pending" "$backup/committed"
sync -f "$etc"
committed=1
echo "ModelAtlas 已就緒；更新備份：$backup"
echo "登入帳號：admin；管理密碼保留在 $env（僅 root 可讀）。"
echo '本機入口：http://127.0.0.1:8787/；外部連線使用 SSH tunnel 或固定 HTTPS 入口。'
