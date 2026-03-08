# Card Implementation Tracker

**109 Done / 12 Partial / 13 Missing**

Internal names often differ from the in-game display names (e.g. `zapmachine` = Sparky,
`assassin` = Bandit). See the "Internal ID" column for our internal identifier and "CR Name" for
the live game name.

---

## Troops

| #  | CR Name           | Internal ID      | Status  | Notes                       |
|----|-------------------|------------------|---------|-----------------------------|
| 1  | Knight            | knight           | [x]     |                             |
| 2  | Archers           | archer           | []      | count=2                     |
| 3  | Goblins           | goblins          | [ ]     | count=4                     |
| 4  | Giant             | giant            | [x]     |                             |
| 5  | P.E.K.K.A         | pekka            | [x]     |                             |
| 6  | Minions           | minions          | [ ]     | count=3                     |
| 7  | Balloon           | balloon          | [ ]     |                             |
| 8  | Witch             | witch            | [x]     |                             |
| 9  | Barbarians        | barbarians       | [ ]     | count=5                     |
| 10 | Golem             | golem            | [ ]     |                             |
| 11 | Skeletons         | skeletons        | [ ]     | count=3                     |
| 12 | Valkyrie          | valkyrie         | [ ]     |                             |
| 13 | Skeleton Army     | skeletonarmy     | [ ]     | count=15                    |
| 14 | Bomber            | bomber           | [ ]     |                             |
| 15 | Musketeer         | musketeer        | [x]     |                             |
| 16 | Baby Dragon       | babydragon       | [x]     |                             |
| 17 | Prince            | prince           | [x]     |                             |
| 18 | Wizard            | wizard           | [ ]     |                             |
| 19 | Mini P.E.K.K.A    | minipekka        | [x]     |                             |
| 20 | Spear Goblins     | speargoblins     | [ ]     | count=3                     |
| 21 | Giant Skeleton    | giantskeleton    | [ ]     |                             |
| 22 | Hog Rider         | hogrider         | [ ]     | jump over river             |
| 23 | Minion Horde      | minionhorde      | [ ]     | count=6                     |
| 24 | Ice Wizard        | icewizard        | [ ]     | deploy effect               |
| 25 | Royal Giant       | royalgiant       | [x]     |                             |
| 26 | Guards            | skeletonwarriors | [x]     | count=3                     |
| 27 | Princess          | princess         | [ ]     |                             |
| 28 | Dark Prince       | darkprince       | [ ]     |                             |
| 29 | Lava Hound        | lavahound        | Partial | needs pup death spawn logic |
| 30 | Ice Spirit        | icespirits       | [ ]     |                             |
| 31 | Fire Spirit       | firespirits      | [ ]     |                             |
| 32 | Miner             | miner            | [ ]     |                             |
| 33 | Sparky            | zapmachine       | [ ]     | noPreload                   |
| 34 | Bowler            | bowler           | [ ]     |                             |
| 35 | Lumberjack        | ragebarbarian    | [ ]     |                             |
| 36 | Battle Ram        | battleram        | [ ]     |                             |
| 37 | Inferno Dragon    | infernodragon    | [ ]     | variable damage             |
| 38 | Ice Golem         | icegolemite      | [ ]     |                             |
| 39 | Mega Minion       | megaminion       | [ ]     |                             |
| 40 | Dart Goblin       | blowdartgoblin   | [ ]     |                             |
| 41 | Goblin Gang       | goblingang       | [ ]     | count=3                     |
| 42 | Electro Wizard    | electrowizard    | [ ]     | deploy effect               |
| 43 | Elite Barbarians  | angrybarbarians  | [ ]     | count=2                     |
| 44 | Hunter            | hunter           | [ ]     | shotgun burst               |
| 45 | Executioner       | axeman           | [ ]     |                             |
| 46 | Bandit            | assassin         | [ ]     | dash ability                |
| 47 | Royal Recruits    | royalrecruits    | [ ]     | count=6                     |
| 48 | Night Witch       | darkwitch        | [ ]     |                             |
| 49 | Bats              | bats             | [ ]     | count=5                     |
| 50 | Royal Ghost       | ghost            | [ ]     |                             |
| 51 | Ram Rider         | ramrider         | [ ]     |                             |
| 52 | Zappies           | minisparkys      | [ ]     | count=3                     |
| 53 | Rascals           | rascals          | [ ]     |                             |
| 54 | Mega Knight       | megaknight       | Partial | needs spawn jump AOE        |
| 55 | Skeleton Barrel   | skeletonballoon  | [ ]     |                             |
| 56 | Cannon Cart       | dartbarrell      | [ ]     |                             |
| 57 | Wall Breakers     | wallbreakers     | [ ]     | count=2                     |
| 58 | Royal Hogs        | royalhogs        | [ ]     | count=4                     |
| 59 | Goblin Giant      | goblingiant      | [ ]     |                             |
| 60 | Fisherman         | fisherman        | [x]     | hook ability                |
| 61 | Magic Archer      | elitearcher      | [ ]     |                             |
| 62 | Electro Dragon    | electrodragon    | [ ]     | chain lightning             |
| 63 | Firecracker       | firecracker      | [ ]     |                             |
| 64 | Elixir Golem      | elixirgolem      | [ ]     |                             |
| 65 | Battle Healer     | battlehealer     | [ ]     |                             |
| 66 | Skeleton Dragons  | skeletondragons  | [ ]     | count=2                     |
| 67 | Mother Witch      | witchmother      | [ ]     |                             |
| 68 | Electro Spirit    | electrospirit    | [ ]     |                             |
| 69 | Electro Giant     | electrogiant     | [x]     | reflect damage              |
| 70 | Phoenix           | phoenix          | [ ]     |                             |
| 71 | Skeleton King     | skeletonking     | Partial | needs ability cycling       |
| 72 | Archer Queen      | archerqueen      | Partial | needs ability cycling       |
| 73 | Golden Knight     | goldenknight     | Partial | needs ability cycling       |
| 74 | Mighty Miner      | mightyminer      | Partial | needs ability cycling       |
| 75 | Monk              | monk             | Partial | needs ability cycling       |
| 76 | Little Prince     | littleprince     | Partial | needs ability cycling       |
| 77 | Suspicious Bush   | --               | Missing | newer card                  |
| 78 | Berserker         | --               | Missing | newer card                  |
| 79 | Goblin Demolisher | --               | Missing | newer card                  |
| 80 | Goblin Brawler    | --               | Missing | newer card                  |
| 81 | Rune Giant        | --               | Missing | newer card                  |
| 82 | Goblin Machine    | --               | Missing | newer card                  |
| 83 | Goblinstein       | --               | Missing | newer card                  |
| 84 | Boss Bandit       | --               | Missing | newer card                  |

## Spells

| #  | CR Name          | Internal ID   | Status  | Notes                     |
|----|------------------|---------------|---------|---------------------------|
| 1  | Fireball         | fireball      | [ ]     | projectile spell          |
| 2  | Arrows           | arrows        | [ ]     | projectile spell          |
| 3  | Rage             | rage          | [ ]     | summons RageBottle        |
| 4  | Rocket           | rocket        | [ ]     | projectile spell          |
| 5  | Goblin Barrel    | goblinbarrel  | [ ]     | projectile spell          |
| 6  | Freeze           | freeze        | [ ]     | area effect               |
| 7  | Mirror           | mirror        | Partial | needs meta replay logic   |
| 8  | Lightning        | lightning     | [ ]     | area effect               |
| 9  | Zap              | zap           | [ ]     | area effect               |
| 10 | Poison           | poison        | [ ]     | area effect, ticking      |
| 11 | Graveyard        | graveyard     | Partial | needs random spawn logic  |
| 12 | The Log          | log           | [ ]     | projectile spell          |
| 13 | Tornado          | tornado       | Partial | needs pull mechanic       |
| 14 | Clone            | clone         | Partial | needs clone mechanic      |
| 15 | Earthquake       | earthquake    | [ ]     | area effect, ticking      |
| 16 | Barbarian Barrel | barblog       | [ ]     | projectile spell          |
| 17 | Heal Spirit      | heal          | [ ]     | summons HealSpirit        |
| 18 | Snowball         | snowball      | [ ]     | projectile spell          |
| 19 | Royal Delivery   | royaldelivery | [ ]     | area effect + troop spawn |
| 20 | Void             | darkmagic     | Partial | stub, needs mechanics     |
| 21 | Goblin Curse     | goblincurse   | Partial | stub, needs mechanics     |
| 22 | Spirit Empress   | mergemaiden   | Partial | stub, needs mechanics     |
| 23 | Vines            | vines         | Partial | stub, needs mechanics     |

## Buildings

| #  | CR Name            | Internal ID       | Status | Notes           |
|----|--------------------|-------------------|--------|-----------------|
| 1  | Cannon             | cannon            | [ ]    |                 |
| 2  | Goblin Hut         | goblinhut         | [ ]    | spawner         |
| 3  | Mortar             | mortar            | [ ]    |                 |
| 4  | Inferno Tower      | infernotower      | [ ]    | variable damage |
| 5  | Bomb Tower         | bombtower         | [ ]    |                 |
| 6  | Barbarian Hut      | barbarianhut      | [ ]    | spawner         |
| 7  | Tesla              | tesla             | [ ]    |                 |
| 8  | Elixir Collector   | elixircollector   | [ ]    |                 |
| 9  | X-Bow              | xbow              | [ ]    |                 |
| 10 | Tombstone          | tombstone         | [ ]    | spawner         |
| 11 | Furnace            | firespirithut     | [ ]    | spawner         |
| 12 | Goblin Cage        | goblincage        | [ ]    | death spawn     |
| 13 | Goblin Drill       | goblindrill       | [ ]    |                 |
| 14 | Goblin Party Hut   | goblinpartyhut    | [ ]    | newer building  |
| 15 | Barbarian Launcher | barbarianlauncher | [ ]    | newer building  |
| 16 | Dark Elixir Bottle | darkelixir_bottle | [ ]    | newer building  |
| 17 | Elixir Barrel      | elixirbarrel      | [ ]    | newer building  |

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

| # | CR Name        | Status  | Notes                      |
|---|----------------|---------|----------------------------|
| 1 | Cannoneer      | Missing | Ranged tower troop         |
| 2 | Dagger Duchess | Missing | Ranged tower troop         |
| 3 | Royal Chef     | Missing | Support tower troop        |
| 4 | Tower Princess | Missing | Default ranged tower troop |
| 5 | Apprentice     | Missing | Area damage tower troop    |

---

## Summary by Status

| Status     | Count  | Description                                    |
|------------|--------|------------------------------------------------|
| [x]        |        | Fully functional in simulation                 |
| Partial    | 12     | In cards.json but missing complex mechanics    |
| Missing    | 13     | Not in cards.json (newer cards + tower troops) |
| Sub-entity |        | Internal buildings, not playable cards         |
| **Total**  | **25** |                                                |

### Partial cards breakdown

| Category        | Cards                                                                         | What's needed                                         |
|-----------------|-------------------------------------------------------------------------------|-------------------------------------------------------|
| Champions       | Skeleton King, Archer Queen, Golden Knight, Mighty Miner, Monk, Little Prince | Ability cycling system (tap to activate)              |
| Meta spells     | Mirror                                                                        | Replay last card at +1 level                          |
| Complex spells  | Clone, Tornado, Graveyard                                                     | Clone duplication, pull physics, random spawn pattern |
| Newer stubs     | Void, Goblin Curse, Spirit Empress, Vines                                     | Full mechanics TBD                                    |
| Composite units | Lava Hound (pups), Mega Knight (spawn jump)                                   | Death sub-unit spawning, deploy AOE                   |
