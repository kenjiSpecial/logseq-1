# Journal Android: 定期同期とページ整合性改善計画

## ゴール

https://journal.pa-to-po.dev/#/ を Android アプリ（Journal Android / Logseq fork）で開いたとき、起動時だけでなく定期的にサーバー側の最新データを取り込み、現在表示中のページ・他ページ・ローカルファイル・DB の整合性を保つ。

## 現状メモ

対象リポジトリ: `/home/kenji/projects/logseq-app`
現在ブランチ: `feat/server-graph-autosave`

既存実装:

- Android Journal mode は `LOGSEQ_JOURNAL_ANDROID=1` で有効化。
- `src/main/frontend/journal_mobile/sync.cljs`
  - 起動後 `startup-delay-ms=4000` で `sync-now!` を実行。
  - dirty queue のローカル変更を先に PUT。
  - `/api/graph/manifest` を取得。
  - remote-only / changed ファイルを `local-graph/write-remote-file!` で app-private storage に保存。
  - dirty queue にあるローカル変更中ファイルは remote pull で上書きしない。
- `src/main/frontend/handler.cljs`
  - `load-journal-local-graph!` がローカルファイル群を DB に読み込み、その後 `journal-sync/start!` を呼ぶ。

不足している点:

- `sync-now!` は起動時とローカル書き込み後の遅延実行が中心で、アプリを開いたままにした場合の定期取得がない。
- remote pull 後に app-private storage のファイルは更新されるが、表示中の Logseq DB / ページビューへ確実に再反映される導線が薄い。
- 複数ファイルにまたがる更新を取り込むとき、途中状態を UI に見せない・dirty local を潰さない・現在編集中ページを破壊しない、という整合性方針を明示する必要がある。

## 方針

1. `sync.cljs` に定期同期ループを追加する。
   - 例: 60秒〜120秒間隔。初期値は 60秒案。
   - `:syncing?` で多重実行を防ぐ。
   - `navigator.onLine` が false のときはスキップ。
   - アプリ復帰 / タブ表示復帰時（`visibilitychange`）にも即時同期を試す。

2. remote pull の結果を UI/DB へ反映する専用 callback を用意する。
   - `sync.cljs` は低レイヤーなので、DB 再読込関数を直接 require しすぎない。
   - `register-after-pull!` のような callback atom を `sync.cljs` に持たせ、`handler.cljs` 側から登録する。
   - `remote-pull!` で downloaded > 0 のときだけ callback を呼ぶ。

3. callback では Journal local graph を再読込し、Logseq DB を更新する。
   - 既存の `journal-local-graph/files` を再実行。
   - `repo-handler/load-new-repo-to-db!` またはより安全な既存 reload/parse 経路を調べて利用。
   - 現在の repo が Journal local graph のときだけ実行。
   - ページ間整合性を優先し、manifest 取得→対象ファイルダウンロード完了→DB reload の順に一括反映する。

4. ローカル編集中データ保護を維持する。
   - 既存の `queued-paths` skip は維持。
   - dirty queue flush 失敗時、そのファイルは pull で上書きしない方針を明確化する。
   - 可能なら pull candidates 計算で「flush 失敗した path」も dirty 扱いに含める。

5. ログを増やして検証しやすくする。
   - 定期同期開始/スキップ/成功/失敗。
   - pull 後 DB refresh 実行/スキップ。
   - `:journal-mobile/periodic-sync-*`, `:journal-mobile/db-refresh-*` など。

## 変更予定ファイル

- `src/main/frontend/journal_mobile/sync.cljs`
  - 定期同期 interval timer 追加。
  - app foreground/visibility 復帰 hook 追加。
  - after-pull callback 登録 API 追加。
  - downloaded > 0 のとき callback 実行。
  - dirty path 保護強化。

- `src/main/frontend/handler.cljs`
  - `load-journal-local-graph!` 付近で after-pull callback を登録。
  - remote pull 後に `journal-local-graph/files` → repo DB 再読込を実行。
  - 必要なら current repo / restoring 状態チェックを追加。

- 必要に応じてテスト追加/更新
  - 既存 test runner の有無を確認してから追加。
  - ClojureScript unit test が難しければ、最低限 `yarn lint` / `clj-kondo` / Android build で検証。

## 実装ステップ

1. 既存の repo reload API を調査する。
   - `repo-handler/load-new-repo-to-db!` の副作用と再実行安全性を確認。
   - 既存の file watcher / refresh 処理で再読込に使える関数がないか検索。

2. `sync.cljs` を拡張する。
   - `periodic-sync-interval-ms` を定義。
   - `periodic-sync-timer` / `visibility-listener-registered?` atom を追加。
   - `start!` で起動同期 + interval + visibility listener を開始。
   - `stop!` が必要なら追加して timer を clear できるようにする。
   - `sync-now!` が既に走っている場合は既存 state を返し、重複実行しない。

3. remote pull 後 callback を追加する。
   - `defonce after-pull-callbacks` または単一 callback atom。
   - `register-after-pull!` を提供。
   - `remote-pull!` の summary が `downloaded > 0` のときに callback を呼ぶ。
   - callback 失敗は同期全体を壊さず、ログと `last-error` に残す。

4. `handler.cljs` で DB refresh callback を登録する。
   - Journal graph の current repo を確認。
   - `journal-local-graph/files` で最新ファイル群を読み直す。
   - `repo-handler/start-repo-db-if-not-exists!` → `repo-handler/load-new-repo-to-db!` を既存初期ロードと同じ形で呼ぶ。
   - UI が復帰しない場合は `state/pub-event! [:graph/restored ...]` や page refresh イベントが必要か確認。

5. dirty consistency を確認する。
   - ローカル未送信のファイルは remote pull で上書きされないこと。
   - flush 失敗 path が changed remote と衝突した場合に skip されること。

6. 検証する。
   - `git diff` で変更確認。
   - lint/test/build を実行。
   - 可能なら `scripts/build-journal-android.sh` で debug APK build。
   - APK 内に intended API base が埋め込まれていることを確認。
   - 実機検証が可能なら logcat で periodic sync / remote pull / db refresh ログを確認。

## 検証観点

- アプリ起動直後に remote-only ファイルが取り込まれる。
- アプリを開いたまま Web 側でページを更新すると、次回 interval で Android 側にも反映される。
- 現在表示中ページだけでなく、別ページへ移動しても同じ manifest snapshot に基づく内容が見える。
- ローカルで編集済み・未送信のファイルは remote pull によって上書きされない。
- オフライン時はエラー連発せず、復帰後に同期する。
- 同期中に次の interval が来ても多重同期しない。

## リスク / 要確認

- `load-new-repo-to-db!` を実行中 DB に対して再実行してよいか要確認。もし副作用が大きければ、より細かい file reparse / graph refresh API を使う。
- 編集中ページを外部更新で再読込したときのエディタ状態。dirty queue があるページは上書きしないが、別ページ更新に伴う UI refresh が編集中ブロックに影響しないか確認が必要。
- 定期 interval が短すぎるとバッテリー・通信・サーバー負荷が増える。初期値 60 秒で実装し、必要なら 120〜300 秒へ調整。
- Cloudflare Access / CORS / Tailscale 経路の問題がある場合、同期コードだけでは解決しない。既存の Android transport 検証手順に従う。

## 実装前の確認

この計画で進める場合は「計画通り進めて」と指示してください。実装時は、変更→build/test→可能な範囲で Android/Javascript 側のログ検証まで行います。
