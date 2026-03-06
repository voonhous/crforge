package org.crforge.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.crfoge.data.loader.CardLoader;
import org.crforge.core.ability.AbilityType;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.junit.jupiter.api.Test;

class CardLoaderTest {

  @Test
  void loadCards_shouldParseTroopJson() {
    String json = """
        [
          {
            "id": "knight",
            "name": "Knight",
            "description": "A tough melee fighter",
            "type": "TROOP",
            "cost": 3,
            "units": [
              {
                "name": "Knight",
                "health": 1452,
                "damage": 167,
                "speed": 60.0,
                "mass": 5.0,
                "size": 0.8,
                "range": 0.7,
                "attackCooldown": 1.1,
                "targetType": "GROUND",
                "movementType": "GROUND"
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);

    assertThat(card.getId()).isEqualTo("knight");
    assertThat(card.getName()).isEqualTo("Knight");
    assertThat(card.getType()).isEqualTo(CardType.TROOP);
    assertThat(card.getCost()).isEqualTo(3);
    assertThat(card.getTroops()).hasSize(1);

    TroopStats stats = card.getTroops().get(0);
    assertThat(stats.getName()).isEqualTo("Knight");
    assertThat(stats.getHealth()).isEqualTo(1452);
    assertThat(stats.getTargetType()).isEqualTo(TargetType.GROUND);
    // Speed 60 should convert to 1.0f
    assertThat(stats.getSpeed()).isCloseTo(1.0f, within(0.001f));
  }

  @Test
  void loadCards_shouldConvertBase60Speed() {
    String json = """
        [
          {
            "id": "giant",
            "name": "Giant",
            "type": "TROOP",
            "cost": 5,
            "units": [
              {
                "name": "Giant",
                "health": 4091,
                "speed": 45.0,
                "damage": 211,
                "targetType": "BUILDINGS",
                "movementType": "GROUND"
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    TroopStats stats = cards.get(0).getTroops().get(0);
    // Speed 45 should convert to 0.75f
    assertThat(stats.getSpeed()).isCloseTo(0.75f, within(0.001f));
  }

  @Test
  void loadCards_shouldThrowOnMissingMovementType() {
    String json = """
        [
          {
            "id": "musketeer",
            "name": "Musketeer",
            "type": "TROOP",
            "cost": 4,
            "units": [
              {
                "name": "Musketeer",
                "health": 720,
                "damage": 181,
                "targetType": "ALL"
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> CardLoader.loadCards(is))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("MovementType is required");
  }

  @Test
  void loadCards_shouldThrowOnMissingTargetTypeForAttacker() {
    String json = """
        [
          {
            "id": "musketeer",
            "name": "Musketeer",
            "type": "TROOP",
            "cost": 4,
            "units": [
              {
                "name": "Musketeer",
                "health": 720,
                "damage": 181,
                "movementType": "GROUND"
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> CardLoader.loadCards(is))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("TargetType is required");
  }

  @Test
  void loadCards_shouldParseSpellWithEffects() {
    String json = """
        [
          {
            "id": "zap",
            "name": "Zap",
            "type": "SPELL",
            "cost": 2,
            "projectile": {
                "damage": 192,
                "radius": 2.5,
                "hitEffects": [
                  {
                    "type": "STUN",
                    "duration": 0.5
                  }
                ]
            }
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);

    assertThat(card.getId()).isEqualTo("zap");
    assertThat(card.getType()).isEqualTo(CardType.SPELL);

    // Check projectile stats
    ProjectileStats proj = card.getProjectile();
    assertThat(proj).isNotNull();
    assertThat(proj.getDamage()).isEqualTo(192);
    assertThat(proj.getRadius()).isCloseTo(2.5f, within(0.01f));

    assertThat(proj.getHitEffects()).hasSize(1);
    assertThat(proj.getHitEffects().get(0).getType()).isEqualTo(StatusEffectType.STUN);
    assertThat(proj.getHitEffects().get(0).getDuration()).isCloseTo(0.5f, within(0.01f));
  }

  @Test
  void loadCards_shouldParseMultipleUnitsInArray() {
    String json = """
        [
          {
            "id": "archers",
            "name": "Archers",
            "type": "TROOP",
            "cost": 3,
            "units": [
              {
                "name": "Archer",
                "health": 119,
                "damage": 44,
                "targetType": "ALL",
                "movementType": "GROUND",
                "offsetX": 0.5,
                "offsetY": 0.0
              },
              {
                "name": "Archer",
                "health": 119,
                "damage": 44,
                "targetType": "ALL",
                "movementType": "GROUND",
                "offsetX": -0.5,
                "offsetY": 0.0
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);

    assertThat(card.getTroops()).hasSize(2);
    assertThat(card.getTroops().get(0).getName()).isEqualTo("Archer");
    assertThat(card.getTroops().get(0).getOffsetX()).isCloseTo(0.5f, within(0.01f));
    assertThat(card.getTroops().get(1).getOffsetX()).isCloseTo(-0.5f, within(0.01f));
  }

  @Test
  void loadCards_shouldHandleMultipleUnitsWithCount() {
    String json = """
        [
          {
            "id": "minions",
            "name": "Minions",
            "type": "TROOP",
            "cost": 3,
            "units": [
              {
                "name": "Minion",
                "health": 252,
                "damage": 84,
                "targetType": "ALL",
                "movementType": "AIR",
                "count": 3
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);

    assertThat(card.getTroops()).hasSize(3);
    assertThat(card.getTroops().get(0).getName()).isEqualTo("Minion");
    assertThat(card.getTroops().get(0).getMovementType()).isEqualTo(MovementType.AIR);
  }

  @Test
  void loadCards_shouldParseProjectileConfig() {
    String json = """
        [
          {
            "id": "archers",
            "name": "Archers",
            "type": "TROOP",
            "cost": 3,
            "units": [
              {
                "name": "Archer",
                "health": 304,
                "damage": 107,
                "targetType": "ALL",
                "movementType": "GROUND",
                "projectile": {
                  "name": "ArcherArrow",
                  "speed": 600,
                  "homing": true
                }
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);
    TroopStats stats = card.getTroops().get(0);

    assertThat(stats.getProjectile()).isNotNull();
    ProjectileStats proj = stats.getProjectile();

    assertThat(proj.getName()).isEqualTo("ArcherArrow");
    assertThat(proj.getSpeed()).isCloseTo(10.0f, within(0.01f));
    assertThat(proj.isHoming()).isTrue();
  }

  @Test
  void loadCards_shouldPrioritizeProjectileDamage() {
    String json = """
        [
          {
            "id": "firecracker",
            "name": "Firecracker",
            "type": "TROOP",
            "cost": 3,
            "units": [
              {
                "name": "Firecracker",
                "damage": 50,
                "targetType": "ALL",
                "movementType": "GROUND",
                "projectile": {
                  "name": "Rocket",
                  "damage": 100,
                  "speed": 600
                }
              }
            ]
          },
          {
            "id": "musketeer",
            "name": "Musketeer",
            "type": "TROOP",
            "cost": 4,
            "units": [
              {
                "name": "Musketeer",
                "damage": 200,
                "targetType": "ALL",
                "movementType": "GROUND",
                "projectile": {
                  "name": "Bullet",
                  "damage": 0,
                  "speed": 600
                }
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(2);

    Card firecracker = cards.get(0);
    TroopStats fcStats = firecracker.getTroops().get(0);
    assertThat(fcStats.getDamage()).isEqualTo(50);
    assertThat(fcStats.getProjectile().getDamage()).isEqualTo(100);

    Card musketeer = cards.get(1);
    TroopStats mStats = musketeer.getTroops().get(0);
    assertThat(mStats.getDamage()).isEqualTo(200);
    assertThat(mStats.getProjectile().getDamage()).isEqualTo(200);
  }

  @Test
  void loadCards_shouldParseIceWizardSpawnStats() {
    String json = """
        [
          {
            "id": "ice_wizard",
            "name": "Ice Wizard",
            "type": "TROOP",
            "cost": 3,
            "units": [
              {
                "name": "Ice Wizard",
                "damage": 89,
                "targetType": "ALL",
                "movementType": "GROUND",
                "spawnDamage": 84,
                "spawnRadius": 1.0,
                "spawnEffects": [
                  {
                    "type": "SLOW",
                    "duration": 1.0,
                    "intensity": 0.3
                  }
                ],
                "projectile": {
                    "name": "ice_wizardProjectile",
                    "damage": 89,
                    "speed": 700,
                    "radius": 1.5,
                    "homing": true,
                    "hitEffects": [
                      {
                        "type": "SLOW",
                        "duration": 2.5,
                        "intensity": 0.3
                      }
                    ]
                }
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    TroopStats stats = cards.get(0).getTroops().get(0);

    // Verify Spawn Damage
    assertThat(stats.getSpawnDamage()).isEqualTo(84);
    assertThat(stats.getSpawnRadius()).isCloseTo(1.0f, within(0.01f));

    // Verify Spawn Effects
    assertThat(stats.getSpawnEffects()).hasSize(1);
    assertThat(stats.getSpawnEffects().get(0).getType()).isEqualTo(StatusEffectType.SLOW);
    assertThat(stats.getSpawnEffects().get(0).getDuration()).isCloseTo(1.0f, within(0.01f));
    assertThat(stats.getSpawnEffects().get(0).getIntensity()).isCloseTo(0.3f, within(0.01f));

    // Verify Projectile Inheritance (Damage 89 from Unit)
    assertThat(stats.getProjectile()).isNotNull();
    assertThat(stats.getProjectile().getDamage()).isEqualTo(89);
  }

  @Test
  void loadCards_shouldParseBuildingFromUnitLevel() {
    String json = """
        [
          {
            "id": "cannon",
            "name": "Cannon",
            "type": "BUILDING",
            "rarity": "Common",
            "cost": 3,
            "units": [
              {
                "name": "Cannon",
                "health": 322,
                "damage": 83,
                "speed": 0.0,
                "collisionRadius": 0.6,
                "sightRange": 5.5,
                "range": 5.5,
                "attackCooldown": 1.0,
                "loadTime": 0.1,
                "deployTime": 1.0,
                "targetType": "GROUND",
                "movementType": "GROUND",
                "visualRadius": 1.5,
                "lifeTime": 30.0,
                "projectile": {
                  "name": "TowerCannonball",
                  "damage": 83,
                  "speed": 1000,
                  "homing": true
                }
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);

    assertThat(card.getType()).isEqualTo(CardType.BUILDING);
    assertThat(card.getBuildingHealth()).isEqualTo(322);
    assertThat(card.getBuildingLifetime()).isCloseTo(30.0f, within(0.01f));

    TroopStats stats = card.getTroops().get(0);
    assertThat(stats.getDamage()).isEqualTo(83);
    assertThat(stats.getProjectile()).isNotNull();
    assertThat(stats.getProjectile().getName()).isEqualTo("TowerCannonball");
  }

  @Test
  void loadCards_shouldParseSpawnerBuildingFromUnitLevel() {
    String json = """
        [
          {
            "id": "tombstone",
            "name": "Tombstone",
            "type": "BUILDING",
            "rarity": "Rare",
            "cost": 3,
            "units": [
              {
                "name": "Tombstone",
                "health": 207,
                "damage": 0,
                "speed": 0.0,
                "movementType": "GROUND",
                "visualRadius": 1.5,
                "lifeTime": 30.0,
                "deathSpawn": [
                  {
                    "spawnCharacter": "Skeleton",
                    "spawnNumber": 4
                  }
                ],
                "liveSpawn": {
                  "spawnCharacter": "Skeleton",
                  "spawnNumber": 2,
                  "spawnPauseTime": 3.5,
                  "spawnInterval": 0.5
                }
              },
              {
                "name": "Skeleton",
                "health": 32,
                "damage": 32,
                "speed": 90.0,
                "movementType": "GROUND",
                "targetType": "GROUND"
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);

    assertThat(card.getType()).isEqualTo(CardType.BUILDING);
    assertThat(card.getBuildingHealth()).isEqualTo(207);
    assertThat(card.getBuildingLifetime()).isCloseTo(30.0f, within(0.01f));
    assertThat(card.getSpawnInterval()).isCloseTo(0.5f, within(0.01f));
    assertThat(card.getSpawnPauseTime()).isCloseTo(3.5f, within(0.01f));
    assertThat(card.getSpawnNumber()).isEqualTo(2);
    assertThat(card.getDeathSpawnCount()).isEqualTo(4);

    // Second unit is the spawn template
    assertThat(card.getTroops()).hasSize(2);
    assertThat(card.getTroops().get(1).getName()).isEqualTo("Skeleton");
    assertThat(card.getTroops().get(1).getDamage()).isEqualTo(32);
  }

  @Test
  void loadCards_shouldLoadAllCardsFromResource() {
    InputStream is = CardLoaderTest.class.getResourceAsStream("/cards/cards.json");
    assertThat(is).isNotNull();

    List<Card> cards = CardLoader.loadCards(is);
    assertThat(cards).hasSize(121);

    // Spot check Knight
    Card knight = cards.stream().filter(c -> c.getId().equals("knight")).findFirst().orElse(null);
    assertThat(knight).isNotNull();
    assertThat(knight.getType()).isEqualTo(CardType.TROOP);
    assertThat(knight.getCost()).isEqualTo(3);
    assertThat(knight.getTroops()).hasSize(1);

    // Spot check Tombstone (spawner building)
    Card tombstone = cards.stream().filter(c -> c.getId().equals("tombstone")).findFirst().orElse(null);
    assertThat(tombstone).isNotNull();
    assertThat(tombstone.getType()).isEqualTo(CardType.BUILDING);
    assertThat(tombstone.getSpawnInterval()).isGreaterThan(0f);
    assertThat(tombstone.getDeathSpawnCount()).isEqualTo(4);
    assertThat(tombstone.getTroops()).hasSizeGreaterThanOrEqualTo(2);

    // Spot check Cannon (building with projectile)
    Card cannon = cards.stream().filter(c -> c.getId().equals("cannon")).findFirst().orElse(null);
    assertThat(cannon).isNotNull();
    assertThat(cannon.getType()).isEqualTo(CardType.BUILDING);
    assertThat(cannon.getBuildingLifetime()).isGreaterThan(0f);
    assertThat(cannon.getTroops().get(0).getProjectile()).isNotNull();

    // Spot check spell (Fireball)
    Card fireball = cards.stream().filter(c -> c.getId().equals("fireball")).findFirst().orElse(null);
    assertThat(fireball).isNotNull();
    assertThat(fireball.getType()).isEqualTo(CardType.SPELL);
    assertThat(fireball.getProjectile()).isNotNull();
    assertThat(fireball.getProjectile().getDamage()).isGreaterThan(0);

    // Spot check Zap (area effect spell)
    Card zap = cards.stream().filter(c -> c.getId().equals("zap")).findFirst().orElse(null);
    assertThat(zap).isNotNull();
    assertThat(zap.getAreaEffect()).isNotNull();
    assertThat(zap.getAreaEffect().getDamage()).isEqualTo(75);
    assertThat(zap.getAreaEffect().getRadius()).isCloseTo(2.5f, within(0.01f));
    assertThat(zap.getAreaEffect().getBuff()).isEqualTo("ZapFreeze");

    // Spot check ElectroWizard (deploy effect)
    Card ewiz = cards.stream().filter(c -> c.getId().equals("electrowizard")).findFirst().orElse(null);
    assertThat(ewiz).isNotNull();
    assertThat(ewiz.getDeployEffect()).isNotNull();
    assertThat(ewiz.getDeployEffect().getDamage()).isEqualTo(75);
    assertThat(ewiz.getDeployEffect().getBuff()).isEqualTo("ZapFreeze");
  }

  @Test
  void loadCards_shouldParseAreaEffectSpell() {
    String json = """
        [
          {
            "id": "zap",
            "name": "Zap",
            "type": "SPELL",
            "rarity": "Common",
            "cost": 2,
            "areaEffect": {
              "name": "Zap",
              "radius": 2.5,
              "lifeDuration": 0.001,
              "hitsGround": true,
              "hitsAir": true,
              "damage": 75,
              "buff": "ZapFreeze",
              "buffDuration": 0.5,
              "crownTowerDamagePercent": -70
            }
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);
    assertThat(card.getType()).isEqualTo(CardType.SPELL);

    AreaEffectStats ae = card.getAreaEffect();
    assertThat(ae).isNotNull();
    assertThat(ae.getName()).isEqualTo("Zap");
    assertThat(ae.getRadius()).isCloseTo(2.5f, within(0.01f));
    assertThat(ae.getLifeDuration()).isCloseTo(0.001f, within(0.0001f));
    assertThat(ae.isHitsGround()).isTrue();
    assertThat(ae.isHitsAir()).isTrue();
    assertThat(ae.getDamage()).isEqualTo(75);
    assertThat(ae.getBuff()).isEqualTo("ZapFreeze");
    assertThat(ae.getBuffDuration()).isCloseTo(0.5f, within(0.01f));
    assertThat(ae.getCrownTowerDamagePercent()).isEqualTo(-70);
  }

  @Test
  void loadCards_shouldParseDeployEffect() {
    String json = """
        [
          {
            "id": "electrowizard",
            "name": "Electro Wizard",
            "type": "TROOP",
            "rarity": "Legendary",
            "cost": 4,
            "deployEffect": {
              "name": "ElectroWizardZap",
              "radius": 3.0,
              "lifeDuration": 0.001,
              "hitsGround": true,
              "hitsAir": true,
              "damage": 75,
              "buff": "ZapFreeze",
              "buffDuration": 0.5,
              "crownTowerDamagePercent": -100
            },
            "units": [
              {
                "name": "ElectroWizard",
                "health": 433,
                "damage": 75,
                "targetType": "ALL",
                "movementType": "GROUND"
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);
    assertThat(card.getType()).isEqualTo(CardType.TROOP);

    AreaEffectStats de = card.getDeployEffect();
    assertThat(de).isNotNull();
    assertThat(de.getName()).isEqualTo("ElectroWizardZap");
    assertThat(de.getRadius()).isCloseTo(3.0f, within(0.01f));
    assertThat(de.getDamage()).isEqualTo(75);
    assertThat(de.getBuff()).isEqualTo("ZapFreeze");
    assertThat(de.getCrownTowerDamagePercent()).isEqualTo(-100);
  }

  @Test
  void loadCards_shouldParseProjectileTargetBuff() {
    String json = """
        [
          {
            "id": "icewizard",
            "name": "Ice Wizard",
            "type": "TROOP",
            "rarity": "Legendary",
            "cost": 3,
            "units": [
              {
                "name": "IceWizard",
                "health": 590,
                "damage": 63,
                "targetType": "ALL",
                "movementType": "GROUND",
                "projectile": {
                  "name": "IceWizardProjectile",
                  "damage": 63,
                  "speed": 600,
                  "homing": true,
                  "targetBuff": "IceWizardSlowDown",
                  "buffDuration": 2.5
                }
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    ProjectileStats proj = cards.get(0).getTroops().get(0).getProjectile();
    assertThat(proj).isNotNull();
    assertThat(proj.getTargetBuff()).isEqualTo(StatusEffectType.SLOW);
    assertThat(proj.getBuffDuration()).isCloseTo(2.5f, within(0.01f));
  }

  @Test
  void loadCards_shouldParseTargetingAndCombatModifiers() {
    // Load from real cards.json and spot check Giant and Miner
    InputStream is = CardLoaderTest.class.getResourceAsStream("/cards/cards.json");
    assertThat(is).isNotNull();
    List<Card> cards = CardLoader.loadCards(is);

    // Giant: targetOnlyBuildings=true, ignorePushback=true
    Card giant = cards.stream().filter(c -> c.getId().equals("giant")).findFirst().orElse(null);
    assertThat(giant).isNotNull();
    TroopStats giantStats = giant.getTroops().get(0);
    assertThat(giantStats.isTargetOnlyBuildings()).isTrue();
    assertThat(giantStats.isIgnorePushback()).isTrue();
    assertThat(giantStats.getCrownTowerDamagePercent()).isEqualTo(0);

    // Miner: crownTowerDamagePercent=-75
    Card miner = cards.stream().filter(c -> c.getId().equals("miner")).findFirst().orElse(null);
    assertThat(miner).isNotNull();
    TroopStats minerStats = miner.getTroops().get(0);
    assertThat(minerStats.getCrownTowerDamagePercent()).isEqualTo(-75);
    assertThat(minerStats.isTargetOnlyBuildings()).isFalse();

    // Knight: all modifiers default to false/0
    Card knight = cards.stream().filter(c -> c.getId().equals("knight")).findFirst().orElse(null);
    assertThat(knight).isNotNull();
    TroopStats knightStats = knight.getTroops().get(0);
    assertThat(knightStats.isTargetOnlyBuildings()).isFalse();
    assertThat(knightStats.isIgnorePushback()).isFalse();
    assertThat(knightStats.getMinimumRange()).isEqualTo(0f);
    assertThat(knightStats.getCrownTowerDamagePercent()).isEqualTo(0);
  }

  @Test
  void loadCards_shouldParseDeathDamageAndDeathSpawns() {
    // Load from real cards.json and spot check death mechanics
    InputStream is = CardLoaderTest.class.getResourceAsStream("/cards/cards.json");
    assertThat(is).isNotNull();
    List<Card> cards = CardLoader.loadCards(is);

    // Golem: deathDamage=88, deathDamageRadius=2.0
    // Note: Golem's deathSpawn references "Golemite" which is a sub-character not present
    // as a unit in any card, so it is gracefully skipped (empty list).
    Card golem = cards.stream().filter(c -> c.getId().equals("golem")).findFirst().orElse(null);
    assertThat(golem).isNotNull();
    TroopStats golemStats = golem.getTroops().get(0);
    assertThat(golemStats.getDeathDamage()).isEqualTo(88);
    assertThat(golemStats.getDeathDamageRadius()).isCloseTo(2.0f, within(0.01f));

    // BattleRam: deathSpawn -> 2 Barbarians (resolvable via character lookup)
    Card battleRam = cards.stream()
        .filter(c -> c.getId().equals("battleram")).findFirst().orElse(null);
    assertThat(battleRam).isNotNull();
    TroopStats ramStats = battleRam.getTroops().get(0);
    assertThat(ramStats.getDeathSpawns()).hasSize(1);
    DeathSpawnEntry ramDeathSpawn = ramStats.getDeathSpawns().get(0);
    assertThat(ramDeathSpawn.count()).isEqualTo(2);
    assertThat(ramDeathSpawn.radius()).isCloseTo(0.6f, within(0.01f));
    assertThat(ramDeathSpawn.stats().getName()).isEqualTo("Barbarian");
    assertThat(ramDeathSpawn.stats().getHealth()).isGreaterThan(0);

    // GoblinCage: deathSpawn -> 1 GoblinBrawler (in same card's units[])
    Card goblinCage = cards.stream()
        .filter(c -> c.getId().equals("goblincage")).findFirst().orElse(null);
    assertThat(goblinCage).isNotNull();
    TroopStats cageStats = goblinCage.getTroops().get(0);
    assertThat(cageStats.getDeathSpawns()).hasSize(1);
    assertThat(cageStats.getDeathSpawns().get(0).stats().getName()).isEqualTo("GoblinBrawler");
    assertThat(cageStats.getDeathSpawns().get(0).count()).isEqualTo(1);

    // Knight: no death mechanics
    Card knight = cards.stream().filter(c -> c.getId().equals("knight")).findFirst().orElse(null);
    assertThat(knight).isNotNull();
    assertThat(knight.getTroops().get(0).getDeathDamage()).isEqualTo(0);
    assertThat(knight.getTroops().get(0).getDeathSpawns()).isEmpty();
  }

  @Test
  void loadCards_shouldParseAreaEffectsAndDeployEffects() {
    InputStream is = CardLoaderTest.class.getResourceAsStream("/cards/cards.json");
    assertThat(is).isNotNull();
    List<Card> cards = CardLoader.loadCards(is);

    // Zap: one-shot area effect with stun
    Card zap = cards.stream().filter(c -> c.getId().equals("zap")).findFirst().orElse(null);
    assertThat(zap).isNotNull();
    assertThat(zap.getAreaEffect()).isNotNull();
    AreaEffectStats zapAoe = zap.getAreaEffect();
    assertThat(zapAoe.getName()).isEqualTo("Zap");
    assertThat(zapAoe.getRadius()).isCloseTo(2.5f, within(0.01f));
    assertThat(zapAoe.getLifeDuration()).isCloseTo(0.001f, within(0.001f));
    assertThat(zapAoe.getDamage()).isEqualTo(75);
    assertThat(zapAoe.getBuff()).isEqualTo("ZapFreeze");
    assertThat(zapAoe.getBuffDuration()).isCloseTo(0.5f, within(0.01f));
    assertThat(zapAoe.getCrownTowerDamagePercent()).isEqualTo(-70);

    // Poison: ticking area effect
    Card poison = cards.stream().filter(c -> c.getId().equals("poison")).findFirst().orElse(null);
    assertThat(poison).isNotNull();
    assertThat(poison.getAreaEffect()).isNotNull();
    AreaEffectStats poisonAoe = poison.getAreaEffect();
    assertThat(poisonAoe.getHitSpeed()).isCloseTo(0.25f, within(0.01f));
    assertThat(poisonAoe.getLifeDuration()).isCloseTo(8.0f, within(0.01f));
    assertThat(poisonAoe.getBuff()).isEqualTo("Poison");

    // Earthquake: ground-only ticking effect
    Card earthquake = cards.stream()
        .filter(c -> c.getId().equals("earthquake")).findFirst().orElse(null);
    assertThat(earthquake).isNotNull();
    AreaEffectStats eqAoe = earthquake.getAreaEffect();
    assertThat(eqAoe.isHitsGround()).isTrue();
    assertThat(eqAoe.isHitsAir()).isFalse();

    // ElectroWizard: deploy effect
    Card ewiz = cards.stream()
        .filter(c -> c.getId().equals("electrowizard")).findFirst().orElse(null);
    assertThat(ewiz).isNotNull();
    assertThat(ewiz.getDeployEffect()).isNotNull();
    AreaEffectStats deployFx = ewiz.getDeployEffect();
    assertThat(deployFx.getName()).isEqualTo("ElectroWizardZap");
    assertThat(deployFx.getRadius()).isCloseTo(3.0f, within(0.01f));
    assertThat(deployFx.getDamage()).isEqualTo(75);
    assertThat(deployFx.getBuff()).isEqualTo("ZapFreeze");

    // Knight: no area effect or deploy effect
    Card knight = cards.stream().filter(c -> c.getId().equals("knight")).findFirst().orElse(null);
    assertThat(knight).isNotNull();
    assertThat(knight.getAreaEffect()).isNull();
    assertThat(knight.getDeployEffect()).isNull();
  }

  @Test
  void loadCards_shouldParseShieldBuffOnDamageAndMultipleTargets() {
    InputStream is = CardLoaderTest.class.getResourceAsStream("/cards/cards.json");
    assertThat(is).isNotNull();
    List<Card> cards = CardLoader.loadCards(is);

    // DarkPrince: shieldHitpoints=94
    Card darkPrince = cards.stream()
        .filter(c -> c.getId().equals("darkprince")).findFirst().orElse(null);
    assertThat(darkPrince).isNotNull();
    TroopStats dpStats = darkPrince.getTroops().get(0);
    assertThat(dpStats.getShieldHitpoints()).isEqualTo(94);

    // ElectroWizard: multipleTargets=2, buffOnDamage (ZapFreeze -> STUN, 0.5s)
    Card ewiz = cards.stream()
        .filter(c -> c.getId().equals("electrowizard")).findFirst().orElse(null);
    assertThat(ewiz).isNotNull();
    TroopStats ewizStats = ewiz.getTroops().get(0);
    assertThat(ewizStats.getMultipleTargets()).isEqualTo(2);
    assertThat(ewizStats.getBuffOnDamage()).isNotNull();
    assertThat(ewizStats.getBuffOnDamage().getType()).isEqualTo(StatusEffectType.STUN);
    assertThat(ewizStats.getBuffOnDamage().getDuration()).isCloseTo(0.5f, within(0.01f));

    // Hunter: multipleProjectiles=10
    Card hunter = cards.stream()
        .filter(c -> c.getId().equals("hunter")).findFirst().orElse(null);
    assertThat(hunter).isNotNull();
    TroopStats hunterStats = hunter.getTroops().get(0);
    assertThat(hunterStats.getMultipleProjectiles()).isEqualTo(10);

    // Knight: no shield, no buffOnDamage, no multipleTargets
    Card knight = cards.stream().filter(c -> c.getId().equals("knight")).findFirst().orElse(null);
    assertThat(knight).isNotNull();
    TroopStats knightStats = knight.getTroops().get(0);
    assertThat(knightStats.getShieldHitpoints()).isEqualTo(0);
    assertThat(knightStats.getBuffOnDamage()).isNull();
    assertThat(knightStats.getMultipleTargets()).isEqualTo(0);
  }

  @Test
  void loadCards_shouldParseChargeAbility() {
    InputStream is = CardLoaderTest.class.getResourceAsStream("/cards/cards.json");
    assertThat(is).isNotNull();
    List<Card> cards = CardLoader.loadCards(is);

    // Prince: CHARGE ability with damage=306, speedMultiplier=2.0
    Card prince = cards.stream()
        .filter(c -> c.getId().equals("prince")).findFirst().orElse(null);
    assertThat(prince).isNotNull();
    TroopStats princeStats = prince.getTroops().get(0);
    assertThat(princeStats.getAbility()).isNotNull();
    assertThat(princeStats.getAbility().getType()).isEqualTo(AbilityType.CHARGE);
    assertThat(princeStats.getAbility().getChargeDamage()).isEqualTo(306);
    assertThat(princeStats.getAbility().getSpeedMultiplier()).isEqualTo(2.0f);

    // DarkPrince: CHARGE ability with damage=208
    Card darkPrince = cards.stream()
        .filter(c -> c.getId().equals("darkprince")).findFirst().orElse(null);
    assertThat(darkPrince).isNotNull();
    TroopStats dpStats = darkPrince.getTroops().get(0);
    assertThat(dpStats.getAbility()).isNotNull();
    assertThat(dpStats.getAbility().getType()).isEqualTo(AbilityType.CHARGE);
    assertThat(dpStats.getAbility().getChargeDamage()).isEqualTo(208);

    // Knight: no ability
    Card knight = cards.stream().filter(c -> c.getId().equals("knight")).findFirst().orElse(null);
    assertThat(knight).isNotNull();
    assertThat(knight.getTroops().get(0).getAbility()).isNull();
  }

  @Test
  void loadCards_shouldParseVariableDamageAbility() {
    InputStream is = CardLoaderTest.class.getResourceAsStream("/cards/cards.json");
    assertThat(is).isNotNull();
    List<Card> cards = CardLoader.loadCards(is);

    // InfernoDragon: VARIABLE_DAMAGE with 3 stages (14 -> 47 -> 165)
    Card infernoDragon = cards.stream()
        .filter(c -> c.getId().equals("infernodragon")).findFirst().orElse(null);
    assertThat(infernoDragon).isNotNull();
    TroopStats idStats = infernoDragon.getTroops().get(0);
    assertThat(idStats.getAbility()).isNotNull();
    assertThat(idStats.getAbility().getType()).isEqualTo(AbilityType.VARIABLE_DAMAGE);
    assertThat(idStats.getAbility().getStages()).hasSize(3);
    assertThat(idStats.getAbility().getStages().get(0).damage()).isEqualTo(14);
    assertThat(idStats.getAbility().getStages().get(0).durationSeconds()).isEqualTo(0f);
    assertThat(idStats.getAbility().getStages().get(1).damage()).isEqualTo(47);
    assertThat(idStats.getAbility().getStages().get(1).durationSeconds()).isEqualTo(2.0f);
    assertThat(idStats.getAbility().getStages().get(2).damage()).isEqualTo(165);
    assertThat(idStats.getAbility().getStages().get(2).durationSeconds()).isEqualTo(2.0f);

    // MightyMiner: VARIABLE_DAMAGE with 3 stages (16 -> 80 -> 160)
    Card mightyMiner = cards.stream()
        .filter(c -> c.getId().equals("mightyminer")).findFirst().orElse(null);
    assertThat(mightyMiner).isNotNull();
    assertThat(mightyMiner.getTroops().get(0).getAbility()).isNotNull();
    assertThat(mightyMiner.getTroops().get(0).getAbility().getType())
        .isEqualTo(AbilityType.VARIABLE_DAMAGE);
    assertThat(mightyMiner.getTroops().get(0).getAbility().getStages()).hasSize(3);
    assertThat(mightyMiner.getTroops().get(0).getAbility().getStages().get(0).damage())
        .isEqualTo(16);
  }
}
