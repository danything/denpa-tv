#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# CI の emulator の列から使う。同じ走り (run) の apks の列が上げる smoke-apks を待って、<置く先> に展開する。
#
#   GH_TOKEN=... scripts/wait-apks.sh <置く先>
#
# emulator の列は apks を待たずに走り出し、エミュレータを起こしてからここで APK を待つ (焼くのと起こすのを重ねる)。
# apks の列が通らなかった (落ちた・止められた・飛ばされた) ら、待たずに落ちる。`WAIT_APKS_TIMEOUT` 秒 (既定 15 分) 来なければ落ちる。
# 使うもの: GITHUB_REPOSITORY・GITHUB_RUN_ID (Actions が渡す)、GH_TOKEN (actions: read)
# ---------------------------------------------------------------------------
set -euo pipefail

dest=$1
job=apks
artifact=smoke-apks
api=repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID
deadline=$((SECONDS + ${WAIT_APKS_TIMEOUT:-900}))

echo "apks の列が $artifact を上げるのを待ちます"
while :; do
    # 今の試行 (re-run なら最新) の apks の列。「状態 結論 始まった時刻」。API がたまたま落ちても待ち続ける
    state=$(gh api "$api/jobs?per_page=100" --jq ".jobs[] | select(.name == \"$job\") | \"\(.status) \(.conclusion) \(.started_at)\"" || true)
    read -r status conclusion started <<< "${state:-none none none}"
    # この試行の apks が上げたもの。re-run で前の試行の分が残っていても、apks が始まるより前のものは見ない
    # (apks が通ったあとなら、どれでも同じ commit から焼いたもの)。上げ終わった時点で見えるので、
    # 列の後始末 (Gradle のキャッシュの保存) が終わるのは待たない
    since=$started
    [ "$status" = completed ] && [ "$conclusion" = success ] && since=""
    id=""
    if [ "$started" != none ] && [ "$started" != null ]; then
        id=$(gh api "$api/artifacts?name=$artifact" \
            --jq "[.artifacts[] | select((.expired | not) and .size_in_bytes > 0 and .created_at >= \"$since\")] | sort_by(.created_at) | last | .id // empty" || true)
    fi
    if [ -n "$id" ]; then
        break
    fi
    if [ "$status" = completed ]; then
        if [ "$conclusion" != success ]; then
            echo "::error::apks の列が通りませんでした ($conclusion)。APK が無いので試験できません"
            exit 1
        fi
        echo "::error::apks の列は通りましたが、$artifact が見つかりません"
        exit 1
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "::error::apks の列が ${WAIT_APKS_TIMEOUT:-900} 秒たっても $artifact を上げませんでした (状態: $status $conclusion)"
        exit 1
    fi
    sleep 5
done

echo "$artifact ($id) を落とします (待ち ${SECONDS} 秒)"
mkdir -p "$dest"
gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$id/zip" > "$dest/$artifact.zip"
unzip -q -o "$dest/$artifact.zip" -d "$dest"
rm "$dest/$artifact.zip"
