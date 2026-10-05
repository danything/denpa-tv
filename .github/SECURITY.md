# セキュリティポリシー

## 対象

`main` ブランチと最新のリリースだけを見ています。古いリリースへの
バックポートはしません。

## 脆弱性の報告

**公開の Issue には書かないでください。** 攻撃に使える情報が、直る前に
広まってしまいます。

- GitHub の [プライベート脆弱性報告](https://github.com/danything/denpa-tv/security/advisories/new) を
  使ってください (Security タブ → Report a vulnerability)。
- 内容に応じて優先的に対応します。個人プロジェクトなので即応は
  約束できませんが、放置はしません。

## 前提

denpa TV は **家庭内 LAN の denpa に繋ぐ**ことを前提にしています。家の外の denpa に繋ぐときの
入り方 (OIDC・端末の鍵) は denpa 側の [docs/auth.md](https://github.com/danything/denpa/blob/main/docs/auth.md) を
確認してください。アプリの署名の鍵はリポジトリに置いていません ([docs/release.md](../docs/release.md))。
