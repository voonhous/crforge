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
| 71 | Suspicious Bush   | suspiciousbush   | `DONE`    | permanent stealth, kamikaze, death spawn      |
| 72 | Berserker         | berserker        | `DONE`    | 3-hit attack sequence combo                   |
| 73 | Goblin Demolisher | goblindemolisher | `DONE`    | HP-threshold transformation + kamikaze form   |
| 74 | Rune Giant        | giantbuffer      | `DONE`    | BUFF_ALLY ability: buffs 2 closest friendlies |
| 75 | Goblin Machine    | goblinmachine    | `DONE`    | dual-attack: melee + RANGED_ATTACK rocket     |
| 76 | Spirit Empress    | mergemaiden      | `DONE`    | variant selection (dual-form AIR/GROUND)      |
| 77 | Skeleton King     | skeletonking     | `PARTIAL` | needs ability cycling                         |
| 78 | Archer Queen      | archerqueen      | `PARTIAL` | needs ability cycling                         |
| 79 | Golden Knight     | goldenknight     | `PARTIAL` | needs ability cycling                         |
| 80 | Mighty Miner      | mightyminer      | `PARTIAL` | needs ability cycling                         |
| 81 | Monk              | monk             | `PARTIAL` | needs ability cycling                         |
| 82 | Little Prince     | littleprince     | `PARTIAL` | needs ability cycling                         |
| 83 | Goblinstein       | goblinstein      | `MISSING` | needs ability cycling                         |
| 84 | Boss Bandit       | BossBandit       | `MISSING` | needs ability cycling                         |

## Spells

| #  | CR Name          | Internal ID   | Status | Notes                                            |
|----|------------------|---------------|--------|--------------------------------------------------|
| 1  | Fireball         | fireball      | `DONE` | projectile spell                                 |
| 2  | Arrows           | arrows        | `DONE` | 3-volley projectile spell                        |
| 3  | Rage             | rage          | `DONE` | summons RageBarbarianBottle                      |
| 4  | Rocket           | rocket        | `DONE` | projectile spell                                 |
| 5  | Goblin Barrel    | goblinbarrel  | `DONE` | projectile spawn-on-impact                       |
| 6  | Freeze           | freeze        | `DONE` | area effect                                      |
| 7  | Mirror           | mirror        | `DONE` | replays last card at +1 level/cost               |
| 8  | Lightning        | lightning     | `DONE` | area effect, hitBiggestTargets                   |
| 9  | Zap              | zap           | `DONE` | area effect                                      |
| 10 | Poison           | poison        | `DONE` | area effect, ticking                             |
| 11 | Graveyard        | graveyard     | `DONE` | area effect with 13-skeleton spawn sequence      |
| 12 | The Log          | log           | `DONE` | spellAsDeploy, 2-stage rolling                   |
| 13 | Tornado          | tornado       | `DONE` | pull + damage + controlsBuff                     |
| 14 | Clone            | clone         | `DONE` | area effect, clone mechanic                      |
| 15 | Earthquake       | earthquake    | `DONE` | area effect, ticking, building 4.5x, slow 50%    |
| 16 | Barbarian Barrel | barblog       | `DONE` | spellAsDeploy, 2-stage rolling + Barbarian spawn |
| 17 | Heal Spirit      | heal          | `DONE` | summons HealSpirit                               |
| 18 | Snowball         | snowball      | `DONE` | projectile spell                                 |
| 19 | Royal Delivery   | royaldelivery | `DONE` | area effect + delayed troop spawn                |
| 20 | Void             | darkmagic     | `DONE` | laser ball: tiered DPS, 100ms ticks, scan-based  |
| 21 | Goblin Curse     | goblincurse   | `DONE` | death-spawn curse + DoT, flattened ticking AEO   |
| 22 | Vines            | vines         | `DONE` | targeted freeze + DOT + air-to-ground            |

## Buildings

| #  | CR Name            | Internal ID       | Status    | Notes                                       |
|----|--------------------|-------------------|-----------|---------------------------------------------|
| 1  | Cannon             | cannon            | `DONE`    |                                             |
| 2  | Goblin Hut         | goblinhut_rework  | `DONE`    | aggro-gated spawner                         |
| 3  | Mortar             | mortar            | `DONE`    | minimum range blind spot                    |
| 4  | Inferno Tower      | infernotower      | `DONE`    | variable damage                             |
| 5  | Bomb Tower         | bombtower         | `DONE`    | death bomb AOE                              |
| 6  | Barbarian Hut      | barbarianhut      | `DONE`    | spawner                                     |
| 7  | Tesla              | tesla             | `DONE`    | hiding states                               |
| 8  | Elixir Collector   | elixircollector   | `DONE`    | elixir generation + death refund + cap hold |
| 9  | X-Bow              | xbow              | `DONE`    | siege building                              |
| 10 | Tombstone          | tombstone         | `DONE`    | spawner                                     |
| 11 | Furnace            | firespirithut     | `DONE`    | walking spawner (rework)                    |
| 12 | Goblin Cage        | goblincage        | `DONE`    | death spawn (0.5s deploy)                   |
| 13 | Goblin Drill       | goblindrill       | `DONE`    | tunnel + morph + spawn AOE + death spawn    |
| 14 | Dark Elixir Bottle | darkelixir_bottle | `MISSING` | special gamemode only, deprioritised        |
| 15 | Elixir Barrel      | elixirbarrel      | `MISSING` | special gamemode only, deprioritised        |
| 16 | Goblin Party Hut   | goblinpartyhut    | `MISSING` | special gamemode only, deprioritised        |
| 17 | Barbarian Launcher | barbarianlauncher | `MISSING` | special gamemode only, deprioritised        |

## Sub-Entities (not playable cards)

Internal-only entities used as death spawns or auxiliary mechanics.
Cost-0 buildings are in cards.json; units-only entries exist solely in units.json.

| # | Name                  | Internal ID         | Purpose                                                           |
|---|-----------------------|---------------------|-------------------------------------------------------------------|
| 1 | Giant Skeleton Bomb   | giantskeletonbomb   | Death damage for Giant Skeleton                                   |
| 2 | Balloon Bomb          | balloonbomb         | Death damage for Balloon                                          |
| 3 | Rage Barbarian Bottle | ragebarbarianbottle | Rage drop on Lumberjack death                                     |
| 4 | Skeleton Container    | skeletoncontainer   | Skeleton spawn for Skeleton Barrel (0.5s)                         |
| 5 | Bomb Tower Bomb       | bombtowerbomb       | Death damage for Bomb Tower                                       |
| 6 | Goblin Brawler        | goblinbrawler       | Death spawn for Goblin Cage (0.5s deploy)                         |
| 7 | SpearGoblin (Giant)   | speargoblingiant    | Death spawn for Goblin Giant (0.7s deploy)                        |
| 8 | BushGoblin            | bushgoblin          | Death spawn for Suspicious Bush (0.4s deploy, staggered flanking) |
| 9 | SpearGoblin_Dummy     | speargoblin_dummy   | Aggro-gated live spawn for Goblin Hut (0.5s deploy)               |

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
| `DONE`     | 113     | Fully functional in simulation              |
| `PARTIAL`  | 7       | In cards.json but missing complex mechanics |
| `MISSING`  | 11      | Not yet implemented or newer cards          |
| Sub-entity | 9       | Internal entities, not playable cards       |
| **Total**  | **128** | Playable cards (excluding sub-entities)     |

Data source: season 80 (202602)

### Partial cards breakdown

| Category  | Cards                                                                         | What's needed                            |
|-----------|-------------------------------------------------------------------------------|------------------------------------------|
| Champions | Skeleton King, Archer Queen, Golden Knight, Mighty Miner, Monk, Little Prince | Ability cycling system (tap to activate) |

### Not yet planned

Champion abilities, evolutions, and hero mechanics are not implemented. These meta-systems will be
explored after the core card mechanics are fully nailed down.