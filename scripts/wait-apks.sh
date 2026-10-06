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
# apks の列の、smoke-apks を上げる手順の名前 (.github/workflows/ci.yml と合わせる)
step="Upload smoke-apks"
artifact=smoke-apks
api=repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID
deadline=$((SECONDS + ${WAIT_APKS_TIMEOUT:-900}))

echo "apks の列が $artifact を上げるのを待ちます"
# 見るのは今の試行 (re-run なら最新) の apks の列だけ (1 回に API を 1 度。5 列が 15 分待っても GITHUB_TOKEN の上限に届かない間隔)。
# 上げる手順が終われば取りに行き、列の後始末 (Gradle のキャッシュの保存) が終わるのは待たない。
# API がたまたま落ちても待ち続ける (gh のエラーはそのまま出る)
while :; do
    state=$(gh api "$api/jobs?per_page=100" \
        --jq ".jobs[] | select(.name == \"$job\") | \"\(.status) \(.conclusion) \([.steps[]? | select(.name == \"$step\") | .conclusion] | first // \"none\")\"" || true)
    read -r status conclusion uploaded <<< "${state:-none none none}"
    if [ "$uploaded" = success ]; then
        break
    fi
    if [ "$status" = completed ]; then
        echo "::error::apks の列が $artifact を上げずに終わりました ($conclusion)。APK が無いので試験できません"
        exit 1
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "::error::apks の列が ${WAIT_APKS_TIMEOUT:-900} 秒たっても $artifact を上げませんでした (状態: $status $conclusion)"
        exit 1
    fi
    sleep 6
done

# 今の試行が上げたものがいちばん新しい (re-run で apks を走らせ直さなかったときは、前の試行が同じ commit から焼いたもの)
id=$(gh api "$api/artifacts?name=$artifact" \
    --jq '[.artifacts[] | select((.expired | not) and .size_in_bytes > 0)] | sort_by(.created_at) | last | .id // empty')
if [ -z "$id" ]; then
    echo "::error::apks の列は $artifact を上げましたが、見つかりません"
    exit 1
fi
echo "$artifact ($id) を落とします (待ち ${SECONDS} 秒)"
mkdir -p "$dest"
gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$id/zip" > "$dest/$artifact.zip"
unzip -q -o "$dest/$artifact.zip" -d "$dest"
rm "$dest/$artifact.zip"
