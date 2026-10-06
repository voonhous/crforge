# Game Versions

The game carries two version numbers, and they move apart:

- the **client version**, the version of the installed game client;
- the **data version** (also called the content version), the version of the game data that client loads. A set of game data is identified by its **content sha**, the `content_sha` in the header of every game table file.

The data moves without a client update: client 16.402.17 has run data 16.402.18 and then data 16.426.22. A recorded battle, a replay or a folder of game tables therefore belongs to one pair of client version and content sha, not to either version number alone. This page lists every pair seen, starting from client 16.402.12.

## Pairs seen

Kind: `shipped` is the data set a client version comes with; `update` is a data set that client version was given later, without a client update.

| Client version | Data version | Content sha | Kind | First seen | State here |
| --- | --- | --- | --- | --- | --- |
| 16.402.12 | 16.402.2 | f26e4f9bf73e48ad6a92648279959030bfa2fd6a | shipped | 2026-09-25 | Not used: no game tables, no reference battles. |
| 16.402.12 | 16.402.15 | 67d4a2de5d141b69d86536850082a9ba5b937459 | update | 2026-09-25 | Not used: no game tables, no reference battles. |
| 16.402.17 | 16.402.18 | 8aa8015226b0062c7e16a793522de91e564ffdaf | update | 2026-10-04 | **The data version under work**: `version=` of `crforge-data.lock`, with its game tables and reference battles in the data repository and its expectations in `parity/reference-expectations/16.402.18.json`. |
| 16.402.17 | 16.426.22 | 7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec | update | 2026-10-06 | The data the live game runs since 2026-10-06. Not used yet: no game tables, no reference battles. Against 16.402.18 it changes 24 data files: balance changes to 15 cards, a new unit for Goblin Barrel and a new action chain for the Tombstone hero. |

## What a new data version on an unchanged client means

- The rules of the battle are the client's, so what the battle core models stays valid.
- The numbers and rows are the data's, so they may differ. A reference battle recorded on one data version is evidence for that data version only, and a replay made after the data moved plays on the new data. A replay's provenance names its content version and content hash; the replay tools refuse tables of another one.
- The simulator tracks one data version at a time (`version=` of `crforge-data.lock`). A newer row in the table above records that the data exists; it does not move the version under work.

## Keeping the table

- Add a row the first time a client version or a data version is seen, before any game tables or reference battles of it are made.
- Exactly one row is the data version under work. Move the mark in the change that moves `version=` in `crforge-data.lock`.
- Update a row's state when game tables or reference battles of it are added to the data repository.
- Write "not known" for a value that was not recorded, never a guess.
