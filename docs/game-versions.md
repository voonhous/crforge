# Game Versions

The game carries two version numbers, and they move apart:

- the **client version**, the version of the installed game client;
- the **data version** (also called the content version), the version of the game data that client loads. A set of game data is identified by its **content sha**, the `content_sha` in the header of every game table file.

## Pairs seen

Kind: `shipped` is the data set a client version comes with; `update` is a data set that client version was given later, without a client update.

| Client version | Data version | Content sha | Asset CDN label | Kind | First seen | Balance notes |
| --- | --- | --- | --- | --- | --- | --- |
| 16.402.12 | 16.402.2 | f26e4f9bf73e48ad6a92648279959030bfa2fd6a | not served | shipped | 2026-09-25 | not known |
| 16.402.12 | 16.402.15 | 67d4a2de5d141b69d86536850082a9ba5b937459 | 16.426.18 | update | 2026-09-25 | not known |
| 16.402.17 | 16.402.18 | 8aa8015226b0062c7e16a793522de91e564ffdaf | 16.426.20 | update | 2026-10-04 | not known |
| 16.402.17 | 16.402.19 | 7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec | 16.426.22 | update | 2026-10-06 | [Season 88 - RoyaleAPI](https://royaleapi.com/blog/season-88-balance-final-october-2026)<br>[Season 88 - riggedroyale](https://riggedroyale.com/blog/clash-royale-balance-changes-october-2026) |

## One data set, two labels

The data version in this table is the game client's label: the `version` of the client's own copy of the data set's fingerprint, the copy the game sends it. The asset CDN serves a copy of the same fingerprint, with the same files, under its own label (the "Asset CDN label" column), so one content sha has two version strings. The client's label is the one the client's own version checks use, and it is the one the game tables, `GameVersions` and the data repository's folders are named by. The content sha identifies the data under either label.

7e76080b5dc3b2cfaf74795093e4ac5e39cb61ec was first recorded here, and published in the data repository, as 16.426.22, the CDN's label. It was renamed 16.402.19 on 2026-10-07; its tables changed only in their `version` field.

## What a new data version on an unchanged client means

- The rules of the battle are the client's, so what the battle core models stays valid.
- The numbers and rows are the data's, so they may differ. A reference battle recorded on one data version is evidence for that data version only, and a replay made after the data moved plays on the new data. A replay's provenance names its content version and content hash; the replay tools refuse tables of another one.
- The simulator tracks one data version at a time (`version=` of `crforge-data.lock`). A newer row in the table above records that the data exists; it does not move the version under work.

## Client ranges: which client versions share the battle rules

The game updates clients in two ways:

- **Optional update.** The game offers a newer version, but a player can decline it, keep logging in on the older version, receive the new data and keep playing.
- **Required update.** Older clients can no longer log in until they update. A required update can come with a new patch of the same `major.minor` as well as with a new `major.minor`.

So at any time the game accepts a range of client versions, from a minimum to the newest. Players on different versions of that range meet in the same battle, and a battle has to play out the same for both of them, so the versions of one range share the battle rules. A change to the rules comes with a required update, which raises the minimum and starts a new range. This is inferred from how the game handles updates, not from comparing the clients' code.

What follows for the simulator:

- The battle rules are those of the client range, and the numbers are those of the data (the content sha). One studied client binary of a range stands for every version in it.
- A required update is where new rules can appear, and where a client binary of the new range has to be studied.
- `GameVersions.CLIENT_16_402_17` and `CLIENT_16_402_17_DATA` are named after the binary studied; they stand for the range below.

### Clients 16.402.12 to current

| Client version | Seen as | First seen |
| --- | --- | --- |
| 16.402.12 | a studied client binary | 2026-09-25 |
| 16.402.14 | an installed client on a test phone, offered the optional update on 2026-10-07 | 2026-10-07 |
| 16.402.17 | a studied client binary; still the App Store's version in the US on 2026-10-07 | 2026-10-04 |
| 16.402.20 | the App Store's version in most countries (released 2026-10-07 08:50 UTC) | 2026-10-07 |

On 2026-10-07 a 16.402.14 client was offered the newer version as an optional update; declining it, the client kept logging in, received data 16.402.19 and played TV replays.

## Keeping the table

- Add a row the first time a client version or a data version is seen, before any game tables or reference battles of it are made.
- Write "not known" for a value that was not recorded, never a guess.
- Take the data version from the client's copy of the fingerprint, never from the asset CDN's; put the CDN's label in its own column.
- Add a client version to its range's table when it is first seen, and record a required update as the start of a new range, with its own table.
