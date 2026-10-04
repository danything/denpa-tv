# ---------------------------------------------------------------------------
# denpa TV をテレビに入れる (Windows)。**ダウンロードから adb での接続・インストール・起動まで1行で。**
#
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/danything/denpa-tv/main/scripts/install.ps1))) 192.168.1.20
#
#   引数:
#     <テレビの IP>[:ポート]             adb connect する先 (ポートの既定は 5555)
#     -Pair <IP>:<ポート> -Code <コード>  Android 11 以降の「ワイヤレス デバッグ」で、先にペア設定する
#     -Version v0.2.0                     入れる版 (既定は最新のリリース。試し版も含む)
#
# テレビ側で先に済ませておくこと (開発者向けオプション・USB / ネットワーク デバッグ) は docs/install.md。
# adb が無ければ Google の platform-tools を %LOCALAPPDATA%\denpa-tv に取ってきて使う (入れはしない)。
# Windows に最初から入っている PowerShell 5.1 で動く。`irm | iex` の形でも動くよう、exit は使わない
# ---------------------------------------------------------------------------
param([Parameter(Position = 0)][string]$Target, [string]$Pair, [string]$Code, [string]$Version)

function Install-DenpaTv([string]$Target, [string]$Pair, [string]$Code, [string]$Version) {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    $repo = 'danything/denpa-tv'
    $package = 'io.github.danything.denpatv'
    $cache = Join-Path $env:LOCALAPPDATA 'denpa-tv'
    function Say([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }

    if (-not $Target) { throw 'テレビの IP を渡してください (例: ... ))) 192.168.1.20)' }
    if ($Target -notmatch ':') { $Target = "${Target}:5555" }

    # --- adb (無ければ platform-tools を取ってくる) ---
    $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $adb) {
        $adb = Join-Path $cache 'platform-tools\adb.exe'
        if (-not (Test-Path -LiteralPath $adb)) {
            Say "adb が無いので、Google の platform-tools を $cache に取ってきます"
            New-Item -ItemType Directory -Force -Path $cache | Out-Null
            $zip = Join-Path $cache 'platform-tools.zip'
            Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
            Expand-Archive -Force -LiteralPath $zip -DestinationPath $cache
            Remove-Item -LiteralPath $zip
        }
    }

    # --- APK (GitHub のリリース。ハッシュを確かめる) ---
    $release = if ($Version) {
        Invoke-RestMethod "https://api.github.com/repos/$repo/releases/tags/$Version"
    } else {
        # 試し版 (prerelease) も含めて、いちばん新しいもの
        @(Invoke-RestMethod "https://api.github.com/repos/$repo/releases?per_page=1")[0]
    }
    $asset = $release.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
    if (-not $asset) { throw "リリース $($release.tag_name) に APK が見つかりません" }
    $work = Join-Path ([IO.Path]::GetTempPath()) "denpa-tv-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    try {
        $apk = Join-Path $work $asset.name
        Say "$($asset.name) を取ってきます"
        Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile $apk
        $sums = $release.assets | Where-Object { $_.name -eq 'SHA256SUMS' } | Select-Object -First 1
        if ($sums) {
            $line = (Invoke-WebRequest -UseBasicParsing -Uri $sums.browser_download_url).Content -split "`n" |
                Where-Object { ($_ -split '\s+')[1] -in $asset.name, "*$($asset.name)" }
            $expected = ($line -split '\s+')[0]
            if (-not $expected -or (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash -ne $expected.ToUpperInvariant()) {
                throw 'APK のハッシュが合いません (取り直してください)'
            }
        }

        # --- テレビに繋ぐ ---
        $ErrorActionPreference = 'Continue'
        if ($Pair) {
            Say "$Pair とペア設定します"
            & $adb pair $Pair $Code
            if ($LASTEXITCODE -ne 0) { throw 'ペア設定できませんでした (コードとポートはペア設定の画面に出ているものです)' }
        }
        Say "$Target に繋ぎます"
        & $adb connect $Target | Out-Null
        $asked = $false
        for ($i = 0; $i -lt 30; $i++) {
            $state = (& $adb -s $Target get-state 2>&1 | Out-String).Trim()
            if ($state -eq 'device') { break }
            if ($state -match 'unauthorized') {
                if (-not $asked) { Say 'テレビに出ている「USB デバッグを許可」を押してください'; $asked = $true }
            } else {
                & $adb connect $Target 2>&1 | Out-Null
            }
            Start-Sleep -Seconds 2
        }
        if ((& $adb -s $Target get-state 2>$null | Out-String).Trim() -ne 'device') {
            throw "$Target に繋がりません。テレビの開発者向けオプションでデバッグが入っているか、同じネットワークかを確かめてください (docs/install.md)"
        }

        # --- 入れて起こす ---
        Say 'インストールします'
        $out = & $adb -s $Target install -r $apk 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
                throw "入っている denpa TV と署名が違うので上書きできません。テレビで denpa TV を消してから流し直してください ($adb -s $Target uninstall $package で消せます。設定は消えます)"
            }
            throw "インストールできませんでした: $out"
        }
        & $adb -s $Target shell am start -n "$package/.MainActivity" | Out-Null
        Say '入れて起動しました。終わったら、テレビのデバッグは切っておくのがおすすめです (docs/install.md)'
    } finally {
        Remove-Item -Recurse -Force -LiteralPath $work -ErrorAction SilentlyContinue
    }
}

Install-DenpaTv -Target $Target -Pair $Pair -Code $Code -Version $Version
