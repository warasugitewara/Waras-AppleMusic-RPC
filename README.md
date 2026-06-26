# AppleMusic RPC (Android client)

[`warasugitewara/Waras-discordRPC`](https://github.com/warasugitewara/Waras-discordRPC) の **送信側(Android)** 実装。
Galaxy S26+ などの Android 端末で Apple Music の再生状況を検出し、Twingate のプライベート
オーバーレイ越しに PC の受信ブリッジへ送って Discord Rich Presence に反映させる。

通信契約は PC 側リポジトリの `docs/PROTOCOL.md`(契約バージョン 1)に準拠している。

---

## 仕組み(PC 側との対応)

- **検出**: `NotificationListenerService` をアンカーに `MediaSessionManager.getActiveSessions()` で
  対象パッケージ(既定 `com.apple.android.music`)の `MediaController` を取得し、
  `MediaController.Callback` で曲・再生状態の変化を拾う。
- **送信**: フォアグラウンドサービスが OkHttp WebSocket で `ws://<host>:<port>/ws` に接続し、
  `op:"presence" / kind:"music"` を送る。認証はハンドシェイクの `Authorization: Bearer <token>`。
- **進捗バー**: クライアントは `position_ms` / `duration_ms` を送るだけ。PC 側 mapper が
  `start = now - position_ms`, `end = start + duration_ms` を計算して進捗バー化する。
  一時停止は `paused:true` を送るとタイムスタンプが外れる。
- **TTL 維持**: PC 側はソースに 30 秒の TTL を持つ(無音だと失効して表示が消える)。
  本アプリは **20 秒ごと(設定可)に現在位置入りで再送**して TTL を更新する。
  再送のたびに現在位置を入れるので `start` がずれない。
- **停止**: 再生が止まると猶予(既定 45 秒)後に `op:"clear"` を送り、サービス自身を止める。
  WS 切断時も PC 側がそのソースを失効させる。再接続(`ready` 受信)時は現在状態を再送する。
- **アートワーク**: Android の `MediaMetadata` のアートワークは Bitmap で Discord に渡せないため、
  **iTunes Search API** で曲名/アーティストから画像 URL を解決して `artwork_url` に入れる。

---

## 前提

**PC 側(受信ブリッジ)**

1. `Waras-discordRPC` が動いていること(`python app.py` か配布 exe)。
2. `config.json` の `network_mode` を `twingate` にし、`bind` を Twingate 到達可能な IP
   (または `0.0.0.0`)にする。`port` は既定 `13520`。
3. `.env` の `BRIDGE_TOKEN` を設定(アプリ側と同じ値にする)。
4. Discord デスクトップを起動し、`DISCORD_CLIENT_ID` を設定済みにする。
5. ソース `phone-music` を有効化(`config.example.json` に既定で入っている。GUI で
   有効/優先度を確認)。

**Android 側**

- Android Studio(最近のもの。JDK 17 と Gradle 8.9 を同梱する版)。
- 端末に **Twingate** が接続済みで、PC のリソースに到達できること。
- 端末で Apple Music が動いていること。

---

## ビルド & インストール

```bash
git clone <this-project>
cd AppleMusicRpc
# Android Studio で開く → 初回 Sync で Gradle wrapper が生成される
# もしくは Gradle を持っているなら: gradle wrapper --gradle-version 8.9
```

- Android Studio で開いて Sync → 実機(S26+)を USB 接続して Run。
- もしくは `./gradlew assembleDebug` で `app/build/outputs/apk/debug/app-debug.apk` を生成して
  `adb install` する(wrapper 生成後)。

> `applicationId` は `com.warasugi.amrpc`。必要なら `app/build.gradle.kts` で変更可。

---

## 設定 & 権限付与

アプリを起動して上から順に:

1. **接続設定**
   - ホスト: PC の Twingate IP か Resource DNS
   - ポート: `13520`
   - `BRIDGE_TOKEN`: PC 側 `.env` と同じ値
2. **ソース**: `source_id` は `phone-music`(PC 側と一致させる)。
3. **権限**(必須順):
   1. **通知アクセス**(必須): 「通知アクセス設定を開く」→ 本アプリを許可。
      これが無いとメディアセッションを取得できない。
   2. **通知の表示**(Android 13+): 常駐通知のため許可。
   3. **電池最適化から除外**(実質必須): Android 12 以降、**バックグラウンドからの
      フォアグラウンドサービス起動は電池最適化の除外が exemption になっている**ため、
      これを許可しないと再生開始時の自動起動が弾かれることがある。Doze 対策にもなる。
4. 「保存」→「接続/再接続」。

---

## 使い方

- Apple Music で再生 → 数秒で Discord に表示。アートワークは少し遅れて反映される
  (テキストを先に送り、URL 取得後に貼り直すため)。
- 一時停止 → "Paused"(タイムスタンプが外れる)。
- 停止 / アプリを閉じる → 猶予後に表示が消える。
- アプリ内「停止」で常駐を止められる。再生を再開すると(通知アクセスが有効なら)再び起動する。

---

## 制約・正直な注意点

- **iTunes Search API は唯一の外部通信**。曲名・アーティストが Apple のサーバに送られる
  (Twingate は経由しない)。気になる場合はアートワークを OFF にできる(画像なしで動作)。
  URL は mzstatic のサムネ URL の `100x100bb` を希望解像度に置換して高解像度化している。
  曲によっては検索がヒットせず画像が出ないことがある。
- **TTL とキープアライブ**: 長い曲でも 20 秒ごとに再送している。PC 側 `min_update_interval`
  は 15 秒なので Discord 反映はそれ以上の間隔に合体される(仕様どおり)。
- **フォアグラウンドサービス種別は `specialUse`**。用途(自ホストの Discord ブリッジへ
  再生メタデータを中継)を manifest に宣言してある。`mediaPlayback` 等は実際に再生して
  いないため使っていない。自動起動には上記の電池最適化除外が必要。
- **Twingate 必須**: 端末が Twingate で PC に到達できないと送れない。平文 ws:// は
  Twingate オーバーレイ内前提(PROTOCOL のとおりアプリ層 TLS なし)。
- **Discord の外部画像 URL**: PC 側は `artwork_url` を `large_image` にそのまま渡す設計。
  外部 URL が表示されるかは Discord 側の挙動に依存する(PC 側/Discord アプリの領分)。
- **対象パッケージは可変**: 既定は `com.apple.android.music`。端末で実際のパッケージ名が
  違う場合や対象を広げたい場合は設定のカンマ区切りで変更できる。

---

## トラブルシュート

| 症状 | 確認 |
|---|---|
| `認証失敗` と出る / すぐ切れる | `BRIDGE_TOKEN` が PC 側と一致しているか。WS は不一致だと close(4001)。 |
| 何も表示されない | PC 側で `phone-music` が有効・優先度が適切か。`GET /health` で `active_source` を確認。 |
| 接続できない | 端末の Twingate が接続済みか。ホスト/ポートが正しいか。PC の `bind` が到達可能か。 |
| 再生開始で自動起動しない | アプリを開いて「電池最適化から除外」を許可(Android 12+ の exemption)。 |
| 曲の途中で消える | キープアライブが届いていない可能性。電池最適化除外と通知アクセスを確認。 |
| アートワークが出ない | iTunes 検索がヒットしていないか、アートワークが OFF。ストアフロント(国)を確認。 |

---

## ライセンス

本体リポジトリに合わせて MIT。© 2026 .warasugi
