# Version Mod Sorter

Minecraftのバージョンごとに、対応するMODだけを読み込ませるMOD。Fabric・Forge・NeoForgeに対応しています。

`mods/` の中にローダーとバージョンのフォルダ（例: `mods/fabric/1.20.4/`）を作り、そこにMODを入れておくと、起動中のローダーとバージョンに一致するフォルダのMODだけが読み込まれます。複数のバージョンやローダーを使い分けていても、MODを整理しておけます。

## 動作環境

- Fabric Loader 0.12.0 以降 / Forge 41.1.0（Minecraft 1.19）以降 / NeoForge 20.5（Minecraft 1.20.5）以降
- 対応ローダーが動作するMinecraftのバージョンであれば、MCのバージョンごとに別のjarを用意する必要はありません。1つのjarで動作します。

## 導入

[Releases](../../releases)から最新の `version-mod-sorter-x.x.x.jar` をダウンロードし、`mods` フォルダに入れます。同じjarをFabric・Forge・NeoForgeのいずれでもそのまま使えます。

## 使い方

1. 一度ゲームを起動すると、`mods/<ローダー>/<バージョン>/`（例: `mods/fabric/1.20.4/`）が自動で作成されます。ローダー名は `fabric`・`forge`・`neoforge` です。
2. そのフォルダに、そのバージョンで使いたいMODの `.jar` を入れます。
3. もう一度起動すると、フォルダ内のMODが読み込まれます。

フォルダ構成は次のようになります。

```
mods
├─ fabric
│  └─ 26.1.2
│     ├─ ModA.jar
│     └─ ModB.jar
├─ forge
│  ├─ 26.1
│  │  └─ ModC.jar
│  └─ 1.21.4
│     └─ ModD.jar
└─ neoforge
   ├─ 1.21.4
   │  └─ ModE.jar
   └─ 1.20.6
      └─ ModF.jar
```

![mods フォルダの例](images/mods-folder.png)

フォルダが空の間は何も起きず、通常どおり起動します。

## 仕組み

起動時に実行中のローダーとMinecraftバージョンを判定し、`mods/<ローダー>/<バージョン>/` のMODを読み込みます。読み込みには各ローダー公式の仕組み（Fabricの `fabric.addMods`、Forge・NeoForgeのMOD探索API）を使い、Minecraftのクラスやバイトコードの書き換えには一切触れていません。そのため1つのjarがどのローダー・バージョンでも動作します。

## テスト済み環境

- Windows 11 — Fabric Loader 0.19.2 / Forge 44.1.23（MC 1.19.3）/ Forge 54.1.16（MC 1.21.4）/ Forge 62.0.9（MC 26.1）/ Forge 64.0.8（MC 26.1.2）/ NeoForge 21.4.157 / NeoForge 26.1.2.66-beta
- macOS（Apple M2） — Fabric Loader 0.19.2（MC 1.20.4）

## 注意点

- フォルダ名のバージョンは、ローダーが報告するMinecraftのバージョン名と一致させる必要があります。
- `mods/<ローダー>/<バージョン>/` のMODは、通常の `mods/` 直下のMODに加えて読み込まれます。
- Fabricでは、読み込むMODがある場合に起動時のプロセスを一度だけ起動し直すため、起動が二段階になります。Forge・NeoForgeでは起動し直しは行いません。
- 各ローダーの内部実装を利用しているため、ローダーの大型アップデートで読み込みが機能しなくなる場合があります。その場合もMinecraft本体やローダーの書き換えは行っていないため、本MODが働かなくなるだけで、ゲーム自体やほかのMODへの影響はありません。

## 問い合わせ

不具合の報告や質問は [Issues](../../issues) からお願いします。
