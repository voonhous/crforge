package org.crforge.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.junit.jupiter.api.Test;

class UnitLoaderTest {

  private static InputStream toStream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void loadUnits_shouldParseBasicUnit() {
    String json =
        """
        {
          "Knight": {
            "name": "Knight",
            "health": 690,
            "damage": 79,
            "speed": 60.0,
            "mass": 6.0,
            "collisionRadius": 0.5,
            "sightRange": 5.5,
            "range": 1.2,
            "attackCooldown": 1.2,
            "loadTime": 0.7,
            "deployTime": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND"
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), Map.of());

    assertThat(map).hasSize(1);
    TroopStats knight = map.get("Knight");
    assertThat(knight).isNotNull();
    assertThat(knight.getHealth()).isEqualTo(690);
    assertThat(knight.getDamage()).isEqualTo(79);
    assertThat(knight.getSpeed()).isCloseTo(1.0f, within(0.01f)); // 60/60
    assertThat(knight.getMass()).isCloseTo(6.0f, within(0.01f));
    assertThat(knight.getRange()).isCloseTo(1.2f, within(0.01f));
    assertThat(knight.getAttackCooldown()).isCloseTo(1.2f, within(0.01f));
    assertThat(knight.getLoadTime()).isCloseTo(0.7f, within(0.01f));
    assertThat(knight.getTargetType()).isEqualTo(TargetType.GROUND);
    assertThat(knight.getMovementType()).isEqualTo(MovementType.GROUND);
  }

  @Test
  void loadUnits_shouldResolveProjectileReference() {
    ProjectileStats arrow =
        ProjectileStats.builder().name("ArcherArrow").damage(44).speed(10.0f).homing(true).build();

    String json =
        """
        {
          "Archer": {
            "name": "Archer",
            "health": 119,
            "damage": 44,
            "speed": 60.0,
            "mass": 3.0,
            "sightRange": 5.5,
            "range": 5.0,
            "attackCooldown": 0.9,
            "loadTime": 0.4,
            "deployTime": 1.0,
            "targetType": "ALL",
            "movementType": "GROUND",
            "projectile": "ArcherArrow"
          }
        }
        """;

    Map<String, ProjectileStats> projMap = Map.of("ArcherArrow", arrow);
    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), projMap);

    TroopStats archer = map.get("Archer");
    assertThat(archer.getProjectile()).isNotNull();
    assertThat(archer.getProjectile().getName()).isEqualTo("ArcherArrow");
  }

  @Test
  void loadUnits_shouldResolveDeathSpawns() {
    String json =
        """
        {
          "Golem": {
            "name": "Golem",
            "health": 3200,
            "damage": 120,
            "speed": 45.0,
            "mass": 18.0,
            "sightRange": 7.5,
            "range": 1.2,
            "attackCooldown": 2.5,
            "deployTime": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND",
            "targetOnlyBuildings": true,
            "deathDamage": { "damage": 120, "radius": 2.0 },
            "deathSpawn": [
              { "spawnCharacter": "Golemite", "spawnNumber": 2, "spawnRadius": 0.7 }
            ]
          },
          "Golemite": {
            "name": "Golemite",
            "health": 640,
            "damage": 30,
            "speed": 45.0,
            "mass": 6.0,
            "sightRange": 7.5,
            "range": 1.2,
            "attackCooldown": 2.5,
            "deployTime": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND",
            "targetOnlyBuildings": true,
            "deathDamage": { "damage": 30, "radius": 1.5 }
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), Map.of());

    TroopStats golem = map.get("Golem");
    assertThat(golem.getDeathDamage()).isEqualTo(120);
    assertThat(golem.getDeathDamageRadius()).isCloseTo(2.0f, within(0.01f));
    assertThat(golem.getDeathSpawns()).hasSize(1);
    assertThat(golem.getDeathSpawns().get(0).stats().getName()).isEqualTo("Golemite");
    assertThat(golem.getDeathSpawns().get(0).count()).isEqualTo(2);
  }

  @Test
  void loadUnits_shouldParseLiveSpawn() {
    String json =
        """
        {
          "Witch": {
            "name": "Witch",
            "health": 328,
            "damage": 53,
            "speed": 60.0,
            "mass": 8.0,
            "sightRange": 5.5,
            "range": 5.5,
            "attackCooldown": 1.1,
            "loadTime": 0.4,
            "deployTime": 1.0,
            "targetType": "ALL",
            "movementType": "GROUND",
            "liveSpawn": {
              "spawnCharacter": "Skeleton",
              "spawnNumber": 4,
              "spawnPauseTime": 7.0,
              "spawnStartTime": 1.0,
              "spawnRadius": 2.0
            }
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), Map.of());

    TroopStats witch = map.get("Witch");
    assertThat(witch.getLiveSpawn()).isNotNull();
    assertThat(witch.getLiveSpawn().spawnCharacter()).isEqualTo("Skeleton");
    assertThat(witch.getLiveSpawn().spawnNumber()).isEqualTo(4);
    assertThat(witch.getLiveSpawn().spawnPauseTime()).isCloseTo(7.0f, within(0.01f));
    assertThat(witch.getLiveSpawn().spawnStartTime()).isCloseTo(1.0f, within(0.01f));
    assertThat(witch.getLiveSpawn().spawnRadius()).isCloseTo(2.0f, within(0.01f));
  }

  @Test
  void loadUnits_shouldParseLifeTime() {
    String json =
        """
        {
          "Cannon": {
            "name": "Cannon",
            "health": 322,
            "damage": 83,
            "speed": 0.0,
            "mass": 0.0,
            "collisionRadius": 0.6,
            "sightRange": 5.5,
            "range": 5.5,
            "attackCooldown": 1.0,
            "loadTime": 0.1,
            "deployTime": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND",
            "visualRadius": 1.5,
            "lifeTime": 30.0
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), Map.of());

    TroopStats cannon = map.get("Cannon");
    assertThat(cannon.getLifeTime()).isCloseTo(30.0f, within(0.01f));
  }

  @Test
  void loadUnits_shouldParseTargetingModifiers() {
    String json =
        """
        {
          "Giant": {
            "name": "Giant",
            "health": 1550,
            "damage": 99,
            "speed": 45.0,
            "mass": 18.0,
            "sightRange": 7.5,
            "range": 1.2,
            "attackCooldown": 1.5,
            "deployTime": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND",
            "targetOnlyBuildings": true,
            "ignorePushback": true
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), Map.of());

    TroopStats giant = map.get("Giant");
    assertThat(giant.isTargetOnlyBuildings()).isTrue();
    assertThat(giant.isIgnorePushback()).isTrue();
  }

  @Test
  void loadUnits_shouldParseAreaDamageRadius() {
    String json =
        """
        {
          "Valkyrie": {
            "name": "Valkyrie",
            "health": 880,
            "damage": 120,
            "speed": 60.0,
            "mass": 8.0,
            "sightRange": 5.5,
            "range": 1.2,
            "attackCooldown": 1.5,
            "loadTime": 0.4,
            "deployTime": 1.0,
            "areaDamageRadius": 1.3,
            "targetType": "GROUND",
            "movementType": "GROUND"
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), Map.of());

    TroopStats valkyrie = map.get("Valkyrie");
    assertThat(valkyrie).isNotNull();
    assertThat(valkyrie.getAoeRadius()).isCloseTo(1.3f, within(0.01f));
  }

  @Test
  void loadUnits_shouldResolveChainedDeathSpawns() {
    // Depth-2 chain: A -> B -> C. A appears before B in JSON order,
    // so a single resolution pass would give A a reference to B that still has empty deathSpawns.
    String json =
        """
        {
          "UnitC": {
            "name": "UnitC",
            "health": 50,
            "damage": 10,
            "speed": 60.0,
            "mass": 1.0,
            "range": 1.0,
            "attackCooldown": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND"
          },
          "UnitA": {
            "name": "UnitA",
            "health": 500,
            "damage": 50,
            "speed": 60.0,
            "mass": 6.0,
            "range": 1.0,
            "attackCooldown": 1.5,
            "targetType": "ALL",
            "movementType": "AIR",
            "deathSpawn": [
              { "spawnCharacter": "UnitB", "spawnNumber": 1, "spawnRadius": 0.5 }
            ]
          },
          "UnitB": {
            "name": "UnitB",
            "health": 100,
            "damage": 20,
            "speed": 60.0,
            "mass": 2.0,
            "range": 1.0,
            "attackCooldown": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND",
            "deathSpawn": [
              { "spawnCharacter": "UnitC", "spawnNumber": 3, "spawnRadius": 0.3 }
            ]
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), Map.of());

    // UnitB -> UnitC (depth-1, always works)
    TroopStats unitB = map.get("UnitB");
    assertThat(unitB.getDeathSpawns()).hasSize(1);
    assertThat(unitB.getDeathSpawns().get(0).stats().getName()).isEqualTo("UnitC");

    // UnitA -> UnitB (depth-2 chain): UnitA's death spawn reference to UnitB
    // must have UnitB's own deathSpawns populated (not the pass-1 empty version)
    TroopStats unitA = map.get("UnitA");
    assertThat(unitA.getDeathSpawns()).hasSize(1);
    TroopStats unitBFromA = unitA.getDeathSpawns().get(0).stats();
    assertThat(unitBFromA.getName()).isEqualTo("UnitB");
    assertThat(unitBFromA.getDeathSpawns())
        .as("UnitA's reference to UnitB must have UnitB's deathSpawns resolved")
        .hasSize(1);
    assertThat(unitBFromA.getDeathSpawns().get(0).stats().getName()).isEqualTo("UnitC");
    assertThat(unitBFromA.getDeathSpawns().get(0).count()).isEqualTo(3);
  }

  @Test
  void loadUnits_shouldResolveDepth3ChainedDeathSpawns() {
    // Depth-3 chain: A -> B -> C -> D. This would fail with the old hardcoded
    // two-pass approach since it only handled up to depth-2.
    String json =
        """
        {
          "UnitA": {
            "name": "UnitA",
            "health": 1000,
            "damage": 100,
            "speed": 60.0,
            "mass": 10.0,
            "range": 1.0,
            "attackCooldown": 1.5,
            "targetType": "ALL",
            "movementType": "AIR",
            "deathSpawn": [
              { "spawnCharacter": "UnitB", "spawnNumber": 1, "spawnRadius": 0.5 }
            ]
          },
          "UnitB": {
            "name": "UnitB",
            "health": 500,
            "damage": 50,
            "speed": 60.0,
            "mass": 5.0,
            "range": 1.0,
            "attackCooldown": 1.0,
            "targetType": "ALL",
            "movementType": "AIR",
            "deathSpawn": [
              { "spawnCharacter": "UnitC", "spawnNumber": 2, "spawnRadius": 0.4 }
            ]
          },
          "UnitC": {
            "name": "UnitC",
            "health": 200,
            "damage": 20,
            "speed": 60.0,
            "mass": 3.0,
            "range": 1.0,
            "attackCooldown": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND",
            "deathSpawn": [
              { "spawnCharacter": "UnitD", "spawnNumber": 4, "spawnRadius": 0.3 }
            ]
          },
          "UnitD": {
            "name": "UnitD",
            "health": 50,
            "damage": 10,
            "speed": 60.0,
            "mass": 1.0,
            "range": 1.0,
            "attackCooldown": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND"
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), Map.of());

    // Depth 1: UnitC -> UnitD
    TroopStats unitC = map.get("UnitC");
    assertThat(unitC.getDeathSpawns()).hasSize(1);
    assertThat(unitC.getDeathSpawns().get(0).stats().getName()).isEqualTo("UnitD");
    assertThat(unitC.getDeathSpawns().get(0).count()).isEqualTo(4);

    // Depth 2: UnitB -> UnitC (with UnitC's deathSpawns resolved)
    TroopStats unitB = map.get("UnitB");
    assertThat(unitB.getDeathSpawns()).hasSize(1);
    TroopStats unitCFromB = unitB.getDeathSpawns().get(0).stats();
    assertThat(unitCFromB.getName()).isEqualTo("UnitC");
    assertThat(unitCFromB.getDeathSpawns()).hasSize(1);
    assertThat(unitCFromB.getDeathSpawns().get(0).stats().getName()).isEqualTo("UnitD");

    // Depth 3: UnitA -> UnitB -> UnitC -> UnitD (full chain resolved)
    TroopStats unitA = map.get("UnitA");
    assertThat(unitA.getDeathSpawns()).hasSize(1);
    TroopStats unitBFromA = unitA.getDeathSpawns().get(0).stats();
    assertThat(unitBFromA.getName()).isEqualTo("UnitB");
    assertThat(unitBFromA.getDeathSpawns()).hasSize(1);
    TroopStats unitCFromA = unitBFromA.getDeathSpawns().get(0).stats();
    assertThat(unitCFromA.getName()).isEqualTo("UnitC");
    assertThat(unitCFromA.getDeathSpawns()).hasSize(1);
    assertThat(unitCFromA.getDeathSpawns().get(0).stats().getName()).isEqualTo("UnitD");
  }

  @Test
  void loadUnits_shouldResolvePhoenixDeathSpawnProjectileChain() {
    // Phoenix -> deathSpawnProjectile "PhoenixFireball" -> spawn PhoenixEgg -> liveSpawn
    // PhoenixNoRespawn

    // Build the projectile map with PhoenixFireball containing spawn.spawnCharacter = PhoenixEgg
    ProjectileStats phoenixFireball =
        ProjectileStats.builder()
            .name("PhoenixFireball")
            .damage(64)
            .speed(10.0f)
            .radius(2.5f)
            .homing(false)
            .pushback(2.0f)
            .spawnCharacterName("PhoenixEgg")
            .spawnCharacterCount(1)
            .build();
    Map<String, ProjectileStats> projectileMap = Map.of("PhoenixFireball", phoenixFireball);

    String json =
        """
        {
          "Phoenix": {
            "name": "Phoenix",
            "health": 411,
            "damage": 85,
            "speed": 60.0,
            "mass": 2.0,
            "range": 1.6,
            "attackCooldown": 1.0,
            "loadTime": 0.4,
            "targetType": "ALL",
            "movementType": "AIR",
            "deathSpawnProjectile": "PhoenixFireball"
          },
          "PhoenixEgg": {
            "name": "PhoenixEgg",
            "health": 94,
            "damage": 0,
            "speed": 1.0,
            "mass": 3.0,
            "range": 5.5,
            "attackCooldown": 1.0,
            "targetType": "GROUND",
            "movementType": "GROUND",
            "liveSpawn": {
              "spawnCharacter": "PhoenixNoRespawn",
              "spawnNumber": 1,
              "spawnPauseTime": 4.3,
              "spawnStartTime": 4.3,
              "spawnLimit": 1,
              "destroyAtLimit": true
            }
          },
          "PhoenixNoRespawn": {
            "name": "PhoenixNoRespawn",
            "health": 411,
            "damage": 85,
            "speed": 60.0,
            "mass": 2.0,
            "range": 1.6,
            "attackCooldown": 1.0,
            "loadTime": 0.5,
            "deployTime": 0.733,
            "targetType": "ALL",
            "movementType": "AIR"
          }
        }
        """;

    Map<String, TroopStats> map = UnitLoader.loadUnits(toStream(json), projectileMap);

    // Phoenix should have deathSpawnProjectile resolved
    TroopStats phoenix = map.get("Phoenix");
    assertThat(phoenix).isNotNull();
    assertThat(phoenix.getDeathSpawnProjectile()).isNotNull();
    assertThat(phoenix.getDeathSpawnProjectile().getName()).isEqualTo("PhoenixFireball");
    assertThat(phoenix.getDeathSpawnProjectile().getDamage()).isEqualTo(64);

    // PhoenixFireball's spawnCharacter should be resolved to PhoenixEgg TroopStats
    assertThat(phoenix.getDeathSpawnProjectile().getSpawnCharacter()).isNotNull();
    assertThat(phoenix.getDeathSpawnProjectile().getSpawnCharacter().getName())
        .isEqualTo("PhoenixEgg");
    assertThat(phoenix.getDeathSpawnProjectile().getSpawnCharacter().getHealth()).isEqualTo(94);

    // PhoenixEgg should have liveSpawn with spawnLimit and destroyAtLimit
    TroopStats egg = map.get("PhoenixEgg");
    assertThat(egg).isNotNull();
    assertThat(egg.getLiveSpawn()).isNotNull();
    assertThat(egg.getLiveSpawn().spawnCharacter()).isEqualTo("PhoenixNoRespawn");
    assertThat(egg.getLiveSpawn().spawnLimit()).isEqualTo(1);
    assertThat(egg.getLiveSpawn().destroyAtLimit()).isTrue();

    // PhoenixEgg should have spawnTemplate resolved to PhoenixNoRespawn
    assertThat(egg.getSpawnTemplate()).isNotNull();
    assertThat(egg.getSpawnTemplate().getName()).isEqualTo("PhoenixNoRespawn");
    assertThat(egg.getSpawnTemplate().getHealth()).isEqualTo(411);

    // PhoenixNoRespawn should have no death mechanics
    TroopStats noRespawn = map.get("PhoenixNoRespawn");
    assertThat(noRespawn).isNotNull();
    assertThat(noRespawn.getDeathSpawnProjectile()).isNull();
    assertThat(noRespawn.getLiveSpawn()).isNull();
    assertThat(noRespawn.getDeathSpawns()).isEmpty();
  }

  @Test
  void loadUnits_shouldLoadAllFromResource() {
    // Load projectiles first, then units
    Map<String, ProjectileStats> projMap;
    try (InputStream pis = getClass().getResourceAsStream("/cards/projectiles.json")) {
      projMap = ProjectileLoader.loadProjectiles(pis);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    try (InputStream uis = getClass().getResourceAsStream("/cards/units.json")) {
      Map<String, TroopStats> map = UnitLoader.loadUnits(uis, projMap);
      assertThat(map).isNotEmpty();
      assertThat(map.size()).isGreaterThanOrEqualTo(50);

      // Spot checks
      TroopStats knight = map.get("Knight");
      assertThat(knight).isNotNull();
      assertThat(knight.getSpeed()).isCloseTo(1.0f, within(0.01f));

      TroopStats witch = map.get("Witch");
      assertThat(witch).isNotNull();
      assertThat(witch.getLiveSpawn()).isNotNull();

      // Verify splash troops have areaDamageRadius loaded correctly
      TroopStats valkyrie = map.get("Valkyrie");
      assertThat(valkyrie).isNotNull();
      assertThat(valkyrie.getAoeRadius()).isGreaterThan(0f);

      // Verify chained death spawn: SkeletonBalloon -> SkeletonContainer -> Skeleton
      TroopStats skelBalloon = map.get("SkeletonBalloon");
      assertThat(skelBalloon).isNotNull();
      assertThat(skelBalloon.getDeathSpawns()).hasSize(1);
      TroopStats skelContainer = skelBalloon.getDeathSpawns().get(0).stats();
      assertThat(skelContainer.getName()).isEqualTo("SkeletonContainer");
      assertThat(skelContainer.getDeathSpawns())
          .as("SkeletonContainer (from SkeletonBalloon) must spawn Skeletons")
          .isNotEmpty();
      assertThat(skelContainer.getDeathSpawns().get(0).stats().getName()).isEqualTo("Skeleton");

      // Verify Ram's liveSpawn has spawnAttach=true
      TroopStats ram = map.get("Ram");
      assertThat(ram).isNotNull();
      assertThat(ram.getLiveSpawn()).isNotNull();
      assertThat(ram.getLiveSpawn().spawnAttach()).isTrue();
      assertThat(ram.getLiveSpawn().spawnCharacter()).isEqualTo("RamRider");

      // Verify RamRider has targetOnlyTroops and ignoreTargetsWithBuff
      TroopStats ramRider = map.get("RamRider");
      assertThat(ramRider).isNotNull();
      assertThat(ramRider.isTargetOnlyTroops()).isTrue();
      assertThat(ramRider.getIgnoreTargetsWithBuff()).isEqualTo("BolaSnare");

      // Verify GoblinGiant's liveSpawn has spawnAttach=true
      TroopStats goblinGiant = map.get("GoblinGiant");
      assertThat(goblinGiant).isNotNull();
      assertThat(goblinGiant.getLiveSpawn()).isNotNull();
      assertThat(goblinGiant.getLiveSpawn().spawnAttach()).isTrue();

      // Verify Phoenix chain resolution from real data
      TroopStats phoenix = map.get("Phoenix");
      assertThat(phoenix).isNotNull();
      assertThat(phoenix.getDeathSpawnProjectile()).isNotNull();
      assertThat(phoenix.getDeathSpawnProjectile().getName()).isEqualTo("PhoenixFireball");
      assertThat(phoenix.getDeathSpawnProjectile().getSpawnCharacter()).isNotNull();
      assertThat(phoenix.getDeathSpawnProjectile().getSpawnCharacter().getName())
          .isEqualTo("PhoenixEgg");

      TroopStats phoenixEgg = map.get("PhoenixEgg");
      assertThat(phoenixEgg).isNotNull();
      assertThat(phoenixEgg.getLiveSpawn()).isNotNull();
      assertThat(phoenixEgg.getLiveSpawn().spawnLimit()).isEqualTo(1);
      assertThat(phoenixEgg.getLiveSpawn().destroyAtLimit()).isTrue();
      assertThat(phoenixEgg.getSpawnTemplate()).isNotNull();
      assertThat(phoenixEgg.getSpawnTemplate().getName()).isEqualTo("PhoenixNoRespawn");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
