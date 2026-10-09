# Game tables and reference battles

The battle core reads the game's own tables, which this repository does not ship: they are built on your machine from the game's files (see [Building the game tables](#building-the-game-tables)). The battle core is held to recorded reference battles kept in the game data repository.

## The game tables

A folder of game tables holds one JSON file per table of the game's data - `characters`, `buildings`, `projectiles`, `character_buffs`, `area_effect_objects`, the spells tables, `rarities`, `character_abilities`, `game_object_filters`, `variables`, `damage_types`, `shapes`, `globals`, `locations`, `game_tags` - and `actions.json`, all of one data version. A table file is a header (`table`, `id`, `version`, `content_sha`) and its `rows` by name in creation order, each with its `index`, its `class` and its `columns` under the game's own names. Values are as the game reads them: integer milliseconds, game units (1000 per tile), the published speed column, references as row names. A game tag's `index` is its bit in an object's tag word.

`org.crforge.core.battle.data.GameTables`, in the core module beside the battle it serves, reads such a folder. It is named by the Gradle property `crforge.gameTables` (`./gradlew test -Pcrforge.gameTables=<dir>`, or a line in `~/.gradle/gradle.properties`) or the environment variable `CRFORGE_GAME_TABLES`; the tests that need the real tables fail when neither is set, naming the two settings. In CI a private repository of them (the game data repository) is checked out when the repository variable `GAME_TABLES_REPOSITORY` and the secret `GAME_TABLES_KEY` - the private half of a read-only deploy key on that repository - are set. It is checked out at the commit `crforge-data.lock` (repository root) names, so a change and the data it was built against always agree; move the lock in the change that needs newer data. The lock names version folders in that commit: `version`, the data version under work, which the debug visualizer opens by default, and `compatible`, every data version CI tests: the data versions served to the client range the battle follows ([Compatibility](compatibility.md)), `version` among them. They share the client's battle rules, so CI runs the unit tests (`./gradlew test`) on the tables of each one and the reference battles (below) of each one that has them. The unit tests hard-code no game data, so they pass on each compatible version unchanged; run them locally with `-Pcrforge.gameTables=<data checkout>/<version>`. A pull request from a fork gets no secrets, so those tests fail in its run.

## The client schema

The game tables are the game's own files read the way the game client reads them. Which file feeds which table, which section type goes to which table, which class a row is, which columns name a row of another table, how a column is read and when an `[EXT.Name]` row is linked to its base are rules of the client, not of the data, so they hold for every data version that client is served. The `tables` module keeps them, one file per client version: `tables/src/main/resources/org/crforge/tables/schema/<client version>.json`, read by `org.crforge.tables.ClientSchema`. It holds the facts alone, none of the game's data:

| Part | What it says |
| --- | --- |
| `registrations` | the client's data load in order: each registration's kind, number and the files it loads (one the client makes twice is listed twice) |
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

The `tables` module builds a folder of game tables on your machine from the game's own files, so that this project never ships the game's data:

```
./gradlew -q :tables:gameTables
```

prints the folder of the tables of the data version under work (`version=` of `crforge-data.lock`); name it as `crforge.gameTables`. `-Pcrforge.dataVersion=<version>` builds another data version the decoder knows.

- **Where the files come from.** The game's asset CDN serves a data version's files under its content sha: `<content sha>/fingerprint.json`, the list of every file with its SHA-1, and `<content sha>/<path>`. By default they are fetched from there. `-Pcrforge.assetSource=<URI>` (or `CRFORGE_ASSET_SOURCE`) names another source laid out the same way; a `file:` URI names a local copy, such as the `cdn/` folder of the game data repository. Only the files the client's table loaders read are fetched: those under `csv_logic/` and `csv_client/`, and `data_manifest.toml`.
- **The cache.** `~/.crforge`, or the folder `crforge.cache` (`CRFORGE_CACHE`) names. A fetched file is kept under `assets/` by its SHA-1, so it is fetched once, and a file shared by several data versions is kept once. A file that does not match the SHA-1 its fingerprint lists is refused, naming it. The tables of a data version go to `tables/<data version>/`, with a `build.properties` naming the content sha, the client schema and the decoder they were built with; a folder built the same way is used again as it is.
- **The data versions.** The decoder names a data version by the game client's label, never the CDN's (one content sha has both, see [Game Versions](game-versions.md)): `tables/src/main/resources/org/crforge/tables/data-versions.json` pairs each data version with its client version, whose schema reads it, and its content sha.
- **How the files are read.** A file as served is 5 bytes of LZMA properties, the 4-byte little-endian size of the text, then an LZMA1 stream. The decoder (`org.crforge.tables.TableBuild`) stacks each row's layers in the client's load order (`LoadModel`), resolves them as the client does (`TableResolver`: EXT inheritance at the schema's link points, operators, the patch tables, inline rows) and writes the tables (`TableExport`), each JSON file as these tables have always been written.

The decoder is held to the game data repository: for each data version it knows, the tables it builds from the repository's copy of the asset files (`cdn/`) are the repository's tables, byte for byte (`TableBuildReferenceTest`, given `-Pcrforge.dataRoot=<data checkout>` or `CRFORGE_DATA_ROOT`; CI runs it on every build, reading only that copy, never the game's CDN).

## Reference battles

The same repository holds recorded reference battles under `references/<version>/`: one folder per battle under `battles/` (`scenario.json`, `reference.json` and the gzipped observation trace `observations.jsonl.gz`) and `corpora/*.json` listing the cases. `./gradlew :conformance:referenceTest -Pcrforge.references=<data checkout>/references/<version>` (or `CRFORGE_REFERENCES`) runs every case in one JVM, in parallel, through the same code path as the `conformance` command line run, compares each trace with its reference observation by observation, and holds each case's outcome (`diagnostic_match`, `mismatch` with its first divergent observation and field, `unsupported` with the refusal, or `invalid` with the error) to `conformance/reference-expectations/<version>.json`. Any difference fails, a case that newly matches included, so a change that moves an outcome updates that file with `./gradlew :conformance:updateReferenceExpectations` and the diff is reviewed with it. Without a references folder the task is skipped; `./gradlew test` never runs it. The tables and the references must be of one content version (the task refuses a mismatch), so name both: `-Pcrforge.gameTables=<data checkout>/<version> -Pcrforge.references=<data checkout>/references/<version>` (a property wins over the variable, so a `crforge.gameTables` in `~/.gradle/gradle.properties` needs the property here). CI runs the reference battles of each version in the lock's `compatible` that has them at the locked commit, each held to its own expectations file. `CRFORGE_REFERENCES_SHARD` / `CRFORGE_REFERENCES_SHARDS` run one shard of the cases (CI splits the cases over a job matrix), and each run writes a scorecard of every case to `conformance/build/reference-scorecard/`. `diagnostic_match` is agreement with a recorded reference battle on the observation schema, not a release pass.

## Records

`org.crforge.core.battle.data.BattleRecords` builds the battle core's records from those rows: a unit or building (`UnitData`) from the characters or buildings table, a projectile (`ProjectileData`) from the projectiles table and a troop card (`DeployCard`) from the spells characters table, every field the column of the same name in the column's own units. Three fields are not a column: a unit flies when its `FlyingHeight` is above 0, a projectile's damage scaling rule is the one its `DamageScalingMode` names (`KingTower`, `PrincessTower`, else the card rule), and a rarity is the published row of that name. The battle tests take their units and cards from it.
