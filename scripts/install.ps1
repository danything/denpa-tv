# ---------------------------------------------------------------------------
# denpa TV をテレビに入れる (Windows)。**ダウンロードから adb での接続・インストール・起動まで1行で。**
#
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/danything/denpa-tv/main/scripts/install.ps1))) 192.168.1.20
#
#   引数:
#     <テレビの IP>[:ポート]             adb connect する先 (ポートの既定は 5555)
#     -Pair <IP>:<ポート> -Code <コード>  Android 11 以降の「ワイヤレス デバッグ」で、先にペア設定する
#     -Version v0.2.0                     インストールする版 (既定は最新のリリース。プレリリースを含む)
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
    $package = 'io.doany.denpatv'
    # 古いアプリ (v1.0.0 より前のパッケージ名) と、そのパッケージ名で出した最後のバージョン。
    # 古いアプリが入っていれば先にこのバージョンへ上げ、新しいアプリが初めて開いたときに設定を受け取れるようにする (docs/install.md)
    $oldPackage = 'io.github.danything.denpatv'
    $oldLast = 'v0.30.0'
    $cache = Join-Path $env:LOCALAPPDATA 'denpa-tv'
    function Say([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }

    if (-not $Target) { throw 'テレビの IP を指定してください (例: ... ))) 192.168.1.20)' }
    if ($Target -notmatch ':') { $Target = "${Target}:5555" }
    if ($Pair -and -not $Code) { throw '-Pair には -Code も指定してください' }

    # --- adb (無ければ platform-tools を取ってくる) ---
    $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $adb) {
        $adb = Join-Path $cache 'platform-tools\adb.exe'
        if (-not (Test-Path -LiteralPath $adb)) {
            Say "platform-tools をダウンロード中 ($cache)"
            New-Item -ItemType Directory -Force -Path $cache | Out-Null
            $zip = Join-Path $cache 'platform-tools.zip'
            Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
            Expand-Archive -Force -LiteralPath $zip -DestinationPath $cache
            Remove-Item -LiteralPath $zip
        }
    }

    # --- APK (GitHub のリリース。ハッシュを確かめる) ---
    $work = Join-Path ([IO.Path]::GetTempPath()) "denpa-tv-$([Guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    # リリースの APK を $work に落としてハッシュを確かめ、パスを返す
    function Get-ReleaseApk($Release) {
        $asset = $Release.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
        if (-not $asset) { throw "リリース $($Release.tag_name) に APK が見つかりません" }
        $file = Join-Path $work $asset.name
        Say "$($asset.name) をダウンロード中"
        Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile $file
        # ハッシュの無いリリースは入れない (壊れた APK を黙って入れない)
        $sums = $Release.assets | Where-Object { $_.name -eq 'SHA256SUMS' } | Select-Object -First 1
        if (-not $sums) { throw 'リリースに SHA256SUMS がありません' }
        # 5.1 の Invoke-WebRequest は octet-stream を byte[] で返すので、ファイルに落としてから読む
        $sumsFile = Join-Path $work 'SHA256SUMS'
        Invoke-WebRequest -UseBasicParsing -Uri $sums.browser_download_url -OutFile $sumsFile
        $line = Get-Content -LiteralPath $sumsFile | Where-Object { ($_ -split '\s+')[1] -in $asset.name, "*$($asset.name)" }
        $expected = ($line -split '\s+')[0]
        if (-not $expected -or (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash -ne $expected.ToUpperInvariant()) {
            throw 'APK のハッシュが一致しません。もう一度実行してください'
        }
        $file
    }
    try {
        $release = if ($Version) {
            Invoke-RestMethod "https://api.github.com/repos/$repo/releases/tags/$Version"
        } else {
            # 試し版 (prerelease) も含めて、いちばん新しいもの
            @(Invoke-RestMethod "https://api.github.com/repos/$repo/releases?per_page=1")[0]
        }
        $apk = Get-ReleaseApk $release

        # --- テレビに繋ぐ ---
        $ErrorActionPreference = 'Continue'
        if ($Pair) {
            Say "$Pair とペア設定中"
            & $adb pair $Pair $Code
            if ($LASTEXITCODE -ne 0) { throw 'ペア設定に失敗しました。ペア設定の画面のポートとコードを確認してください' }
        }
        Say "$Target に接続中"
        # 届かない宛先への adb connect はなかなか返らないので、10秒で見切る
        function Connect-Tv {
            $p = Start-Process -FilePath $adb -ArgumentList 'connect', $Target -NoNewWindow -PassThru `
                -RedirectStandardOutput (Join-Path $work 'connect.out') -RedirectStandardError (Join-Path $work 'connect.err')
            if (-not $p.WaitForExit(10000)) { $p.Kill() }
        }
        function Get-TvState { (& $adb -s $Target get-state 2>&1 | Out-String).Trim() }
        for ($i = 0; $i -lt 3; $i++) {
            Connect-Tv
            $state = Get-TvState
            if ($state -eq 'device' -or $state -match 'unauthorized') { break }
        }
        # テレビに「USB デバッグを許可しますか」が出ていれば、許可を押すまで待つ (60秒)
        $asked = $false
        for ($i = 0; $i -lt 30; $i++) {
            $state = Get-TvState
            if ($state -eq 'device') { break }
            if ($state -notmatch 'unauthorized') { break }
            if (-not $asked) { Say 'テレビで「USB デバッグを許可」を選んでください'; $asked = $true }
            Start-Sleep -Seconds 2
        }
        if ((Get-TvState) -ne 'device') {
            throw "$Target に接続できません。テレビのデバッグが有効か、同じネットワークかを確認してください (docs/install.md)"
        }

        # --- 古いアプリが入っていれば、先に最後のバージョンへ上げる ---
        # 新しいアプリは別のアプリなので、古いアプリの上には入らない。古いアプリを最後のバージョンにしておけば、
        # 新しいアプリが初めて開いたときに、そこから接続先と設定を受け取る。上げられなくても新しいアプリは入れる (繋ぎ直せばよい)
        & $adb -s $Target shell pm path $oldPackage 2>&1 | Out-Null
        $hadOld = $LASTEXITCODE -eq 0
        if ($hadOld) {
            $found = & $adb -s $Target shell dumpsys package $oldPackage 2>$null |
                Select-String -Pattern '^\s*versionName=(\S+)' | Select-Object -First 1
            $oldVersion = if ($found) { $found.Matches[0].Groups[1].Value } else { '' }
            if ($oldVersion -ne $oldLast.TrimStart('v')) {
                Say "古いアプリ (v$oldVersion) を $oldLast に上げます (設定を新しいアプリに渡すため)"
                try {
                    $oldApk = Get-ReleaseApk (Invoke-RestMethod -ErrorAction Stop "https://api.github.com/repos/$repo/releases/tags/$oldLast")
                    $out = & $adb -s $Target install -r $oldApk 2>&1 | Out-String
                    if ($LASTEXITCODE -ne 0) { throw $out }
                } catch {
                    Say "古いアプリを $oldLast に上げられませんでした。新しいアプリでは繋ぎ直してください: $_"
                }
            }
        }

        # --- 入れて起こす ---
        Say 'インストール中'
        $out = & $adb -s $Target install -r $apk 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
                throw "署名が違うため上書きできません。アンインストールしてから、もう一度実行してください: $adb -s $Target uninstall $package (設定も消えます)"
            }
            throw "インストールに失敗しました: $out"
        }
        & $adb -s $Target shell am start -n "$package/.MainActivity" | Out-Null
        if ($hadOld) { Say '古いアプリは、新しいアプリの録画の一覧の上に出る「古いアプリを削除」で削除できます' }
        Say 'インストールしました。使い終わったら、テレビのデバッグをオフに戻してください (docs/install.md)'
    } finally {
        Remove-Item -Recurse -Force -LiteralPath $work -ErrorAction SilentlyContinue
    }
}

Install-DenpaTv -Target $Target -Pair $Pair -Code $Code -Version $Version
