# Card Implementation Tracker

Internal names often differ from the in-game display names (e.g. `zapmachine` = Sparky,
`assassin` = Bandit). See the "Internal ID" column for our internal identifier and "CR Name" for
the live game name.

---

## Troops

| #  | CR Name           | Internal ID      | Status    | Notes                                         |
|----|-------------------|------------------|-----------|-----------------------------------------------|
| 1  | Knight            | knight           | `DONE`    |                                               |
| 2  | Archers           | archer           | `DONE`    | count=2                                       |
| 3  | Goblins           | goblins          | `DONE`    | count=4                                       |
| 4  | Giant             | giant            | `DONE`    |                                               |
| 5  | P.E.K.K.A         | pekka            | `DONE`    |                                               |
| 6  | Minions           | minions          | `DONE`    | count=3                                       |
| 7  | Balloon           | balloon          | `DONE`    |                                               |
| 8  | Witch             | witch            | `DONE`    |                                               |
| 9  | Barbarians        | barbarians       | `DONE`    | count=5                                       |
| 10 | Golem             | golem            | `DONE`    |                                               |
| 11 | Skeletons         | skeletons        | `DONE`    | count=3                                       |
| 12 | Valkyrie          | valkyrie         | `DONE`    |                                               |
| 13 | Skeleton Army     | skeletonarmy     | `DONE`    | count=15                                      |
| 14 | Bomber            | bomber           | `DONE`    |                                               |
| 15 | Musketeer         | musketeer        | `DONE`    |                                               |
| 16 | Baby Dragon       | babydragon       | `DONE`    |                                               |
| 17 | Prince            | prince           | `DONE`    |                                               |
| 18 | Wizard            | wizard           | `DONE`    |                                               |
| 19 | Mini P.E.K.K.A    | minipekka        | `DONE`    |                                               |
| 20 | Spear Goblins     | speargoblins     | `DONE`    | count=3                                       |
| 21 | Giant Skeleton    | giantskeleton    | `DONE`    |                                               |
| 22 | Hog Rider         | hogrider         | `DONE`    | jump over river                               |
| 23 | Minion Horde      | minionhorde      | `DONE`    | count=6                                       |
| 24 | Ice Wizard        | icewizard        | `DONE`    | deploy effect                                 |
| 25 | Royal Giant       | royalgiant       | `DONE`    |                                               |
| 26 | Guards            | skeletonwarriors | `DONE`    | count=3                                       |
| 27 | Princess          | princess         | `DONE`    | non-homing AOE projectile                     |
| 28 | Dark Prince       | darkprince       | `DONE`    |                                               |
| 29 | Lava Hound        | lavahound        | `DONE`    |                                               |
| 30 | Ice Spirit        | icespirits       | `DONE`    |                                               |
| 31 | Fire Spirit       | firespirits      | `DONE`    |                                               |
| 32 | Miner             | miner            | `DONE`    | tunnel + deploy on enemy side                 |
| 33 | Sparky            | zapmachine       | `DONE`    | noPreload                                     |
| 34 | Bowler            | bowler           | `DONE`    | piercing projectile + knockback               |
| 35 | Lumberjack        | ragebarbarian    | `DONE`    | death drops Rage spell                        |
| 36 | Battle Ram        | battleram        | `DONE`    | charge + kamikaze + death spawn (1.0s deploy) |
| 37 | Inferno Dragon    | infernodragon    | `DONE`    | variable damage                               |
| 38 | Ice Golem         | icegolemite      | `DONE`    | death slow via deathAreaEffect                |
| 39 | Mega Minion       | megaminion       | `DONE`    |                                               |
| 40 | Dart Goblin       | blowdartgoblin   | `DONE`    |                                               |
| 41 | Goblin Gang       | goblingang       | `DONE`    | count=3                                       |
| 42 | Electro Wizard    | electrowizard    | `DONE`    | deploy effect                                 |
| 43 | Elite Barbarians  | angrybarbarians  | `DONE`    | count=2                                       |
| 44 | Hunter            | hunter           | `DONE`    | shotgun burst (10 scatter proj)               |
| 45 | Executioner       | axeman           | `DONE`    | returning projectile                          |
| 46 | Bandit            | assassin         | `DONE`    | dash ability                                  |
| 47 | Royal Recruits    | royalrecruits    | `DONE`    | count=6                                       |
| 48 | Night Witch       | darkwitch        | `DONE`    |                                               |
| 49 | Bats              | bats             | `DONE`    | count=5                                       |
| 50 | Royal Ghost       | ghost            | `DONE`    | stealth ability                               |
| 51 | Ram Rider         | ramrider         | `DONE`    | spawnAttach (rider on mount)                  |
| 52 | Zappies           | minisparkys      | `DONE`    | count=3                                       |
| 53 | Rascals           | rascals          | `DONE`    |                                               |
| 54 | Mega Knight       | megaknight       | `DONE`    | needs spawn jump AOE                          |
| 55 | Skeleton Barrel   | skeletonballoon  | `DONE`    | kamikaze + death chain                        |
| 56 | Flying Machine    | dartbarrell      | `DONE`    |                                               |
| 57 | Wall Breakers     | wallbreakers     | `DONE`    | count=2, kamikaze + melee AOE                 |
| 58 | Royal Hogs        | royalhogs        | `DONE`    | count=4, jump over river                      |
| 59 | Goblin Giant      | goblingiant      | `DONE`    | spawnAttach (2 spear goblins)                 |
| 60 | Fisherman         | fisherman        | `DONE`    | hook ability                                  |
| 61 | Magic Archer      | elitearcher      | `DONE`    | piercing arrow (0.25 radius, 11-tile range)   |
| 62 | Electro Dragon    | electrodragon    | `DONE`    | chain lightning                               |
| 63 | Firecracker       | firecracker      | `DONE`    | attack recoil + shrapnel fan scatter          |
| 64 | Elixir Golem      | elixirgolem      | `DONE`    | 3-form split chain, manaOnDeathForOpponent    |
| 65 | Battle Healer     | battlehealer     | `DONE`    | heal on hit, heal on deploy, hovering         |
| 66 | Skeleton Dragons  | skeletondragons  | `DONE`    | count=2                                       |
| 67 | Mother Witch      | witchmother      | `DONE`    |                                               |
| 68 | Electro Spirit    | electrospirit    | `DONE`    |                                               |
| 69 | Electro Giant     | electrogiant     | `DONE`    | reflect damage                                |
| 70 | Phoenix           | phoenix          | `DONE`    | death -> egg -> respawn chain                 |
| 71 | Skeleton King     | skeletonking     | `PARTIAL` | needs ability cycling                         |
| 72 | Archer Queen      | archerqueen      | `PARTIAL` | needs ability cycling                         |
| 73 | Golden Knight     | goldenknight     | `PARTIAL` | needs ability cycling                         |
| 74 | Mighty Miner      | mightyminer      | `PARTIAL` | needs ability cycling                         |
| 75 | Monk              | monk             | `PARTIAL` | needs ability cycling                         |
| 76 | Little Prince     | littleprince     | `PARTIAL` | needs ability cycling                         |
| 77 | Suspicious Bush   | --               | `MISSING` | newer card                                    |
| 78 | Berserker         | --               | `MISSING` | newer card                                    |
| 79 | Goblin Demolisher | --               | `MISSING` | newer card                                    |
| 80 | Rune Giant        | --               | `MISSING` | newer card                                    |
| 81 | Goblin Machine    | --               | `MISSING` | newer card                                    |
| 82 | Goblinstein       | --               | `MISSING` | newer card                                    |
| 83 | Boss Bandit       | --               | `MISSING` | newer card                                    |

## Spells

| #  | CR Name          | Internal ID   | Status    | Notes                          |
|----|------------------|---------------|-----------|--------------------------------|
| 1  | Fireball         | fireball      | `DONE`    | projectile spell               |
| 2  | Arrows           | arrows        | `DONE`    | 3-volley projectile spell      |
| 3  | Rage             | rage          | `DONE`    | summons RageBarbarianBottle    |
| 4  | Rocket           | rocket        | `DONE`    | projectile spell               |
| 5  | Goblin Barrel    | goblinbarrel  | `DONE`    | projectile spawn-on-impact     |
| 6  | Freeze           | freeze        | `DONE`    | area effect                    |
| 7  | Mirror           | mirror        | `PARTIAL` | needs meta replay logic        |
| 8  | Lightning        | lightning     | `DONE`    | area effect, hitBiggestTargets |
| 9  | Zap              | zap           | `DONE`    | area effect                    |
| 10 | Poison           | poison        | `DONE`    | area effect, ticking           |
| 11 | Graveyard        | graveyard     | `PARTIAL` | needs random spawn logic       |
| 12 | The Log          | log           | `MISSING` | projectile spell               |
| 13 | Tornado          | tornado       | `PARTIAL` | needs pull mechanic            |
| 14 | Clone            | clone         | `PARTIAL` | needs clone mechanic           |
| 15 | Earthquake       | earthquake    | `MISSING` | area effect, ticking           |
| 16 | Barbarian Barrel | barblog       | `MISSING` | projectile spell               |
| 17 | Heal Spirit      | heal          | `DONE`    | summons HealSpirit             |
| 18 | Snowball         | snowball      | `MISSING` | projectile spell               |
| 19 | Royal Delivery   | royaldelivery | `MISSING` | area effect + troop spawn      |
| 20 | Void             | darkmagic     | `PARTIAL` | stub, needs mechanics          |
| 21 | Goblin Curse     | goblincurse   | `PARTIAL` | stub, needs mechanics          |
| 22 | Spirit Empress   | mergemaiden   | `PARTIAL` | stub, needs mechanics          |
| 23 | Vines            | vines         | `PARTIAL` | stub, needs mechanics          |

## Buildings

| #  | CR Name            | Internal ID       | Status    | Notes                         |
|----|--------------------|-------------------|-----------|-------------------------------|
| 1  | Cannon             | cannon            | `DONE`    |                               |
| 2  | Goblin Hut         | goblinhut         | `MISSING` | spawner                       |
| 3  | Mortar             | mortar            | `DONE`    | minimum range blind spot      |
| 4  | Inferno Tower      | infernotower      | `DONE`    | variable damage               |
| 5  | Bomb Tower         | bombtower         | `DONE`    | death bomb AOE                |
| 6  | Barbarian Hut      | barbarianhut      | `MISSING` | spawner                       |
| 7  | Tesla              | tesla             | `MISSING` |                               |
| 8  | Elixir Collector   | elixircollector   | `MISSING` |                               |
| 9  | X-Bow              | xbow              | `DONE`    | siege building                |
| 10 | Tombstone          | tombstone         | `DONE`    | spawner                       |
| 11 | Furnace            | firespirithut     | `MISSING` | spawner                       |
| 12 | Goblin Cage        | goblincage        | `MISSING` | death spawn (0.5s deploy)     |
| 13 | Goblin Drill       | goblindrill       | `MISSING` | tunnel + death spawn (0.5s)   |
| 14 | Dark Elixir Bottle | darkelixir_bottle | `MISSING` | bomb, drops DarkElixir AOE    |
| 15 | Elixir Barrel      | elixirbarrel      | `MISSING` | 4 elixir to opponent on death |
| 16 | Goblin Party Hut   | goblinpartyhut    | `MISSING` | newer building                |
| 17 | Barbarian Launcher | barbarianlauncher | `MISSING` | newer building                |

## Sub-Entities (not playable cards)

Internal-only entities used as death spawns or auxiliary mechanics.
Cost-0 buildings are in cards.json; units-only entries exist solely in units.json.

| # | Name                  | Internal ID         | Purpose                                    |
|---|-----------------------|---------------------|--------------------------------------------|
| 1 | Giant Skeleton Bomb   | giantskeletonbomb   | Death damage for Giant Skeleton            |
| 2 | Balloon Bomb          | balloonbomb         | Death damage for Balloon                   |
| 3 | Rage Barbarian Bottle | ragebarbarianbottle | Rage drop on Lumberjack death              |
| 4 | Skeleton Container    | skeletoncontainer   | Skeleton spawn for Skeleton Barrel (0.5s)  |
| 5 | Bomb Tower Bomb       | bombtowerbomb       | Death damage for Bomb Tower                |
| 6 | Goblin Brawler        | goblinbrawler       | Death spawn for Goblin Cage (0.5s deploy)  |
| 7 | SpearGoblin (Giant)   | speargoblingiant    | Death spawn for Goblin Giant (0.7s deploy) |

## Tower Troops (new mechanic -- all Missing)

Tower Troops are a newer mechanic where players equip special troops onto their
Crown Towers. This system is not yet implemented in crforge.

| # | CR Name        | Status    | Notes                      |
|---|----------------|-----------|----------------------------|
| 1 | Cannoneer      | `MISSING` | Ranged tower troop         |
| 2 | Dagger Duchess | `MISSING` | Ranged tower troop         |
| 3 | Royal Chef     | `MISSING` | Support tower troop        |
| 4 | Tower Princess | `DONE`    | Default ranged tower troop |
| 5 | Apprentice     | `MISSING` | Area damage tower troop    |

---

## Summary by Status

| Status     | Count   | Description                                 |
|------------|---------|---------------------------------------------|
| `DONE`     | 87      | Fully functional in simulation              |
| `PARTIAL`  | 14      | In cards.json but missing complex mechanics |
| `MISSING`  | 27      | Not yet implemented or newer cards          |
| Sub-entity | 7       | Internal entities, not playable cards       |
| **Total**  | **128** | Playable cards (excluding sub-entities)     |

Data source: season 80 (202602) -- 121 entries in cards.json.

### Partial cards breakdown

| Category       | Cards                                                                         | What's needed                                         |
|----------------|-------------------------------------------------------------------------------|-------------------------------------------------------|
| Champions      | Skeleton King, Archer Queen, Golden Knight, Mighty Miner, Monk, Little Prince | Ability cycling system (tap to activate)              |
| Meta spells    | Mirror                                                                        | Replay last card at +1 level                          |
| Complex spells | Clone, Tornado, Graveyard                                                     | Clone duplication, pull physics, random spawn pattern |
| Newer stubs    | Void, Goblin Curse, Spirit Empress, Vines                                     | Full mechanics TBD                                    |