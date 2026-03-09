# Card Implementation Tracker

**109 Done / 12 Partial / 13 Missing**

Internal names often differ from the in-game display names (e.g. `zapmachine` = Sparky,
`assassin` = Bandit). See the "Internal ID" column for our internal identifier and "CR Name" for
the live game name.

---

## Troops

| #  | CR Name           | Internal ID      | Status    | Notes                       |
|----|-------------------|------------------|-----------|-----------------------------|
| 1  | Knight            | knight           | `DONE`    |                             |
| 2  | Archers           | archer           |           | count=2                     |
| 3  | Goblins           | goblins          |           | count=4                     |
| 4  | Giant             | giant            | `DONE`    |                             |
| 5  | P.E.K.K.A         | pekka            | `DONE`    |                             |
| 6  | Minions           | minions          |           | count=3                     |
| 7  | Balloon           | balloon          |           |                             |
| 8  | Witch             | witch            | `DONE`    |                             |
| 9  | Barbarians        | barbarians       |           | count=5                     |
| 10 | Golem             | golem            |           |                             |
| 11 | Skeletons         | skeletons        |           | count=3                     |
| 12 | Valkyrie          | valkyrie         |           |                             |
| 13 | Skeleton Army     | skeletonarmy     |           | count=15                    |
| 14 | Bomber            | bomber           |           |                             |
| 15 | Musketeer         | musketeer        | `DONE`    |                             |
| 16 | Baby Dragon       | babydragon       |           |                             |
| 17 | Prince            | prince           | `DONE`    |                             |
| 18 | Wizard            | wizard           |           |                             |
| 19 | Mini P.E.K.K.A    | minipekka        | `DONE`    |                             |
| 20 | Spear Goblins     | speargoblins     |           | count=3                     |
| 21 | Giant Skeleton    | giantskeleton    |           |                             |
| 22 | Hog Rider         | hogrider         |           | jump over river             |
| 23 | Minion Horde      | minionhorde      |           | count=6                     |
| 24 | Ice Wizard        | icewizard        | `DONE`    | deploy effect               |
| 25 | Royal Giant       | royalgiant       | `DONE`    |                             |
| 26 | Guards            | skeletonwarriors |           | count=3                     |
| 27 | Princess          | princess         |           |                             |
| 28 | Dark Prince       | darkprince       | `DONE`    |                             |
| 29 | Lava Hound        | lavahound        | `PARTIAL` | needs pup death spawn logic |
| 30 | Ice Spirit        | icespirits       |           |                             |
| 31 | Fire Spirit       | firespirits      |           |                             |
| 32 | Miner             | miner            |           |                             |
| 33 | Sparky            | zapmachine       | `DONE`    | noPreload                   |
| 34 | Bowler            | bowler           |           |                             |
| 35 | Lumberjack        | ragebarbarian    |           |                             |
| 36 | Battle Ram        | battleram        |           |                             |
| 37 | Inferno Dragon    | infernodragon    | `DONE`    | variable damage             |
| 38 | Ice Golem         | icegolemite      |           |                             |
| 39 | Mega Minion       | megaminion       | `DONE`    |                             |
| 40 | Dart Goblin       | blowdartgoblin   | `DONE`    |                             |
| 41 | Goblin Gang       | goblingang       |           | count=3                     |
| 42 | Electro Wizard    | electrowizard    | `DONE`    | deploy effect               |
| 43 | Elite Barbarians  | angrybarbarians  | `DONE`    | count=2                     |
| 44 | Hunter            | hunter           |           | shotgun burst               |
| 45 | Executioner       | axeman           |           |                             |
| 46 | Bandit            | assassin         |           | dash ability                |
| 47 | Royal Recruits    | royalrecruits    |           | count=6                     |
| 48 | Night Witch       | darkwitch        | `DONE`    |                             |
| 49 | Bats              | bats             |           | count=5                     |
| 50 | Royal Ghost       | ghost            |           |                             |
| 51 | Ram Rider         | ramrider         |           |                             |
| 52 | Zappies           | minisparkys      | `DONE`    | count=3                     |
| 53 | Rascals           | rascals          |           |                             |
| 54 | Mega Knight       | megaknight       |           | needs spawn jump AOE        |
| 55 | Skeleton Barrel   | skeletonballoon  |           |                             |
| 56 | Cannon Cart       | dartbarrell      |           |                             |
| 57 | Wall Breakers     | wallbreakers     |           | count=2                     |
| 58 | Royal Hogs        | royalhogs        |           | count=4, jump over river    |
| 59 | Goblin Giant      | goblingiant      |           |                             |
| 60 | Fisherman         | fisherman        | `DONE`    | hook ability                |
| 61 | Magic Archer      | elitearcher      |           |                             |
| 62 | Electro Dragon    | electrodragon    | `DONE`    | chain lightning             |
| 63 | Firecracker       | firecracker      |           |                             |
| 64 | Elixir Golem      | elixirgolem      |           |                             |
| 65 | Battle Healer     | battlehealer     |           |                             |
| 66 | Skeleton Dragons  | skeletondragons  |           | count=2                     |
| 67 | Mother Witch      | witchmother      |           |                             |
| 68 | Electro Spirit    | electrospirit    |           |                             |
| 69 | Electro Giant     | electrogiant     | `DONE`    | reflect damage              |
| 70 | Phoenix           | phoenix          |           |                             |
| 71 | Skeleton King     | skeletonking     | `PARTIAL` | needs ability cycling       |
| 72 | Archer Queen      | archerqueen      | `PARTIAL` | needs ability cycling       |
| 73 | Golden Knight     | goldenknight     | `PARTIAL` | needs ability cycling       |
| 74 | Mighty Miner      | mightyminer      | `PARTIAL` | needs ability cycling       |
| 75 | Monk              | monk             | `PARTIAL` | needs ability cycling       |
| 76 | Little Prince     | littleprince     | `PARTIAL` | needs ability cycling       |
| 77 | Suspicious Bush   | --               | `MISSING` | newer card                  |
| 78 | Berserker         | --               | `MISSING` | newer card                  |
| 79 | Goblin Demolisher | --               | `MISSING` | newer card                  |
| 80 | Goblin Brawler    | --               | `MISSING` | newer card                  |
| 81 | Rune Giant        | --               | `MISSING` | newer card                  |
| 82 | Goblin Machine    | --               | `MISSING` | newer card                  |
| 83 | Goblinstein       | --               | `MISSING` | newer card                  |
| 84 | Boss Bandit       | --               | `MISSING` | newer card                  |

## Spells

| #  | CR Name          | Internal ID   | Status    | Notes                     |
|----|------------------|---------------|-----------|---------------------------|
| 1  | Fireball         | fireball      | `DONE`    | projectile spell          |
| 2  | Arrows           | arrows        |           | projectile spell          |
| 3  | Rage             | rage          |           | summons RageBottle        |
| 4  | Rocket           | rocket        | `DONE`    | projectile spell          |
| 5  | Goblin Barrel    | goblinbarrel  |           | projectile spell          |
| 6  | Freeze           | freeze        | `DONE`    | area effect               |
| 7  | Mirror           | mirror        | `PARTIAL` | needs meta replay logic   |
| 8  | Lightning        | lightning     |           | area effect               |
| 9  | Zap              | zap           | `DONE`    | area effect               |
| 10 | Poison           | poison        | `DONE`    | area effect, ticking      |
| 11 | Graveyard        | graveyard     | `PARTIAL` | needs random spawn logic  |
| 12 | The Log          | log           |           | projectile spell          |
| 13 | Tornado          | tornado       | `PARTIAL` | needs pull mechanic       |
| 14 | Clone            | clone         | `PARTIAL` | needs clone mechanic      |
| 15 | Earthquake       | earthquake    |           | area effect, ticking      |
| 16 | Barbarian Barrel | barblog       |           | projectile spell          |
| 17 | Heal Spirit      | heal          |           | summons HealSpirit        |
| 18 | Snowball         | snowball      |           | projectile spell          |
| 19 | Royal Delivery   | royaldelivery |           | area effect + troop spawn |
| 20 | Void             | darkmagic     | `PARTIAL` | stub, needs mechanics     |
| 21 | Goblin Curse     | goblincurse   | `PARTIAL` | stub, needs mechanics     |
| 22 | Spirit Empress   | mergemaiden   | `PARTIAL` | stub, needs mechanics     |
| 23 | Vines            | vines         | `PARTIAL` | stub, needs mechanics     |

## Buildings

| #  | CR Name            | Internal ID       | Status | Notes           |
|----|--------------------|-------------------|--------|-----------------|
| 1  | Cannon             | cannon            | `DONE` |                 |
| 2  | Goblin Hut         | goblinhut         |        | spawner         |
| 3  | Mortar             | mortar            |        |                 |
| 4  | Inferno Tower      | infernotower      |        | variable damage |
| 5  | Bomb Tower         | bombtower         |        |                 |
| 6  | Barbarian Hut      | barbarianhut      |        | spawner         |
| 7  | Tesla              | tesla             |        |                 |
| 8  | Elixir Collector   | elixircollector   |        |                 |
| 9  | X-Bow              | xbow              |        |                 |
| 10 | Tombstone          | tombstone         | `DONE` | spawner         |
| 11 | Furnace            | firespirithut     |        | spawner         |
| 12 | Goblin Cage        | goblincage        |        | death spawn     |
| 13 | Goblin Drill       | goblindrill       |        |                 |
| 14 | Goblin Party Hut   | goblinpartyhut    |        | newer building  |
| 15 | Barbarian Launcher | barbarianlauncher |        | newer building  |

## Sub-Entity Buildings (not playable cards)

These are internal-only building entities with cost 0, used as death spawns or
auxiliary mechanics. They are not selectable cards in a player's deck.

| # | Name                  | Internal ID         | Purpose                         |
|---|-----------------------|---------------------|---------------------------------|
| 1 | Giant Skeleton Bomb   | giantskeletonbomb   | Death damage for Giant Skeleton |
| 2 | Balloon Bomb          | balloonbomb         | Death damage for Balloon        |
| 3 | Rage Barbarian Bottle | ragebarbarianbottle | Rage drop on Lumberjack death   |
| 4 | Skeleton Container    | skeletoncontainer   | Skeleton spawn container        |
| 5 | Bomb Tower Bomb       | bombtowerbomb       | Death damage for Bomb Tower     |

## Tower Troops (new mechanic -- all Missing)

Tower Troops are a newer mechanic where players equip special troops onto their
Crown Towers. This system is not yet implemented in crforge.

| # | CR Name        | Status    | Notes                      |
|---|----------------|-----------|----------------------------|
| 1 | Cannoneer      | `MISSING` | Ranged tower troop         |
| 2 | Dagger Duchess | `MISSING` | Ranged tower troop         |
| 3 | Royal Chef     | `MISSING` | Support tower troop        |
| 4 | Tower Princess | `MISSING` | Default ranged tower troop |
| 5 | Apprentice     | `MISSING` | Area damage tower troop    |

---

## Summary by Status

| Status     | Count | Description                                    |
|------------|-------|------------------------------------------------|
| `DONE`     |       | Fully functional in simulation                 |
| `PARTIAL`  |       | In cards.json but missing complex mechanics    |
| `MISSING`  |       | Not in cards.json (newer cards + tower troops) |
| Sub-entity |       | Internal buildings, not playable cards         |
| **Total**  |       |                                                |

### Partial cards breakdown

| Category        | Cards                                                                         | What's needed                                         |
|-----------------|-------------------------------------------------------------------------------|-------------------------------------------------------|
| Champions       | Skeleton King, Archer Queen, Golden Knight, Mighty Miner, Monk, Little Prince | Ability cycling system (tap to activate)              |
| Meta spells     | Mirror                                                                        | Replay last card at +1 level                          |
| Complex spells  | Clone, Tornado, Graveyard                                                     | Clone duplication, pull physics, random spawn pattern |
| Newer stubs     | Void, Goblin Curse, Spirit Empress, Vines                                     | Full mechanics TBD                                    |
| Composite units | Lava Hound (pups), Mega Knight (spawn jump)                                   | Death sub-unit spawning, deploy AOE                   |