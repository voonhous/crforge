# Game tables and reference battles

The battle core reads the game's own tables, which this repository does not ship: they are built on your machine from the game's files (see [Building the game tables](#building-the-game-tables)). The battle core is held to recorded reference battles kept in the game data repository.

## The game tables

A folder of game tables holds one JSON file per table of the game's data - `characters`, `buildings`, `projectiles`, `character_buffs`, `area_effect_objects`, the spells tables (`spells_characters`, `spells_buildings`, `spells_other`, `spells_evolved`, `spells_hero_form`), `rarities`, `character_abilities`, `game_object_filters`, `variables`, `damage_types`, `shapes`, `globals`, `locations`, `arenas` (from client 16.635.1 on: the arena rows a replay's players name), `exp_levels` (from client 16.635.1 on: the summoner level a challenge's kings and towers stand at), `game_modes`, `battle_timelines`, `card_groups`, `support_cards`, `support_rarities`, `spawn_groups`, `target_resolvers`, `game_tags` - and `actions.json`, all of one data version. Beside them, under `tilemaps/`, are the data version's tile maps (`tilemap.csv` and the others the locations name by `TileDataFileName`), each the game's file decoded to text: the battle core reads none of them, but its tests hold its bundled standard arena map to `tilemaps/tilemap.csv`.

A table file is a header (`table`, `id`, `version`, `content_sha`) and its `rows` by name in creation order, each with its `index`, its `class` and its `columns` under the game's own names. Values are as the game reads them: integer milliseconds, game units (1000 per tile), the published speed column, references as row names. A game tag's `index` is its bit in an object's tag word.

`org.crforge.core.battle.data.GameTables`, in the core module beside the battle it serves, reads such a folder. The tests read the folder the build makes for them (see [Building the game tables](#building-the-game-tables)), which the build names by the system property `crforge.gameTables`.

### The data lock and CI

In CI the game data repository, which is private, is checked out when the repository variable `GAME_TABLES_REPOSITORY` and the secret `GAME_TABLES_KEY` are set. `GAME_TABLES_KEY` is the private half of a read-only deploy key on that repository. The repository is checked out at the commit `crforge-data.lock` (repository root) names, so a change and the data it was built against always agree. Move the lock in the change that needs newer data.

The lock names the data versions:

- `version`: the data version under work, which the tests and the debug visualizer use by default.
- `compatible`: every data version CI tests: the data versions served to the client range the battle follows ([Compatibility](compatibility.md)), `version` among them.

These data versions share the client's battle rules, so CI runs the unit tests (`./gradlew test`) on the tables of each one, built from the repository's copy of the asset files, and the reference battles (below) of each one that has them. The unit tests hard-code no game data, so they pass on each compatible version unchanged. Run them locally on another one with `-Pcrforge.dataVersion=<version>`.

A pull request from a fork gets no secrets, so its build stops for want of an asset source.

## The client schema

The game tables are the game's own files read the way the game client reads them. These are rules of the client, not of the data, so they hold for every data version that client is served:

- which file feeds which table
- which data assets of the game's asset database the client loads after its files
- which section type goes to which table
- which class a row is
- which columns name a row of another table
- how a column is read
- when an `[EXT.Name]` row is linked to its base

The `tables` module keeps them, one file per client version: `tables/src/main/resources/org/crforge/tables/schema/<client version>.json`, read by `org.crforge.tables.ClientSchema`. It holds the facts alone, none of the game's data:

| Part | What it says |
| --- | --- |
| `registrations` | the client's data load in order: each registration's kind, number and the files it loads (one the client makes twice is listed twice) |
| `asset_database` | the asset database's file in a content set, the config asset the client reads from it after the manifest files, the config's list of data assets, and the asset types of the config and of a listed data asset; absent from a schema whose tables are read from the data files alone |
| `type_tokens` | the table each `[TOKEN.Name]` section type goes to |
| `ext_link` | when an EXT row is linked to its base: `at creation`, or `deferred` (on a later section that makes or extends the base, else after the last file) |
| `classes` | every class of the client's data: its parent, whether its tables pick the class by `ClassType`, the `ClassType` that selects it, its tables, and the fields that name a row of another class |
| `table_classes` | the classes the client builds the rows of each table with |
| `loader_kinds` | per class and table, the kind each column is read as when no CSV declares it |
| `patch_targets` | the patch tables and the table each patches |
| `spell_tables`, `combined_characters`, `reference_tables`, `array_read_csv_columns` | the spell tables, the characters-then-buildings lookup of an EXT base, the reference columns with a fixed target table, and the CSV columns read with the array reader |
| `export_tables`, `global_id_types` | the tables written as game tables, by file name, and the ones whose rows carry a global id |

A schema and the game's files of a data version are all the table decoder (below) reads.

## Building the game tables

The `tables` module builds the game tables on your machine from the game's own files, so that this project never ships the game's data. There is one way to get the tables, with no fallback: the build makes them, and every task that needs them reads them from the cache. It fetches nothing until you name where the files come from, with the Gradle property `crforge.assetSource` (`-Pcrforge.assetSource=...`, or a line in `~/.gradle/gradle.properties`):

- `crforge.assetSource=cdn`: the game's asset CDN. This is an opt-in: the CDN is never a default.
- `crforge.assetSource=file:///path/to/crforge-data/cdn/`: a local copy laid out as the CDN, such as the `cdn/` folder of the game data repository.

Without it, any task that needs the tables stops at the task that builds them (`:tables:gameTables` or `:tables:dataVersionTables`, below) and names the setting.

`./gradlew test`, `./gradlew build`, `:desktop:run` and `:desktop:uiSmoke` depend on `:tables:gameTables` (a second or two the first time, nothing after). It builds the data version (`-Pcrforge.dataVersion=<version>`, by default the lock's `version=`) and every version the lock lists as `compatible`, each in its own folder beside the others, as some tests and the visualizer's `V` key read them.

The tasks that read the data version's tables alone, the reference battles (`:conformance:referenceTest`), `updateReferenceExpectations`, `coverageScore` and `tickBenchmark`, depend on `:tables:dataVersionTables` instead. It builds that one version with the same settings, so a CI job that scores one version builds no other.

```
./gradlew -q :tables:gameTables
```

builds them alone and prints their folders.

- **Where the files come from.** The game's asset CDN serves a data version's files under its content sha: `<content sha>/fingerprint.json`, the list of every file with its SHA-1, and `<content sha>/<path>`. An asset source is a base URI laid out the same way. Only the files the client's table loaders read are fetched: those under `csv_logic/` and `csv_client/`, and `data_manifest.toml`; the tile maps under `tilemaps/`, written beside the tables; and the asset database (`assets.scdb`) when the client's schema names it.
- **The cache.** `~/.crforge`, or the folder the Gradle property `crforge.cache` names. A fetched file is kept under `assets/` by its SHA-1, so it is fetched once, and a file shared by several data versions is kept once. A file that does not match the SHA-1 its fingerprint lists is refused, naming it. The asset database is the exception: the value its fingerprint lists is not the SHA-1 of the file served, so it is kept under that value and checked with the hash the file carries (below). The tables of a data version go to `tables/<data version>/`, with a `build.properties` naming the content sha, the client version, the SHA-256 of its client schema and the decoder they were built with. A folder built the same way is used again as it is; a schema that has gained a table or a column since builds it again.
- **The data versions.** The decoder names a data version by the game client's label, never the CDN's (one content sha has both, see [Game Versions](game-versions.md)). `tables/src/main/resources/org/crforge/tables/data-versions.json` pairs each data version with its client version, whose schema reads it, and its content sha.
- **How the files are read.** A file as served is 5 bytes of LZMA properties, the 4-byte little-endian size of the text, then an LZMA1 stream. The decoder (`org.crforge.tables.TableBuild`) stacks each row's layers in the client's load order (`LoadModel`), resolves them as the client does (`TableResolver`: EXT inheritance at the schema's link points, operators, the patch tables, inline rows) and writes the tables (`TableExport`), each JSON file as these tables have always been written, and each tile map as its decoded text, under its own path.
- **The asset database.** `assets.scdb` as served is a container: the 4 bytes `scdb`, then little-endian 32-bit values (the container version 1, the compression 1 for zstd, the compressed size, the database's size), the 16-byte XXH3 128-bit hash of the database (its low, then its high 64 bits, little-endian), then one zstd frame of the database (`AssetDatabase`). The database is SQLite: its table `meta` names each asset's path, asset type and uuid, and its table `asset` holds each asset's `data`, `extref` and `format` (0 for JSON text). After the manifest files the client reads the config asset the schema names (`logic/chaos_arena/chaos_config` for 16.635.1) and loads each data asset it lists (written `<path> | <uuid>`), in list order, the way it loads a manifest file: the data asset's `data` object holds sections keyed by type token, so a `FILTER` section is a row of `game_object_filters`, an `EXT` section an inheriting row linked by the same rule as a file's, and so on. Their rows come after every row of the files, in that order; the patch tables and inline rows still come last. A listed asset the database lacks, or one of a format the client loads nothing for, adds nothing. The decoder refuses what it does not read rather than read it another way: an asset in the binary form (format 1), an asset whose data lies outside the database, a listed asset of another type, a listing whose path and uuid name different assets, and JSON with a repeated key or an escape.

The decoder is held to the game data repository: for each data version it knows, the tables it builds from the repository's copy of the asset files (`cdn/`) are the repository's tables, byte for byte (`TableBuildReferenceTest`, given `-Pcrforge.dataRoot=<data checkout>`). CI sets its asset source to that copy, so every job's tables are built from it. It never reaches the game's CDN.

## Reference battles

The same repository holds recorded reference battles under `references/<version>/`:

- `battles/`: one folder per battle (`scenario.json`, `reference.json` and the observation trace, zstd-compressed `observations.jsonl.zst`). `trace_sha256` names the uncompressed trace. The coverage sets store the trace as its delta lines instead ([Delta traces](../conformance/README.md#delta-traces)).
- `corpora/*.json`: listing the cases.

`./gradlew :conformance:referenceTest -Pcrforge.references=<data checkout>/references/<version>` runs every case in one JVM, in parallel, through the same code path as the `conformance` command line run. It compares each trace with its reference observation by observation, and holds each case's outcome to `conformance/reference-expectations/<version>.json`. The outcome is one of:

- `diagnostic_match`
- `mismatch` with its first divergent observation and field
- `unsupported` with the refusal
- `invalid` with the error

Any difference fails, a case that newly matches included. So a change that moves an outcome updates that file with `./gradlew :conformance:updateReferenceExpectations`, and the diff is reviewed with it.

Without a references folder the task is skipped. `./gradlew test` never runs it. The tables and the references must be of one content version (the task refuses a mismatch), so name the version too: `-Pcrforge.dataVersion=<version> -Pcrforge.references=<data checkout>/references/<version>`.

CI runs the reference battles of each version in the lock's `compatible` that has them at the locked commit, each held to its own expectations file. `-Pcrforge.references.shard` / `-Pcrforge.references.shards` run one shard of the cases (CI splits the cases over a job matrix). Each run writes a scorecard of every case to `conformance/build/reference-scorecard/`.

`diagnostic_match` is agreement with a recorded reference battle on the observation schema, not a release pass.

## Records

`org.crforge.core.battle.data.BattleRecords` builds the battle core's records from those rows, every field the column of the same name in the column's own units:

- a unit or building (`UnitData`) from the characters or buildings table
- a projectile (`ProjectileData`) from the projectiles table
- a troop card (`DeployCard`) from the spells characters table

Three fields are not a column:

- a unit flies when its `FlyingHeight` is above 0
- a projectile's damage scaling rule is the one its `DamageScalingMode` names (`KingTower`, `PrincessTower`, else the card rule)
- a rarity is the published row of that name

The battle tests take their units and cards from it.
