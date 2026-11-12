#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# denpa TV をテレビに入れる。**ダウンロードから adb での接続・インストール・起動まで1行で。**
#
#   curl -fsSL https://raw.githubusercontent.com/danything/denpa-tv/main/scripts/install.sh | bash -s -- 192.168.1.20
#
#   引数:
#     <テレビの IP>[:ポート]          adb connect する先 (ポートの既定は 5555)
#     --pair <IP>:<ポート> <コード>   Android 11 以降の「ワイヤレス デバッグ」で、先にペア設定する
#                                     (テレビの「ペア設定コードでデバイスをペア設定」に出る IP・ポート・コード)
#     --version v0.2.0                入れる版 (既定は最新のリリース。試し版も含む)
#
# テレビ側で先に済ませておくこと (開発者向けオプション・USB / ネットワーク デバッグ) は docs/install.md。
# adb が無ければ Google の platform-tools を ~/.cache/denpa-tv に取ってきて使う (入れはしない)。
# macOS と Linux 用。Windows は scripts/install.ps1。
# ---------------------------------------------------------------------------
set -euo pipefail

REPO=danything/denpa-tv
PACKAGE=io.doany.denpatv
# 古いアプリ (v1.0.0 より前のパッケージ名) と、そのパッケージ名で出した最後のバージョン。
# 古いアプリが入っていれば先にこのバージョンへ上げ、新しいアプリが初めて開いたときに設定を受け取れるようにする (docs/install.md)
OLD_PACKAGE=io.github.danything.denpatv
OLD_LAST=v0.30.0
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/denpa-tv"

say() { printf '==> %s\n' "$*" >&2; }
usage() {
    cat >&2 <<'EOF'
使い方: install.sh <テレビの IP>[:ポート] [--pair <IP>:<ポート> <コード>] [--version v0.2.1]
  --pair     Android 11 以降の「ワイヤレス デバッグ」で、先にペア設定する
  --version  インストールする版 (既定は最新のリリース。プレリリースを含む)
EOF
}
die() { printf 'エラー: %s\n' "$*" >&2; exit 1; }

target='' pair='' code='' version=''
while [ $# -gt 0 ]; do
    case "$1" in
        --pair) [ $# -ge 3 ] || die '--pair には <IP>:<ポート> と <コード> を指定してください'
            pair=$2 code=$3; shift 3 ;;
        --version) [ $# -ge 2 ] || die '--version には版を指定してください (例: v0.2.0)'
            version=$2; shift 2 ;;
        -h | --help) usage; exit 0 ;;
        -*) die "不明な引数です: $1" ;;
        *) target=$1; shift ;;
    esac
done
[ -n "$target" ] || die 'テレビの IP を指定してください (例: bash -s -- 192.168.1.20)'
case "$target" in *:*) ;; *) target="$target:5555" ;; esac

# --- adb (無ければ platform-tools を取ってくる) ---
if command -v adb >/dev/null 2>&1; then
    adb=adb
else
    case "$(uname -s)" in
        Darwin) os=darwin ;;
        Linux) os=linux ;;
        *) die "未対応の OS です: $(uname -s) (Windows は scripts/install.ps1)" ;;
    esac
    adb="$CACHE/platform-tools/adb"
    if [ ! -x "$adb" ]; then
        say "platform-tools をダウンロード中 ($CACHE)"
        mkdir -p "$CACHE"
        curl -fsSL --retry 3 -o "$CACHE/platform-tools.zip" \
            "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
        rm -rf "$CACHE/platform-tools"
        unzip -q "$CACHE/platform-tools.zip" -d "$CACHE"
        rm -f "$CACHE/platform-tools.zip"
    fi
fi

# --- APK (GitHub のリリース。ハッシュを確かめる) ---
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# リリースの API ($1) の APK を $work に落としてハッシュを確かめ、パスを $fetched に入れる
fetch_apk() {
    local api=$1 release urls apk_url sums_url expected actual
    release=$(curl -fsSL --retry 3 -H 'Accept: application/vnd.github+json' "$api") ||
        die "リリースが見つかりません ($api)"
    urls=$(printf '%s\n' "$release" |
        grep -o '"browser_download_url": *"[^"]*"' | sed 's/.*"\(https[^"]*\)"/\1/' || true)
    # 一致しない grep は pipefail で止まるので、無いことは下で言う
    apk_url=$(printf '%s\n' "$urls" | grep '\.apk$' | head -n1 || true)
    sums_url=$(printf '%s\n' "$urls" | grep '/SHA256SUMS$' | head -n1 || true)
    [ -n "$apk_url" ] || die "リリースに APK が見つかりません ($api)"
    fetched="$work/$(basename "$apk_url")"
    say "$(basename "$apk_url") をダウンロード中"
    curl -fsSL --retry 3 -o "$fetched" "$apk_url"
    # ハッシュの無いリリースは入れない (壊れた APK を黙って入れない)
    [ -n "$sums_url" ] || die 'リリースに SHA256SUMS がありません'
    expected=$(curl -fsSL --retry 3 "$sums_url" | grep " \*\{0,1\}$(basename "$fetched")\$" | cut -d' ' -f1 || true)
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$fetched" | cut -d' ' -f1)
    else
        actual=$(shasum -a 256 "$fetched" | cut -d' ' -f1)
    fi
    [ -n "$expected" ] && [ "$expected" = "$actual" ] || die 'APK のハッシュが一致しません。もう一度実行してください'
}
if [ -n "$version" ]; then
    fetch_apk "https://api.github.com/repos/$REPO/releases/tags/$version"
else
    # 試し版 (prerelease) も含めて、いちばん新しいもの
    fetch_apk "https://api.github.com/repos/$REPO/releases?per_page=1"
fi
apk=$fetched

# --- テレビに繋ぐ ---
# adb には標準入力を渡さない。curl | bash で流すと、標準入力はこのスクリプトの続きで、
# adb (とくに shell) がそれを読むと残りが食われる
if [ -n "$pair" ]; then
    say "$pair とペア設定中"
    "$adb" pair "$pair" "$code" </dev/null || die 'ペア設定に失敗しました。ペア設定の画面のポートとコードを確認してください'
fi
say "$target に接続中"
# 届かない宛先への adb connect はなかなか返らないので、10秒で見切る (macOS には timeout が無い)
connect() {
    "$adb" connect "$target" </dev/null >/dev/null 2>&1 &
    local pid=$!
    ( sleep 10; kill "$pid" 2>/dev/null ) &
    local watch=$!
    wait "$pid" 2>/dev/null || true
    kill "$watch" 2>/dev/null || true
}
state() { "$adb" -s "$target" get-state </dev/null 2>&1 || true; }
for _ in 1 2 3; do
    connect
    case "$(state)" in device | *unauthorized*) break ;; esac
done
# テレビに「USB デバッグを許可しますか」が出ていれば、許可を押すまで待つ (60秒)
for _ in $(seq 1 30); do
    case "$(state)" in
        device) break ;;
        *unauthorized*) [ "${asked:-}" ] || say 'テレビで「USB デバッグを許可」を選んでください'; asked=1 ;;
        *) break ;;
    esac
    sleep 2
done
[ "$(state)" = device ] ||
    die "$target に接続できません。テレビのデバッグが有効か、同じネットワークかを確認してください (docs/install.md)"

# --- 古いアプリが入っていれば、先に最後のバージョンへ上げる ---
# 新しいアプリは別のアプリなので、古いアプリの上には入らない。古いアプリを最後のバージョンにしておけば、
# 新しいアプリが初めて開いたときに、そこから接続先と設定を受け取る。上げられなくても新しいアプリは入れる (繋ぎ直せばよい)
had_old=
if "$adb" -s "$target" shell pm path "$OLD_PACKAGE" </dev/null >/dev/null 2>&1; then
    had_old=1
    old_version=$("$adb" -s "$target" shell dumpsys package "$OLD_PACKAGE" </dev/null 2>/dev/null |
        sed -n 's/^ *versionName=\([^[:space:]]*\).*/\1/p' | head -n1 | tr -d '\r' || true)
    if [ "$old_version" != "${OLD_LAST#v}" ]; then
        say "古いアプリ (v$old_version) を $OLD_LAST に上げます (設定を新しいアプリに渡すため)"
        fetch_apk "https://api.github.com/repos/$REPO/releases/tags/$OLD_LAST"
        if ! out=$("$adb" -s "$target" install -r "$fetched" </dev/null 2>&1); then
            say "古いアプリを $OLD_LAST に上げられませんでした。新しいアプリでは繋ぎ直してください: $out"
        fi
    fi
fi

# --- 入れて起こす ---
say 'インストール中'
if ! out=$("$adb" -s "$target" install -r "$apk" </dev/null 2>&1); then
    case "$out" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
            die "署名が違うため上書きできません。アンインストールしてから、もう一度実行してください: $adb -s $target uninstall $PACKAGE (設定も消えます)" ;;
        *) die "インストールに失敗しました: $out" ;;
    esac
fi
"$adb" -s "$target" shell am start -n "$PACKAGE/.MainActivity" </dev/null >/dev/null
[ -z "$had_old" ] || say '古いアプリは、新しいアプリの録画の一覧の上に出る「古いアプリを削除」で削除できます'
say 'インストールしました。使い終わったら、テレビのデバッグをオフに戻してください (docs/install.md)'
