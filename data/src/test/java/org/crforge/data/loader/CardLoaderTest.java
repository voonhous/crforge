package org.crforge.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.crforge.core.ability.AbilityType;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.DamageTier;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.SpawnSequenceEntry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.junit.jupiter.api.Test;

class CardLoaderTest {

  // -- Helper methods for building pre-loaded maps --

  private static Map<String, TroopStats> unitMap(TroopStats... stats) {
    Map<String, TroopStats> map = new java.util.LinkedHashMap<>();
    for (TroopStats s : stats) {
      map.put(s.getName(), s);
    }
    return map;
  }

  private static Map<String, ProjectileStats> projMap(ProjectileStats... stats) {
    Map<String, ProjectileStats> map = new java.util.LinkedHashMap<>();
    for (ProjectileStats s : stats) {
      map.put(s.getName(), s);
    }
    return map;
  }

  private static InputStream toStream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  // -- Basic card loading tests --

  @Test
  void loadCards_shouldParseTroopCard() {
    TroopStats knight =
        TroopStats.builder()
            .name("Knight")
            .health(1452)
            .damage(167)
            .speed(1.0f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    String json =
        """
        [
          {
            "id": "knight",
            "name": "Knight",
            "description": "A tough melee fighter",
            "type": "TROOP",
            "cost": 3,
            "unit": "Knight"
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(knight), Map.of());

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);

    assertThat(card.getId()).isEqualTo("knight");
    assertThat(card.getName()).isEqualTo("Knight");
    assertThat(card.getType()).isEqualTo(CardType.TROOP);
    assertThat(card.getCost()).isEqualTo(3);
    assertThat(card.getUnitStats()).isNotNull();
    assertThat(card.getUnitStats().getName()).isEqualTo("Knight");
    assertThat(card.getUnitStats().getHealth()).isEqualTo(1452);
    assertThat(card.getUnitCount()).isEqualTo(1);
  }

  @Test
  void loadCards_shouldParseMultiUnitCard() {
    TroopStats archer =
        TroopStats.builder()
            .name("Archer")
            .health(119)
            .damage(44)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    String json =
        """
        [
          {
            "id": "archer",
            "name": "Archer",
            "type": "TROOP",
            "cost": 3,
            "unit": "Archer",
            "count": 2
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(archer), Map.of());

    Card card = cards.get(0);
    assertThat(card.getUnitStats()).isNotNull();
    assertThat(card.getUnitStats().getName()).isEqualTo("Archer");
    assertThat(card.getUnitCount()).isEqualTo(2);
  }

  @Test
  void loadCards_shouldParseCountFiveWithSummonRadius() {
    TroopStats barb =
        TroopStats.builder()
            .name("Barbarian")
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    String json =
        """
        [
          {
            "id": "barbarians",
            "name": "Barbarians",
            "type": "TROOP",
            "cost": 5,
            "unit": "Barbarian",
            "count": 5,
            "summonRadius": 700.0
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(barb), Map.of());

    Card card = cards.get(0);
    assertThat(card.getUnitCount()).isEqualTo(5);
    assertThat(card.getSummonRadius()).isCloseTo(700.0f, within(0.01f));
  }

  @Test
  void loadCards_shouldParseBuildingCard() {
    TroopStats cannon =
        TroopStats.builder()
            .name("Cannon")
            .health(322)
            .damage(83)
            .lifeTime(30.0f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    String json =
        """
        [
          {
            "id": "cannon",
            "name": "Cannon",
            "type": "BUILDING",
            "cost": 3,
            "unit": "Cannon"
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(cannon), Map.of());

    Card card = cards.get(0);
    assertThat(card.getType()).isEqualTo(CardType.BUILDING);
    assertThat(card.getUnitStats()).isNotNull();
    assertThat(card.getUnitStats().getHealth()).isEqualTo(322);
    assertThat(card.getUnitStats().getLifeTime()).isCloseTo(30.0f, within(0.01f));
  }

  @Test
  void loadCards_shouldParseSpawnerBuilding() {
    TroopStats skeleton =
        TroopStats.builder()
            .name("Skeleton")
            .health(67)
            .damage(32)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    TroopStats tombstone =
        TroopStats.builder()
            .name("Tombstone")
            .health(207)
            .lifeTime(30.0f)
            .movementType(MovementType.GROUND)
            .liveSpawn(new LiveSpawnConfig("Skeleton", 2, 3.5f, 0.5f, 0f, 0f, false))
            .build();

    String json =
        """
        [
          {
            "id": "tombstone",
            "name": "Tombstone",
            "type": "BUILDING",
            "cost": 3,
            "unit": "Tombstone"
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(tombstone, skeleton), Map.of());

    Card card = cards.get(0);
    assertThat(card.getUnitStats().getLiveSpawn()).isNotNull();
    assertThat(card.getUnitStats().getLiveSpawn().spawnNumber()).isEqualTo(2);
    assertThat(card.getUnitStats().getLiveSpawn().spawnPauseTime()).isCloseTo(3.5f, within(0.01f));
    assertThat(card.getUnitStats().getLiveSpawn().spawnInterval()).isCloseTo(0.5f, within(0.01f));

    // Spawn template should be resolved
    assertThat(card.getSpawnTemplate()).isNotNull();
    assertThat(card.getSpawnTemplate().getName()).isEqualTo("Skeleton");
    assertThat(card.getSpawnTemplate().getDamage()).isEqualTo(32);
  }

  @Test
  void loadCards_shouldParseSpellWithProjectile() {
    ProjectileStats fireball =
        ProjectileStats.builder()
            .name("FireballSpell")
            .damage(325)
            .speed(6.67f)
            .radius(2.5f)
            .build();

    String json =
        """
        [
          {
            "id": "fireball",
            "name": "Fireball",
            "type": "SPELL",
            "rarity": "Rare",
            "cost": 4,
            "projectile": "FireballSpell"
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), Map.of(), projMap(fireball));

    Card card = cards.get(0);
    assertThat(card.getType()).isEqualTo(CardType.SPELL);
    assertThat(card.getProjectile()).isNotNull();
    assertThat(card.getProjectile().getDamage()).isEqualTo(325);
    assertThat(card.getUnitStats()).isNull();
  }

  @Test
  void loadCards_shouldParseSpellWithAreaEffect() {
    String json =
        """
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

    List<Card> cards = CardLoader.loadCards(toStream(json), Map.of(), Map.of());

    Card card = cards.get(0);
    assertThat(card.getAreaEffect()).isNotNull();
    AreaEffectStats ae = card.getAreaEffect();
    assertThat(ae.getRadius()).isCloseTo(2.5f, within(0.01f));
    assertThat(ae.getDamage()).isEqualTo(75);
    assertThat(ae.getBuff()).isEqualTo("ZapFreeze");
    assertThat(ae.getBuffDuration()).isCloseTo(0.5f, within(0.01f));
  }

  @Test
  void loadCards_shouldParseDeployEffect() {
    TroopStats ewiz =
        TroopStats.builder()
            .name("ElectroWizard")
            .health(304)
            .damage(79)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    String json =
        """
        [
          {
            "id": "electrowizard",
            "name": "ElectroWizard",
            "type": "TROOP",
            "rarity": "Legendary",
            "cost": 4,
            "unit": "ElectroWizard",
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
            }
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(ewiz), Map.of());

    Card card = cards.get(0);
    assertThat(card.getDeployEffect()).isNotNull();
    assertThat(card.getDeployEffect().getDamage()).isEqualTo(75);
    assertThat(card.getDeployEffect().getBuff()).isEqualTo("ZapFreeze");
  }

  @Test
  void loadCards_shouldParseSummonCharacter() {
    TroopStats rageBottle =
        TroopStats.builder()
            .name("RageBarbarianBottle")
            .health(1)
            .movementType(MovementType.GROUND)
            .build();

    String json =
        """
        [
          {
            "id": "rage",
            "name": "Rage",
            "type": "SPELL",
            "rarity": "Epic",
            "cost": 2,
            "summonCharacter": "RageBarbarianBottle"
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(rageBottle), Map.of());

    Card card = cards.get(0);
    assertThat(card.getSummonTemplate()).isNotNull();
    assertThat(card.getSummonTemplate().getName()).isEqualTo("RageBarbarianBottle");
  }

  @Test
  void loadCards_shouldParseFormationOffsets() {
    TroopStats archer =
        TroopStats.builder()
            .name("Archer")
            .health(119)
            .damage(44)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    String json =
        """
        [
          {
            "id": "archer",
            "name": "Archer",
            "type": "TROOP",
            "cost": 3,
            "unit": "Archer",
            "count": 2,
            "formationOffsets": [[0.5, 0.0], [-0.5, 0.0]]
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(archer), Map.of());

    Card card = cards.get(0);
    assertThat(card.getFormationOffsets()).isNotNull();
    assertThat(card.getFormationOffsets()).hasSize(2);
    assertThat(card.getFormationOffsets().get(0)[0]).isCloseTo(0.5f, within(0.001f));
    assertThat(card.getFormationOffsets().get(0)[1]).isCloseTo(0.0f, within(0.001f));
    assertThat(card.getFormationOffsets().get(1)[0]).isCloseTo(-0.5f, within(0.001f));
    assertThat(card.getFormationOffsets().get(1)[1]).isCloseTo(0.0f, within(0.001f));
  }

  @Test
  void loadCards_shouldParseSecondaryUnit() {
    TroopStats goblin =
        TroopStats.builder()
            .name("Goblin_Stab")
            .health(80)
            .damage(50)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    TroopStats spearGoblin =
        TroopStats.builder()
            .name("SpearGoblin")
            .health(52)
            .damage(24)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    String json =
        """
        [
          {
            "id": "goblingang",
            "name": "GoblinGang",
            "type": "TROOP",
            "cost": 3,
            "unit": "Goblin_Stab",
            "count": 3,
            "secondaryUnit": "SpearGoblin",
            "secondaryCount": 3,
            "formationOffsets": [
              [-0.093, 1.519], [-1.223, -0.884], [1.288, -0.856],
              [0.0, -0.2], [-0.7, -1.6], [0.7, -1.6]
            ]
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(goblin, spearGoblin), Map.of());

    Card card = cards.get(0);
    assertThat(card.getUnitStats().getName()).isEqualTo("Goblin_Stab");
    assertThat(card.getUnitCount()).isEqualTo(3);
    assertThat(card.getSecondaryUnitStats()).isNotNull();
    assertThat(card.getSecondaryUnitStats().getName()).isEqualTo("SpearGoblin");
    assertThat(card.getSecondaryUnitCount()).isEqualTo(3);
    assertThat(card.getTotalDeployCount()).isEqualTo(6);
    assertThat(card.getFormationOffsets()).hasSize(6);
  }

  @Test
  void loadCards_shouldDefaultToNullFormationAndZeroSecondary() {
    TroopStats knight =
        TroopStats.builder()
            .name("Knight")
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    String json =
        """
        [
          {
            "id": "knight",
            "name": "Knight",
            "type": "TROOP",
            "cost": 3,
            "unit": "Knight"
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(knight), Map.of());

    Card card = cards.get(0);
    assertThat(card.getFormationOffsets()).isNull();
    assertThat(card.getSecondaryUnitStats()).isNull();
    assertThat(card.getSecondaryUnitCount()).isEqualTo(0);
    assertThat(card.getTotalDeployCount()).isEqualTo(1);
  }

  @Test
  void loadCards_shouldParseRarity() {
    String json =
        """
        [
          { "id": "a", "name": "A", "type": "TROOP", "cost": 1, "rarity": "Common" },
          { "id": "b", "name": "B", "type": "TROOP", "cost": 1, "rarity": "Rare" },
          { "id": "c", "name": "C", "type": "TROOP", "cost": 1, "rarity": "Epic" },
          { "id": "d", "name": "D", "type": "TROOP", "cost": 1, "rarity": "Legendary" }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), Map.of(), Map.of());

    assertThat(cards.get(0).getRarity()).isEqualTo(Rarity.COMMON);
    assertThat(cards.get(1).getRarity()).isEqualTo(Rarity.RARE);
    assertThat(cards.get(2).getRarity()).isEqualTo(Rarity.EPIC);
    assertThat(cards.get(3).getRarity()).isEqualTo(Rarity.LEGENDARY);
  }

  @Test
  void loadCards_shouldDefaultCountToOne() {
    TroopStats knight =
        TroopStats.builder()
            .name("Knight")
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    String json =
        """
        [
          {
            "id": "knight",
            "name": "Knight",
            "type": "TROOP",
            "cost": 3,
            "unit": "Knight"
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(knight), Map.of());
    assertThat(cards.get(0).getUnitCount()).isEqualTo(1);
  }

  @Test
  void loadCards_shouldParseEarthquakeSpell() {
    String json =
        """
        [
          {
            "id": "earthquake",
            "name": "Earthquake",
            "type": "SPELL",
            "rarity": "Rare",
            "cost": 3,
            "areaEffect": {
              "name": "Earthquake",
              "radius": 3.5,
              "lifeDuration": 3.0,
              "hitsGround": true,
              "hitsAir": false,
              "hitSpeed": 0.1,
              "buff": "Earthquake",
              "buffDuration": 1.0,
              "capBuffTimeToAreaEffectTime": true
            }
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), Map.of(), Map.of());

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);
    assertThat(card.getId()).isEqualTo("earthquake");
    assertThat(card.getType()).isEqualTo(CardType.SPELL);
    assertThat(card.getRarity()).isEqualTo(Rarity.RARE);
    assertThat(card.getCost()).isEqualTo(3);

    AreaEffectStats ae = card.getAreaEffect();
    assertThat(ae).isNotNull();
    assertThat(ae.getRadius()).isCloseTo(3.5f, within(0.01f));
    assertThat(ae.getLifeDuration()).isCloseTo(3.0f, within(0.01f));
    assertThat(ae.isHitsGround()).isTrue();
    assertThat(ae.isHitsAir()).isFalse();
    assertThat(ae.getHitSpeed()).isCloseTo(0.1f, within(0.01f));
    assertThat(ae.getBuff()).isEqualTo("Earthquake");
    assertThat(ae.getBuffDuration()).isCloseTo(1.0f, within(0.01f));
    assertThat(ae.isCapBuffTimeToAreaEffectTime()).isTrue();
  }

  // -- Integration test with real resource files --

  @Test
  void loadCards_shouldLoadAllCardsFromResource() {
    // Load all 4 resource files and verify cards load correctly
    Map<String, ProjectileStats> projectileMap;
    Map<String, TroopStats> uMap;

    try (InputStream pis = getClass().getResourceAsStream("/cards/projectiles.json")) {
      projectileMap = ProjectileLoader.loadProjectiles(pis);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    try (InputStream uis = getClass().getResourceAsStream("/cards/units.json")) {
      uMap = UnitLoader.loadUnits(uis, projectileMap);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    try (InputStream cis = getClass().getResourceAsStream("/cards/cards.json")) {
      List<Card> cards = CardLoader.loadCards(cis, uMap, projectileMap);

      // Should have all cards loaded
      assertThat(cards).isNotEmpty();
      assertThat(cards.size()).isGreaterThanOrEqualTo(90);

      // Spot-check some cards
      Card knight = cards.stream().filter(c -> "knight".equals(c.getId())).findFirst().orElse(null);
      assertThat(knight).isNotNull();
      assertThat(knight.getType()).isEqualTo(CardType.TROOP);
      assertThat(knight.getUnitStats()).isNotNull();
      assertThat(knight.getUnitCount()).isEqualTo(1);

      Card archer = cards.stream().filter(c -> "archer".equals(c.getId())).findFirst().orElse(null);
      assertThat(archer).isNotNull();
      assertThat(archer.getUnitCount()).isEqualTo(2);

      Card barbarians =
          cards.stream().filter(c -> "barbarians".equals(c.getId())).findFirst().orElse(null);
      assertThat(barbarians).isNotNull();
      assertThat(barbarians.getUnitCount()).isEqualTo(5);
      assertThat(barbarians.getSummonRadius()).isGreaterThan(0);

      // Building
      Card cannon = cards.stream().filter(c -> "cannon".equals(c.getId())).findFirst().orElse(null);
      assertThat(cannon).isNotNull();
      assertThat(cannon.getType()).isEqualTo(CardType.BUILDING);
      assertThat(cannon.getUnitStats()).isNotNull();
      assertThat(cannon.getUnitStats().getProjectile()).isNotNull();

      // Spawner building
      Card tombstone =
          cards.stream().filter(c -> "tombstone".equals(c.getId())).findFirst().orElse(null);
      assertThat(tombstone).isNotNull();
      assertThat(tombstone.getUnitStats().getLiveSpawn()).isNotNull();
      assertThat(tombstone.getSpawnTemplate()).isNotNull();
      assertThat(tombstone.getSpawnTemplate().getName()).isEqualTo("Skeleton");

      // Spell
      Card fireball =
          cards.stream().filter(c -> "fireball".equals(c.getId())).findFirst().orElse(null);
      assertThat(fireball).isNotNull();
      assertThat(fireball.getType()).isEqualTo(CardType.SPELL);
      assertThat(fireball.getProjectile()).isNotNull();
      assertThat(fireball.getUnitStats()).isNull();

      // Troop with death spawns (Golem -> Golemite)
      Card golem = cards.stream().filter(c -> "golem".equals(c.getId())).findFirst().orElse(null);
      assertThat(golem).isNotNull();
      assertThat(golem.getUnitStats().getDeathSpawns()).isNotEmpty();

      // Troop with liveSpawn (Witch)
      Card witch = cards.stream().filter(c -> "witch".equals(c.getId())).findFirst().orElse(null);
      assertThat(witch).isNotNull();
      assertThat(witch.getUnitStats().getLiveSpawn()).isNotNull();
      assertThat(witch.getUnitStats().getLiveSpawn().spawnCharacter()).isEqualTo("Skeleton");
      assertThat(witch.getUnitStats().getLiveSpawn().spawnNumber()).isEqualTo(4);
      assertThat(witch.getSpawnTemplate()).isNotNull();
      assertThat(witch.getSpawnTemplate().getName()).isEqualTo("Skeleton");

      // Troop with ability (Prince -> CHARGE)
      Card prince = cards.stream().filter(c -> "prince".equals(c.getId())).findFirst().orElse(null);
      assertThat(prince).isNotNull();
      assertThat(prince.getUnitStats().getAbility()).isNotNull();
      assertThat(prince.getUnitStats().getAbility().type()).isEqualTo(AbilityType.CHARGE);

      // Targeting modifier (Giant -> targetOnlyBuildings)
      Card giant = cards.stream().filter(c -> "giant".equals(c.getId())).findFirst().orElse(null);
      assertThat(giant).isNotNull();
      assertThat(giant.getUnitStats().isTargetOnlyBuildings()).isTrue();
      assertThat(giant.getUnitStats().isIgnorePushback()).isTrue();

      // Dark Prince shield
      Card darkPrince =
          cards.stream().filter(c -> "darkprince".equals(c.getId())).findFirst().orElse(null);
      assertThat(darkPrince).isNotNull();
      assertThat(darkPrince.getUnitStats().getShieldHitpoints()).isGreaterThan(0);

      // ElectroDragon chain lightning
      Card eDragon =
          cards.stream().filter(c -> "electrodragon".equals(c.getId())).findFirst().orElse(null);
      assertThat(eDragon).isNotNull();
      assertThat(eDragon.getUnitStats().getProjectile()).isNotNull();
      assertThat(eDragon.getUnitStats().getProjectile().getChainedHitCount()).isGreaterThan(0);

      // Hunter scatter projectiles
      Card hunter = cards.stream().filter(c -> "hunter".equals(c.getId())).findFirst().orElse(null);
      assertThat(hunter).isNotNull();
      assertThat(hunter.getUnitStats().getProjectile()).isNotNull();
      assertThat(hunter.getUnitStats().getProjectile().getProjectileRange()).isGreaterThan(0);

      // InfernoDragon variable damage
      Card infernoDragon =
          cards.stream().filter(c -> "infernodragon".equals(c.getId())).findFirst().orElse(null);
      assertThat(infernoDragon).isNotNull();
      assertThat(infernoDragon.getUnitStats().getAbility()).isNotNull();
      assertThat(infernoDragon.getUnitStats().getAbility().type())
          .isEqualTo(AbilityType.VARIABLE_DAMAGE);
      assertThat(infernoDragon.getUnitStats().getAbility())
          .isInstanceOf(org.crforge.core.ability.VariableDamageAbility.class);
      assertThat(
              ((org.crforge.core.ability.VariableDamageAbility)
                      infernoDragon.getUnitStats().getAbility())
                  .stages())
          .hasSize(3);

      // Bandit dash
      Card bandit =
          cards.stream().filter(c -> "assassin".equals(c.getId())).findFirst().orElse(null);
      assertThat(bandit).isNotNull();
      assertThat(bandit.getUnitStats().getAbility()).isNotNull();
      assertThat(bandit.getUnitStats().getAbility().type()).isEqualTo(AbilityType.DASH);

      // Fisherman hook
      Card fisherman =
          cards.stream().filter(c -> "fisherman".equals(c.getId())).findFirst().orElse(null);
      assertThat(fisherman).isNotNull();
      assertThat(fisherman.getUnitStats().getAbility()).isNotNull();
      assertThat(fisherman.getUnitStats().getAbility().type()).isEqualTo(AbilityType.HOOK);

      // ElectroGiant reflect
      Card eGiant =
          cards.stream().filter(c -> "electrogiant".equals(c.getId())).findFirst().orElse(null);
      assertThat(eGiant).isNotNull();
      assertThat(eGiant.getUnitStats().getAbility()).isNotNull();
      assertThat(eGiant.getUnitStats().getAbility().type()).isEqualTo(AbilityType.REFLECT);

      // Formation offsets: Barbarians should have 5 offsets
      assertThat(barbarians.getFormationOffsets()).isNotNull();
      assertThat(barbarians.getFormationOffsets()).hasSize(5);

      // Dual-unit card: GoblinGang (3 primary + 3 secondary)
      Card goblinGang =
          cards.stream().filter(c -> "goblingang".equals(c.getId())).findFirst().orElse(null);
      assertThat(goblinGang).isNotNull();
      assertThat(goblinGang.getUnitCount()).isEqualTo(3);
      assertThat(goblinGang.getSecondaryUnitStats()).isNotNull();
      assertThat(goblinGang.getSecondaryUnitStats().getName()).isEqualTo("SpearGoblin");
      assertThat(goblinGang.getSecondaryUnitCount()).isEqualTo(3);
      assertThat(goblinGang.getTotalDeployCount()).isEqualTo(6);
      assertThat(goblinGang.getFormationOffsets()).hasSize(6);

      // Dual-unit card: Rascals (1 primary + 2 secondary)
      Card rascals =
          cards.stream().filter(c -> "rascals".equals(c.getId())).findFirst().orElse(null);
      assertThat(rascals).isNotNull();
      assertThat(rascals.getUnitCount()).isEqualTo(1);
      assertThat(rascals.getSecondaryUnitStats()).isNotNull();
      assertThat(rascals.getSecondaryUnitStats().getName()).isEqualTo("RascalGirl");
      assertThat(rascals.getSecondaryUnitCount()).isEqualTo(2);
      assertThat(rascals.getTotalDeployCount()).isEqualTo(3);
      assertThat(rascals.getFormationOffsets()).hasSize(3);

      // Single-unit card should have no formation offsets or secondary unit
      assertThat(knight.getFormationOffsets()).isNull();
      assertThat(knight.getSecondaryUnitStats()).isNull();
      assertThat(knight.getSecondaryUnitCount()).isEqualTo(0);

      // GoblinHut_Rework: aggro-gated spawner building
      Card goblinHut =
          cards.stream().filter(c -> "goblinhut_rework".equals(c.getId())).findFirst().orElse(null);
      assertThat(goblinHut).isNotNull();
      assertThat(goblinHut.getType()).isEqualTo(CardType.BUILDING);
      assertThat(goblinHut.getCost()).isEqualTo(4);
      assertThat(goblinHut.getRarity()).isEqualTo(Rarity.RARE);
      assertThat(goblinHut.getUnitStats().getLiveSpawn()).isNotNull();
      assertThat(goblinHut.getUnitStats().getLiveSpawn().spawnOnAggro()).isTrue();
      assertThat(goblinHut.getUnitStats().getLiveSpawn().spawnCharacter())
          .isEqualTo("SpearGoblin_Dummy");
      assertThat(goblinHut.getSpawnTemplate()).isNotNull();
      assertThat(goblinHut.getSpawnTemplate().getName()).isEqualTo("SpearGoblin_Dummy");

      // BarbLog: verify full spawn chain resolution
      // BarbLogProjectile -> BarbLogProjectileRolling -> Barbarian
      Card barbLog =
          cards.stream().filter(c -> "barblog".equals(c.getId())).findFirst().orElse(null);
      assertThat(barbLog).isNotNull();
      assertThat(barbLog.getType()).isEqualTo(CardType.SPELL);
      assertThat(barbLog.isSpellAsDeploy()).isTrue();
      assertThat(barbLog.getProjectile()).isNotNull();
      assertThat(barbLog.getProjectile().getName()).isEqualTo("BarbLogProjectile");

      // Stage 2 sub-projectile should be resolved
      ProjectileStats barbLogRolling = barbLog.getProjectile().getSpawnProjectile();
      assertThat(barbLogRolling).isNotNull();
      assertThat(barbLogRolling.getName()).isEqualTo("BarbLogProjectileRolling");
      assertThat(barbLogRolling.getProjectileRange()).isGreaterThan(0);

      // Barbarian spawn character on sub-projectile should be resolved
      assertThat(barbLogRolling.getSpawnCharacterName()).isEqualTo("Barbarian");
      assertThat(barbLogRolling.getSpawnCharacter()).isNotNull();
      assertThat(barbLogRolling.getSpawnCharacter().getName()).isEqualTo("Barbarian");

      // GoblinDrill: tunnel building with tunnelDigUnit, deploy effect, death spawn, live spawn
      Card goblinDrill =
          cards.stream().filter(c -> "goblindrill".equals(c.getId())).findFirst().orElse(null);
      assertThat(goblinDrill).isNotNull();
      assertThat(goblinDrill.getType()).isEqualTo(CardType.BUILDING);
      assertThat(goblinDrill.isCanDeployOnEnemySide()).isTrue();

      // tunnelDigUnit should be GoblinDrillDig (resolved from summonCharacter with morphTarget)
      assertThat(goblinDrill.getTunnelDigUnit()).isNotNull();
      assertThat(goblinDrill.getTunnelDigUnit().getName()).isEqualTo("GoblinDrillDig");
      assertThat(goblinDrill.getTunnelDigUnit().getMorphTarget()).isNotNull();
      assertThat(goblinDrill.getTunnelDigUnit().getMorphTarget().getName())
          .isEqualTo("GoblinDrill");

      // unitStats should be the GoblinDrill building itself
      assertThat(goblinDrill.getUnitStats()).isNotNull();
      assertThat(goblinDrill.getUnitStats().getName()).isEqualTo("GoblinDrill");
      assertThat(goblinDrill.getUnitStats().getLifeTime()).isEqualTo(10.0f);

      // Deploy effect should come from GoblinDrill's spawnAreaEffect (GoblinDrillDamage)
      assertThat(goblinDrill.getDeployEffect()).isNotNull();
      assertThat(goblinDrill.getDeployEffect().getName()).isEqualTo("GoblinDrillDamage");
      assertThat(goblinDrill.getDeployEffect().getPushback()).isGreaterThan(0f);

      // Live spawn: Goblin
      assertThat(goblinDrill.getSpawnTemplate()).isNotNull();
      assertThat(goblinDrill.getSpawnTemplate().getName()).isEqualTo("Goblin");

      // Death spawn: 2 Goblins
      assertThat(goblinDrill.getUnitStats().getDeathSpawns()).isNotEmpty();
      assertThat(goblinDrill.getUnitStats().getDeathSpawns().get(0).stats().getName())
          .isEqualTo("Goblin");
      assertThat(goblinDrill.getUnitStats().getDeathSpawns().get(0).count()).isEqualTo(2);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void graveyardSpawnSequenceLoaded() {
    TroopStats skeleton =
        TroopStats.builder()
            .name("Skeleton")
            .health(32)
            .damage(32)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    String json =
        """
        [
          {
            "id": "graveyard",
            "name": "Graveyard",
            "type": "SPELL",
            "rarity": "Legendary",
            "cost": 5,
            "areaEffect": {
              "name": "Graveyard_rework",
              "radius": 4.0,
              "lifeDuration": 9.0,
              "hitsGround": false,
              "hitsAir": false,
              "spawn": {
                "spawnCharacter": "Skeleton",
                "spawnSequence": [
                  { "spawnDelay": 2.2, "relativeX": 0.0, "relativeY": -3.5 },
                  { "spawnDelay": 2.7, "relativeX": -3.5, "relativeY": 0.0 },
                  { "spawnDelay": 3.3, "relativeX": -2.5, "relativeY": -2.5 }
                ]
              }
            }
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), unitMap(skeleton), Map.of());

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);
    assertThat(card.getId()).isEqualTo("graveyard");
    assertThat(card.getAreaEffect()).isNotNull();

    AreaEffectStats ae = card.getAreaEffect();
    assertThat(ae.getName()).isEqualTo("Graveyard_rework");
    assertThat(ae.getSpawnCharacter()).isNotNull();
    assertThat(ae.getSpawnCharacter().getName()).isEqualTo("Skeleton");

    List<SpawnSequenceEntry> seq = ae.getSpawnSequence();
    assertThat(seq).hasSize(3);
    assertThat(seq.get(0).spawnDelay()).isEqualTo(2.2f);
    assertThat(seq.get(0).relativeX()).isEqualTo(0.0f);
    assertThat(seq.get(0).relativeY()).isEqualTo(-3.5f);
    assertThat(seq.get(1).spawnDelay()).isEqualTo(2.7f);
    assertThat(seq.get(1).relativeX()).isEqualTo(-3.5f);
    assertThat(seq.get(2).spawnDelay()).isEqualTo(3.3f);

    // isDummy should return false even though hitsGround/hitsAir are both false
    assertThat(ae.isDummy()).as("Graveyard should not be a dummy area effect").isFalse();
  }

  @Test
  void loadCards_shouldParseVinesTargetedAreaEffect() {
    String json =
        """
        [
          {
            "id": "vines",
            "name": "Vines",
            "type": "SPELL",
            "rarity": "Epic",
            "cost": 3,
            "areaEffect": {
              "name": "Vines_AeO",
              "radius": 2.5,
              "lifeDuration": 2.0,
              "hitsGround": false,
              "hitsAir": false,
              "buff": "Vines_Trap_Snare_Base",
              "buffDuration": 2.0,
              "targetCount": 3,
              "targetSelectionMode": "HighestCurrentHpIncludeShields",
              "initialDelay": 0.4,
              "targetDelays": [0.0, 0.05, 0.15],
              "airToGround": true,
              "airToGroundDuration": 2.0
            }
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), Map.of(), Map.of());

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);
    assertThat(card.getId()).isEqualTo("vines");
    assertThat(card.getType()).isEqualTo(CardType.SPELL);
    assertThat(card.getRarity()).isEqualTo(Rarity.EPIC);

    AreaEffectStats ae = card.getAreaEffect();
    assertThat(ae).isNotNull();
    assertThat(ae.getName()).isEqualTo("Vines_AeO");
    assertThat(ae.getRadius()).isCloseTo(2.5f, within(0.01f));
    assertThat(ae.getLifeDuration()).isCloseTo(2.0f, within(0.01f));
    assertThat(ae.isHitsGround()).isFalse();
    assertThat(ae.isHitsAir()).isFalse();
    assertThat(ae.getBuff()).isEqualTo("Vines_Trap_Snare_Base");
    assertThat(ae.getBuffDuration()).isCloseTo(2.0f, within(0.01f));

    // Targeted selection fields
    assertThat(ae.getTargetCount()).isEqualTo(3);
    assertThat(ae.getTargetSelectionMode()).isEqualTo("HighestCurrentHpIncludeShields");
    assertThat(ae.getInitialDelay()).isCloseTo(0.4f, within(0.01f));
    assertThat(ae.getTargetDelays()).containsExactly(0.0f, 0.05f, 0.15f);
    assertThat(ae.isAirToGround()).isTrue();
    assertThat(ae.getAirToGroundDuration()).isCloseTo(2.0f, within(0.01f));

    // isDummy should return false because targetCount > 0
    assertThat(ae.isDummy()).as("Vines should not be a dummy area effect").isFalse();
  }

  @Test
  void loadCards_shouldParseDarkMagicDamageTiers() {
    String json =
        """
        [
          {
            "id": "darkmagic",
            "name": "DarkMagic",
            "type": "SPELL",
            "rarity": "Epic",
            "cost": 3,
            "areaEffect": {
              "name": "DarkMagicAOE",
              "radius": 2.5,
              "lifeDuration": 4.0,
              "hitsGround": false,
              "hitsAir": false,
              "onlyEnemies": true,
              "damage": 100,
              "firstHitDelay": 1.0,
              "hitFrequency": 1.0,
              "damageTiers": [
                { "damagePerSecond": 1330, "crownTowerDamagePerHit": 19, "hitFrequency": 0.1, "maxTargets": 1 },
                { "damagePerSecond": 625, "crownTowerDamagePerHit": 10, "hitFrequency": 0.1, "maxTargets": 4 },
                { "damagePerSecond": 297, "crownTowerDamagePerHit": 7, "hitFrequency": 0.1 }
              ]
            }
          }
        ]
        """;

    List<Card> cards = CardLoader.loadCards(toStream(json), Map.of(), Map.of());

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);
    assertThat(card.getId()).isEqualTo("darkmagic");
    assertThat(card.getType()).isEqualTo(CardType.SPELL);
    assertThat(card.getRarity()).isEqualTo(Rarity.EPIC);

    AreaEffectStats ae = card.getAreaEffect();
    assertThat(ae).isNotNull();
    assertThat(ae.getName()).isEqualTo("DarkMagicAOE");
    assertThat(ae.getFirstHitDelay()).isCloseTo(1.0f, within(0.01f));
    assertThat(ae.getScanInterval()).isCloseTo(1.0f, within(0.01f));
    assertThat(ae.getLifeDuration()).isCloseTo(4.0f, within(0.01f));
    assertThat(ae.isHitsGround()).isFalse();
    assertThat(ae.isHitsAir()).isFalse();
    assertThat(ae.isOnlyEnemies()).isTrue();

    // Verify 3 damage tiers
    List<DamageTier> tiers = ae.getDamageTiers();
    assertThat(tiers).hasSize(3);

    assertThat(tiers.get(0).damagePerSecond()).isEqualTo(1330);
    assertThat(tiers.get(0).crownTowerDamagePerHit()).isEqualTo(19);
    assertThat(tiers.get(0).hitFrequency()).isEqualTo(0.1f);
    assertThat(tiers.get(0).maxTargets()).isEqualTo(1);

    assertThat(tiers.get(1).damagePerSecond()).isEqualTo(625);
    assertThat(tiers.get(1).crownTowerDamagePerHit()).isEqualTo(10);
    assertThat(tiers.get(1).hitFrequency()).isEqualTo(0.1f);
    assertThat(tiers.get(1).maxTargets()).isEqualTo(4);

    assertThat(tiers.get(2).damagePerSecond()).isEqualTo(297);
    assertThat(tiers.get(2).crownTowerDamagePerHit()).isEqualTo(7);
    assertThat(tiers.get(2).hitFrequency()).isEqualTo(0.1f);
    assertThat(tiers.get(2).maxTargets()).isEqualTo(0); // catch-all

    // computeTotalLaserScans: firstHitDelay=1.0, scanInterval=1.0, lifeDuration=4.0
    // Scans at t=1.0, t=2.0, t=3.0 -> 3 scans
    assertThat(ae.computeTotalLaserScans()).isEqualTo(3);

    // isDummy should return false because damageTiers is non-empty
    assertThat(ae.isDummy()).as("DarkMagic should not be a dummy area effect").isFalse();
  }
}
