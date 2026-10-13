# 調査メモ: scalardl-cleanup resumable scan の JDBC (RDBMS) 対応

日付: 2026-08-21
対象: scalardl-cleanup (resumable-scan モジュール), ScalarDB 3.19.0

## 結論

**実現可能。しかも Cosmos DB 版より実装は簡単になる見込み。**

Cosmos の continuation token に相当する仕組みは RDBMS に無いが、**キーセットページネーション**
(最後に処理した主キーをチェックポイントとして保存し、`WHERE pk > last ORDER BY pk LIMIT n` で再開)
で同等の resumable scan を実現できる。

複数 RDBMS 対応の鍵は、生 SQL を書かずに **ScalarDB の公開 API (cross-partition scan =
`ScanAll` + filtering / ordering / limit)** に乗ること。SQL 方言差 (LIMIT vs FETCH FIRST vs TOP、
識別子クォート、型) はすべて ScalarDB の RdbEngineStrategy が吸収するため、ツール側は
DB 非依存のコードだけで済む。

## 前提の確認 (コードから確認済みの事実)

### スキャン対象テーブルと主キー

| ツール | テーブル | partition key | clustering key |
|---|---|---|---|
| coordinator-state-cleanup | coordinator.state | tx_id (TEXT) | なし |
| ledger-finalize-records | ledger namespace 内の各テーブル (asset 等) | id (TEXT) | age (INT) ※asset |
| auditor-finalize-records | asset_lock | id (TEXT) | なし |
| request-proof-cleanup | request_proof | nonce (TEXT) | なし |

- JDBC storage ではこれらは実テーブルで、partition key + clustering key がそのまま
  SQL の PRIMARY KEY になる → **PK インデックスが必ず存在**し、キーセットページネーションが効率的に動く。
- ScalarDL 管理テーブルのキー型は TEXT / INT のみ → チェックポイントのシリアライズも簡単。
- **【2026-08-21 訂正】** ledger-finalize-records の走査対象は上記に限らない: ScalarDL Functions の
  実行で coordinator を共有する**ユーザーの任意の ScalarDB ネームスペースの全トランザクショナル
  テーブル**が対象になる(`discoverTables()` は coordinator テーブル以外を除外しない)。したがって
  実装では主キーのカラム数(3カラム以上)・型(BLOB・時刻型含む全キー型)・clustering order(DESC)
  に仮定を置けない。詳細は実装プラン
  ([implementation-plan.md](implementation-plan.md))の R4 / R5 / KTD2 を参照。

- **【2026-08-21 追加検証】キーセット条件は CNF ではなく DNF で組む。** 当初案の
  `whereAnd`(CNF)は ScalarDB の `SelectionBuilder.getConjunctions` が cartesian product で
  DNF 展開するため、主キーのカラム数に対して条件が超指数的に増える(実測: 3カラム 6 disjunct /
  17 パラメータ、4カラム 21 / 76、5カラム 77 / 340、8カラム 4,082 / 28,168)。
  `whereOr(Set<AndConditionSet>)` で DNF を直接渡せば展開を経ないため線形に収まる
  (8カラムで 8 disjunct / 44 パラメータ)。実行計画も劣化せず、PostgreSQL 16 では CNF と
  同一(26 buffers / 0.79ms)、MySQL 8.4 はむしろ改善する(3つのタイトな range union)。
  両形式が同一の行集合を返すことはランダム 200 チェックポイントで確認済み(不一致 0)。

### ScalarDB 側のサポート状況 (3.19 / master で確認)

- cross-partition scan の filtering / ordering / limit は **JDBC storage で正式サポート**
  (`scalar.db.cross_partition_scan.enabled` / `filtering.enabled` / `ordering.enabled`)。
- `OperationChecker.checkOrderingsForScanAll` は「カラムが存在すること」しか要求しない
  → 全 PK カラムを ORDER BY に指定できる。
- `ScanBuilder.whereOr(Set<AndConditionSet>)` で OR-of-AND 条件を表現できる
  → 複合 PK の keyset 条件 `(id > ?) OR (id = ? AND age > ?)` を公開 API で構築可能。
- 公開 API の `storage.scan()` は `Result` を直接返すので、Cosmos 版で必要だった内部クラス
  (`CosmosUtils`, `ResultInterpreter`, FeedRange シリアライズ) に相当する依存が**ほぼ不要**。

Cosmos 版が ScalarDB API を使えなかったのは「resume (continuation token) と FeedRange 並列化が
API 越しに表現できない」ため。JDBC では resume 条件をクエリ自体 (keyset 条件) で表現できるので、
公開 API だけで完結する。

## 設計案

### JdbcResumableScanner (ResumableScanner 実装)

```
loop:
  ScanAll:
    orderings  = 全PKカラム (partition key → clustering key の順、方向は metadata 準拠)
    where      = チェックポイントがあれば keyset 条件 (DNF を whereOr で直接指定)
    limit      = pageSize
  ページ内の各 Result を RecordHandler へ配信
  ページ末尾レコードの PK 値をチェックポイントに永続化 (atomic write)
  結果件数 < pageSize なら完了
```

- **チェックポイント**: `.token` ファイルの中身が「continuation token」から
  「最終処理キーの JSON (カラム名 → 型付き値)」に変わるだけ。既存 `CheckpointManager` の
  ファイル配置・atomic write・clear の仕組みは共通化して再利用できる (cosmos パッケージから common へ移動)。
- **resume semantics**: ページ処理完了後にチェックポイント → at-least-once (ページ途中で落ちると
  そのページは再配信)。Cosmos 版と同じで、handler は冪等 (delete / finalize) なので問題なし。
- **スキャン中の削除との相互作用**: keyset 方式は OFFSET と違い、行削除でページがずれない。
  自分が削除したレコードを再訪することもない。
- **並列化**: Cosmos は FeedRange 単位で並列だったが、RDBMS では汎用的なキーレンジ分割が難しい。
  v1 は「スキャン 1 スレッド + handler 側ワーカープール (ページ単位のバリア後にチェックポイント)」を推奨。
  ボトルネックは handler 側の DB roundtrip (delete/put) なので、これで実効的な並列性は確保できる。
- **cross_partition_scan フラグ**: Ledger/Auditor の properties には通常設定されていないので、
  スキャン用に Properties をコピーして 3 フラグを立て、スキャン専用の storage インスタンスを作る。

### 必要な変更箇所

1. `resumable-scan`: `jdbc` パッケージ追加
   - `JdbcResumableScanner` (ページングループ本体)
   - keyset 条件ビルダー (複合 PK の DNF 構築)
   - チェックポイントキーのシリアライザ (全キー型対応)
   - `JdbcResumableScannerConfig` (pageSize、handler 並列度)
   - `CheckpointManager` の共通化
2. `ResumableScannerFactory` と `common/StorageValidator` に `jdbc` を許可
   (`JdbcConfig.STORAGE_NAME`)、エラーメッセージ更新
3. テスト: 単体 + 統合 (PostgreSQL / MySQL は Docker で回せるので Cosmos より CI が楽)、
   ci/e2e と manifests の JDBC 版
4. ドキュメント (CLI ヘルプ、README)

## 難易度・工数感

- コア実装は Cosmos 版 (scanner + worker + serializer で 500 行弱 + テスト) と同等以下。
- **MySQL + PostgreSQL サポートで 1〜2 人週** (統合テスト・E2E 込み) 程度。
- Oracle / SQL Server / Db2 まで統合テストを広げる場合はテスト基盤分が上乗せ
  (実装は ScalarDB が吸収するので原理的には無変更で動くはず)。

## リスクと対処

1. **複合 PK の OR 条件のインデックス効率**: EXPLAIN ANALYZE で実測検証済み (下記「実測検証」参照)。
   素朴な DNF 形 `(id > ?) OR (id = ? AND age > ?)` は PostgreSQL でインデックス開始位置が
   使われず O(チェックポイント位置) の劣化を確認。ただし **冗長な下限 `id >= ?` を全 OR 枝に
   持たせた形なら MySQL / PostgreSQL とも最適プラン**になり、この形は ScalarDB の
   `whereOr` (DNF をそのまま指定) で生成できる (上記「追加検証」参照。当初案の `whereAnd`
   経由でも同じ SQL になるが、カラム数に対して条件が超指数的に増えるため不採用)。
   万一他 DB でこの形が効かない場合のフォールバックとして
   「`id = last AND age > lastAge` で境界パーティションを先に消化 → 次ページは `id > last` のみ」
   の 2 クエリ方式 (各クエリが単一 conjunction でどの DB でも自明にインデックスが効く) がある。
   なお対象 4 テーブル中 3 つは単一 PK なので OR 自体が不要 (`pk > ?` のみ)。
2. **MySQL の case-insensitive collation**: TEXT キーの `>` 比較が binary 順と一致しない懸念が
   あるが、PK 制約により collation 上等価な別キーは共存できないため、実在行に対する順序は
   strict total order になり正しさは保たれる。resume は常に同一 DB・同一クエリなので
   DB 間の順序差も問題にならない。
3. **スナップショット一貫性なし** (ページ毎に別クエリ): Cosmos 版も同じ前提
   (time-boundary + 冪等 handler) なので新規リスクではない。スキャン開始後に挿入された行が
   見える/見えないは不定だが、cleanup の削除境界は completion token の時刻で決まるため影響なし。

## 実測検証: 複合 PK keyset 条件のプラン (2026-08-21)

環境: PostgreSQL 16 / 13、MySQL 8.4 (Docker)。
テーブル `asset (id varchar(64), age int, payload text, PRIMARY KEY (id, age))`、
50,000 id × 10 age = 500,000 行。チェックポイントは中間地点 (id = 'asset-00025000', age = 5)、
`ORDER BY id, age LIMIT 1000`。

| WHERE の形 | PostgreSQL 16/13 | MySQL 8.4 |
|---|---|---|
| A. 素朴な DNF: `(id > l) OR (id = l AND age > a)` | ❌ Index Scan だが OR は Filter のみ。インデックス先頭から走査し 249,996 行捨てる (100ms, 252k buffers)。ページ毎コストが位置に比例 → 全体 O(N²) | ✅ range optimizer が 2 区間の index range union に変換、順序維持・即 LIMIT (0.75ms) |
| B. 冗長下限つき (採用形、`whereOr` で指定): `(id >= l AND id > l) OR (id >= l AND age > a)` | ✅ 全 OR 枝に共通の `id >= l` を Index Cond に括り出し、Filter 除去は境界パーティションの 6 行のみ (1.5ms, 1k buffers)。PG 13 でも同一プラン | ✅ 同じく range union (0.6ms) |
| C. row-value: `(id, age) > (l, a)` | ✅ ネイティブに Index Cond 化 (最速 0.65ms) | ❌ full index scan + Filter で 251k 行走査 (94ms) |

- 結論: **形 B が両 DB で最適かつ ScalarDB API で表現可能**。ただし渡し方は
  `whereOr(Set<AndConditionSet>)` で DNF を直接指定する (CNF を `whereAnd` に渡しても同じ SQL に
  なるが、cartesian product 展開により条件数が超指数的に増える。上記「追加検証」参照)。
- 形 C は PG では理想だが MySQL で最悪、かつ ScalarDB API で表現不可 → 不採用。
- ScalarDB の JDBC SelectQuery は conjunctions を `... OR ...` で連結する DNF 固定
  (SimpleSelectQuery)。よってツール側は「keyset 条件を DNF で組んで whereOr に渡す」だけでよい。

## 検討して見送った代替案

- **生 JDBC + RdbEngineStrategy 直接利用**: 方言処理・型変換を自前で持つことになり、
  ScalarDB 内部 API への依存も増える。ScanAll で足りるので不要。
- **1 本の長時間カーソル (streaming ResultSet)**: 再開にはどのみち keyset が必要な上、
  長時間トランザクション/タイムアウトのリスクがある。ページング方式が堅い。
- **OFFSET ページング**: 削除しながらのスキャンでずれる、深い OFFSET が遅い。不採用。
