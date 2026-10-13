# 既知の問題: スキャナのスレッド名フォーマットがテーブル名の `%` で壊れる

日付: 2026-08-24
発見経緯: JDBC resumable scan 実装時のコードレビュー
対応方針: **JDBC 対応とは別に修正する**。Cosmos 側にも同じ問題があるため、両方まとめて直すのが妥当。

## 問題

スキャンワーカープールのスレッド名を、修飾テーブル名をフォーマット文字列に連結して作っている。

- `scalardl-cleanup/resumable-scan/src/main/java/com/scalar/dl/tools/scan/jdbc/JdbcResumableScanner.java`(`doScan`)
  ```java
  .setNameFormat("jdbc-scan-" + qualifiedTableName + "-%d")
  ```
- `scalardl-cleanup/resumable-scan/src/main/java/com/scalar/dl/tools/scan/cosmos/CosmosResumableScanner.java`(`doScan`)
  ```java
  .setNameFormat("cosmos-scan-" + qualifiedTableName + "-%d")
  ```

名前空間名またはテーブル名に `%` が含まれると、フォーマット文字列が壊れる。Guava の
`ThreadFactoryBuilder.setNameFormat` は即座に検証するため、**スキャンを1件も実行する前に**例外で落ちる。

実測(Guava, ThreadFactoryBuilder):

| テーブル名 | 結果 |
|---|---|
| `ns.normal` | ok (`jdbc-scan-ns.normal-0`) |
| `ns.pct%tbl` | `IllegalFormatConversionException` |
| `ns.a%db` | `MissingFormatArgumentException` |

## 影響

ledger-finalize-records は ScalarDL Functions 用の**任意のユーザーテーブル**を走査対象にするため、
`%` を含むテーブル名が現れうる。その場合:

1. テーブル名も真の原因も示さない文字列フォーマット例外で実行が停止する
2. 対象テーブル一覧は初回実行で state.json に固定されるため、**再実行しても同じ場所で必ず落ちる**

つまり、主キー構成の前検証([実装プラン](../jdbc-resumable-scan/implementation-plan.md) の R6)で防ごうとした「詰み」と同じ形が、検証を通過した後に発生する。

## 修正案

いずれか:

- 連結前に `qualifiedTableName.replace("%", "%%")` でエスケープする
- スレッド名のフォーマットからテーブル名を外す(固定プレフィックス + 連番のみ)

Cosmos / JDBC 両方のスキャナに同じ修正を入れること。回帰テストとして、`%` を含むテーブル名で
スキャナを構築しても例外が出ないことを確認するケースを追加するとよい。
