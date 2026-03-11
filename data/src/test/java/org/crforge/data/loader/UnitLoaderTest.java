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
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
