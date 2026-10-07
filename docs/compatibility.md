# Compatibility

Which game versions the simulator's battle rules hold for: the client it follows, the client versions that share its rules, and the data it runs. The versions the game has shipped are recorded in [Game Versions](game-versions.md).

## Client ranges: which client versions share the battle rules

The game updates clients in two ways:

- **Optional update.** The game offers a newer version, but a player can decline it, keep logging in on the older version, receive the new data and keep playing.
- **Required update.** Older clients can no longer log in until they update. A required update can come with a new patch of the same `major.minor` as well as with a new `major.minor`.

So at any time the game accepts a range of client versions, from a minimum to the newest. Players on different versions of that range meet in the same battle, and a battle has to play out the same for both of them, so the versions of one range share the battle rules. A change to the rules comes with a required update, which raises the minimum and starts a new range. This is inferred from how the game handles updates, not from comparing the clients' code.

It follows for the data too. The game only serves a data version to clients that can play it, so every data version served while a range is live runs on that range's rules: the data versions compatible with a client are the ones served to any client of its range, from the range's start until a required update ends it.

## Reference client: 16.402.17

The simulator's battle rules follow client 16.402.17 (`GameVersions.CLIENT_16_402_17`). What it is compatible with:

### Client range: 16.402.12 to current

| Client version | Seen as | First seen |
| --- | --- | --- |
| 16.402.12 | a studied client binary | 2026-09-25 |
| 16.402.14 | an installed client on a test phone, offered the optional update on 2026-10-07 | 2026-10-07 |
| 16.402.17 | the reference client | 2026-10-04 |
| 16.402.20 | the App Store's version in most countries (released 2026-10-07 08:50 UTC) | 2026-10-07 |

Every data version served to a client of this range is compatible; the pairs are in [Game Versions](game-versions.md). `GameVersions.CLIENT_16_402_17_DATA` lists the ones the simulator has game tables for (16.402.18 and 16.402.19).

## Keeping this page

- Add a client version to the reference client's range when it is first seen.
- A new data version goes into the pairs table of [Game Versions](game-versions.md); it is compatible when it is served to a client of the range.
- A required update closes the reference client's range: record it, and start a new reference client section for the new range when the simulator moves to it.
