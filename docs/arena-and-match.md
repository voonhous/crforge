# Arena, Match & Economy

> Part of the [Architecture Reference](architecture.md).

---

## Arena and Placement

The arena is an 18x32 tile grid. Each tile is 1.0 world unit.

- `Arena.WIDTH = 18`, `Arena.HEIGHT = 32`, `Arena.TILE_SIZE = 1.0f`
- River at center: `Arena.RIVER_Y = 16` (rows Y=15 and Y=16 are river)
- Two bridges: left at X=[2,5), right at X=[13,16), each `BRIDGE_WIDTH = 3` tiles

### Tile Types

| Type      | Walkable | Placeable       | Notes                                          |
|-----------|----------|-----------------|------------------------------------------------|
| GROUND    | yes      | yes             | General terrain                                |
| BLUE_ZONE | yes      | yes (blue only) | Blue deployment area (Y < 15)                  |
| RED_ZONE  | yes      | yes (red only)  | Red deployment area (Y > 16)                   |
| BRIDGE    | yes      | no              | River crossings                                |
| RIVER     | no       | no              | Impassable except via bridge/jump              |
| TOWER     | yes      | no              | Tower footprints                               |
| BANNED    | no       | no              | Edges behind king towers (Y=0/31, X<6 or X>11) |

### Tower Positions

| Tower             | Center       | Footprint                 |
|-------------------|--------------|---------------------------|
| Crown (Blue)      | (9.0, 3.0)   | 4x4 at X[7-10], Y[1-4]    |
| Crown (Red)       | (9.0, 29.0)  | 4x4 at X[7-10], Y[27-30]  |
| Princess L (Blue) | (3.5, 6.5)   | 3x3 at X[2-4], Y[5-7]     |
| Princess R (Blue) | (14.5, 6.5)  | 3x3 at X[13-15], Y[5-7]   |
| Princess L (Red)  | (3.5, 25.5)  | 3x3 at X[2-4], Y[24-26]   |
| Princess R (Red)  | (14.5, 25.5) | 3x3 at X[13-15], Y[24-26] |

### Placement Validation

- `Arena.isValidPlacement(x, y, team)` -- single-tile center-point check; must be in own zone
- `Arena.isValidBuildingPlacement(x, y, radius, team)` -- validates entire footprint bounding box
- `Match.validateAction()` performs card-specific rules:
    - Spells with `spellAsDeploy=true`: must be in own zone
    - Spells without `spellAsDeploy`: can target anywhere (unless restricted by
      `canPlaceOnBuildings`)
    - Cards with `canDeployOnEnemySide=true`: skip zone check but not BANNED/TOWER
    - Buildings: full footprint validation
    - Mirror: validates as the card it replays
- `Arena.freePrincessTowerTiles()` converts destroyed princess tower tiles back to zone type,
  re-enabling deployment on that footprint

**Key files:**

- `core/.../arena/Arena.java`
- `core/.../arena/TileType.java`
- `core/.../match/Match.java`

---

## Match and Win Conditions

`Match` is the abstract base; `Standard1v1Match` is the primary implementation.

### Timing

| Phase        | Duration  | Ticks           |
|--------------|-----------|-----------------|
| Regular time | 3 minutes | 5400 (180 * 30) |
| Overtime     | 2 minutes | 3600 (120 * 30) |

### Win Conditions

- **Crown tower destruction**: Game ends immediately when a Crown Tower is destroyed. The destroying
  team wins with 3 crowns.
- **Regular time expires**: Crown counts compared. Higher crown count wins. Tied -> overtime begins.
- **Overtime expires**: Crown counts compared. If tied, total remaining tower HP compared. If still
  tied -> draw.
- Crowns: each destroyed Princess Tower = 1, destroyed Crown Tower = 3
- `Match.isEnded()` returns `true` if `winner != null || draw`
- `GameState.processDeaths()` checks win condition on every tower death

### Overtime

- `Match.enterOvertime()` sets `overtime=true` on all players
- Doubles elixir regen rate (see Elixir section below)

### Game Modes

`GameMode` enum defines: `STANDARD_1V1`, `MATCH_2V2`, `DOUBLE_ELIXIR`, `TRIPLE_ELIXIR`,
`SUDDEN_DEATH`. Only STANDARD_1V1 is implemented.

**Key files:**

- `core/.../match/Standard1v1Match.java`
- `core/.../match/Match.java`
- `core/.../match/GameMode.java`
- `core/.../engine/GameState.java` (win condition in `processDeaths()`)

---

## Players and Economy

### Elixir

- `Elixir.MAX_ELIXIR = 10.0f`
- Normal regen: 1 elixir per `REGEN_PERIOD_NORMAL = 2.8s` (rate ~0.357/s)
- Overtime regen: 1 elixir per `REGEN_PERIOD_OVERTIME = 1.4s` (rate ~0.714/s, 2x)
- Starting elixir: 5.0
- `Elixir.update(deltaTime)` adds `rate * deltaTime`, clamped to max
- `Elixir.spend(cost)` deducts if sufficient, returns boolean success

### Elixir Collector Buildings

- `ElixirCollectionSystem` ticks buildings with `ElixirCollectorComponent`
- Collection timer fires every `manaGenerateTime` seconds, granting `manaCollectAmount` elixir
- If owner at max elixir: enters hold state (timer pauses) until owner has room
- Stunned/frozen buildings do not tick their timer
- On death: `manaOnDeath` grants remaining stored elixir to owner

### Deck and Hand

- `Deck.SIZE = 8` cards per deck
- `Hand.HAND_SIZE = 4` active slots + 1 next card + draw pile (cycle queue)
- Initialization: shuffle deck, ensure Mirror cannot appear in starting hand (first 5 cards), deal
  4 + set next
- `Hand.playCard(slotIndex)`: returns card, adds it to back of cycle, moves nextCard into vacated
  slot, draws new nextCard

### Mirror Resolution

- `Player.tryPlayCard()` handles Mirror specially:
    - Requires `lastPlayedCard != null` and it is not Mirror itself
    - Cost = `min(lastPlayedCard.cost + 1, 10)`
    - Mirror level = `min(mirrorLevel + 1, MAX_CARD_LEVEL)`
    - Returns `lastPlayedCard` (the card to replay)
    - Does NOT update `lastPlayedCard` (Mirror chains don't stack)

### Player Structure

- `Team` enum: `BLUE` (bottom half) and `RED` (top half) with `opposite()` method
- `LevelConfig`: default level + per-card overrides via `Map<String, Integer> cardOverrides`
- `LevelConfig.standard()` returns level 1 (no scaling)

**Key files:**

- `core/.../player/Elixir.java`
- `core/.../player/Hand.java`
- `core/.../player/Deck.java`
- `core/.../player/Player.java`
- `core/.../player/LevelConfig.java`
- `core/.../engine/ElixirCollectionSystem.java`
