# Examples

## Replays

[replays/ladder.json](replays/ladder.json) is a replay file the replay viewer opens. Run from the repository root:

```bash
./gradlew :desktop:run -Pcrforge.assetSource=<source> --args="--replay $PWD/examples/replays/ladder.json"
```

You can also drop the file on the window of the debug screen. See [Watching a replay](../README.md#watching-a-replay) for the keys and [Replays](../docs/architecture.md#replays) for the format.

The replay is a whole Ladder battle the battle core played between the debug screen's two decks (`BattleDecks`: an evolution and two heroes on the blue side), with a fixed script for both sides. It is not a battle recorded in the game and holds no player's data: the players are named `Side 0` and `Side 1`. The script (`org.crforge.desktop.replay.ExampleReplay`):

- each side waits until it has 9 elixir, then plays the first card of its hand it may play now
- a card that may be placed in the other side's half (a spell, the Miner) is aimed at the other side's tower nearest the side's lane; any other card is placed in that lane, 3 tiles in front of the side's princess tower there. Both sides use the same lane, the one at the low end of the arena's width, so their plays meet
- a hero's ability is used as soon as it may be
- the battle runs until its match is over

The file is a replay of one data version, the one the build made its tables of when it was written (its `capture` block names it). It plays only on that version's tables: the default build makes them, as the file follows the data version the repository is locked to. A data version bump rewrites it:

```bash
./gradlew :desktop:exampleReplay -Pcrforge.assetSource=<source>
```

`ExampleReplayTest` fails while the shipped file is not the battle the script plays on the lock's version.
