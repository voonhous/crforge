package org.crforge.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.crforge.core.util.GameUnits.tiles;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.effect.StatusEffectType;
import org.junit.jupiter.api.Test;

class ProjectileLoaderTest {

  private static InputStream toStream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void loadProjectiles_shouldParseBasicProjectile() {
    String json =
        """
        {
          "ArcherArrow": {
            "name": "ArcherArrow",
            "damage": 44,
            "speed": 600,
            "homing": true,
            "aoeToAir": false,
            "aoeToGround": false
          }
        }
        """;

    Map<String, ProjectileStats> map = ProjectileLoader.loadProjectiles(toStream(json));

    assertThat(map).hasSize(1);
    ProjectileStats arrow = map.get("ArcherArrow");
    assertThat(arrow).isNotNull();
    assertThat(arrow.getDamage()).isEqualTo(44);
    // Raw speed 600 = 10 tiles per second = 10,000 game units per second
    assertThat(arrow.getSpeed()).isCloseTo(10000f, within(0.1f));
    assertThat(arrow.isHoming()).isTrue();
    assertThat(arrow.isAoeToAir()).isFalse();
    assertThat(arrow.isAoeToGround()).isFalse();
  }

  @Test
  void loadProjectiles_shouldCarryTheBattleColumns() {
    String json =
        """
        {
          "TowerPrincessProjectile": {
            "name": "TowerPrincessProjectile",
            "damage": 50,
            "speed": 600,
            "homing": true,
            "gravity": 60,
            "rarity": "Common",
            "damageScalingMode": "PrincessTower",
            "homingTime": 100,
            "homingMinDistance": 500
          }
        }
        """;

    ProjectileStats arrow =
        ProjectileLoader.loadProjectiles(toStream(json)).get("TowerPrincessProjectile");

    // The published speed is kept beside the converted one, as the units keep theirs
    assertThat(arrow.getRawSpeed()).isEqualTo(600);
    assertThat(arrow.getGravity()).isEqualTo(60);
    assertThat(arrow.getRarity()).isEqualTo(Rarity.COMMON);
    assertThat(arrow.getDamageScalingMode()).isEqualTo("PrincessTower");
    assertThat(arrow.getHomingTime()).isEqualTo(100);
    assertThat(arrow.getHomingMinDistance()).isEqualTo(500);
  }

  @Test
  void loadProjectiles_shouldLeaveTheBattleColumnsAtTheirDefaults() {
    String json =
        """
        {
          "ArcherArrow": {
            "name": "ArcherArrow",
            "damage": 44,
            "speed": 600
          }
        }
        """;

    ProjectileStats arrow = ProjectileLoader.loadProjectiles(toStream(json)).get("ArcherArrow");

    assertThat(arrow.getRawSpeed()).isEqualTo(600);
    assertThat(arrow.getGravity()).isZero();
    assertThat(arrow.getRarity()).isEqualTo(Rarity.UNKNOWN);
    assertThat(arrow.getDamageScalingMode()).isNull();
    assertThat(arrow.getHomingTime()).isZero();
    assertThat(arrow.getHomingMinDistance()).isZero();
  }

  @Test
  void loadProjectiles_shouldParseProjectileWithBuff() {
    String json =
        """
        {
          "ice_wizardProjectile": {
            "name": "ice_wizardProjectile",
            "damage": 35,
            "speed": 700,
            "homing": true,
            "aoeToAir": true,
            "aoeToGround": true,
            "radius": 1.5,
            "targetBuff": "IceWizardSlowDown",
            "buffDuration": 2.5
          }
        }
        """;

    Map<String, ProjectileStats> map = ProjectileLoader.loadProjectiles(toStream(json));

    ProjectileStats proj = map.get("ice_wizardProjectile");
    // targetBuff is now merged into hitEffects as a post-damage effect
    assertThat(proj.getHitEffects()).hasSize(1);
    EffectStats slowEffect = proj.getHitEffects().get(0);
    assertThat(slowEffect.getType()).isEqualTo(StatusEffectType.SLOW);
    assertThat(slowEffect.getDuration()).isCloseTo(2.5f, within(0.01f));
    assertThat(slowEffect.getBuffName()).isEqualTo("IceWizardSlowDown");
    assertThat(slowEffect.isApplyAfterDamage()).isTrue();
    assertThat(proj.getRadius()).isEqualTo(tiles(1.5));
  }

  @Test
  void loadProjectiles_shouldParseChainLightning() {
    String json =
        """
        {
          "ElectroDragonProjectile": {
            "name": "ElectroDragonProjectile",
            "damage": 75,
            "speed": 2000,
            "homing": true,
            "aoeToAir": false,
            "aoeToGround": false,
            "targetBuff": "ZapFreeze",
            "buffDuration": 0.5,
            "chainedHitRadius": 4.0,
            "chainedHitCount": 3
          }
        }
        """;

    Map<String, ProjectileStats> map = ProjectileLoader.loadProjectiles(toStream(json));

    ProjectileStats proj = map.get("ElectroDragonProjectile");
    assertThat(proj.getChainedHitRadius()).isEqualTo(tiles(4.0));
    assertThat(proj.getChainedHitCount()).isEqualTo(3);
    // targetBuff is now merged into hitEffects
    assertThat(proj.getHitEffects()).hasSize(1);
    assertThat(proj.getHitEffects().get(0).getType()).isEqualTo(StatusEffectType.STUN);
  }

  @Test
  void loadProjectiles_shouldResolveSpawnProjectile() {
    String json =
        """
        {
          "FirecrackerProjectile": {
            "name": "FirecrackerProjectile",
            "damage": 0,
            "speed": 400,
            "homing": false,
            "aoeToAir": true,
            "aoeToGround": true,
            "spawnProjectile": "FirecrackerExplosion"
          },
          "FirecrackerExplosion": {
            "name": "FirecrackerExplosion",
            "damage": 25,
            "speed": 550,
            "homing": false,
            "aoeToAir": true,
            "aoeToGround": true,
            "radius": 0.4,
            "projectileRange": 5.0,
            "spawnCount": 5,
            "spawnRadius": 0.08
          }
        }
        """;

    Map<String, ProjectileStats> map = ProjectileLoader.loadProjectiles(toStream(json));

    ProjectileStats parent = map.get("FirecrackerProjectile");
    assertThat(parent.getSpawnProjectile()).isNotNull();
    assertThat(parent.getSpawnProjectile().getName()).isEqualTo("FirecrackerExplosion");
    assertThat(parent.getSpawnProjectile().getDamage()).isEqualTo(25);
    assertThat(parent.getSpawnCount()).isEqualTo(5);
  }

  @Test
  void loadProjectiles_shouldParseProjectileRange() {
    String json =
        """
        {
          "HunterProjectile": {
            "name": "HunterProjectile",
            "damage": 33,
            "speed": 550,
            "homing": false,
            "aoeToAir": true,
            "aoeToGround": true,
            "projectileRange": 6.5
          }
        }
        """;

    Map<String, ProjectileStats> map = ProjectileLoader.loadProjectiles(toStream(json));

    ProjectileStats proj = map.get("HunterProjectile");
    assertThat(proj.getProjectileRange()).isEqualTo(tiles(6.5));
    assertThat(proj.isHoming()).isFalse();
  }

  @Test
  void loadProjectiles_shouldParsePingpongAsReturning() {
    String json =
        """
        {
          "AxeManProjectile": {
            "name": "AxeManProjectile",
            "damage": 66,
            "speed": 550,
            "homing": false,
            "aoeToAir": true,
            "aoeToGround": true,
            "radius": 1.0,
            "projectileRange": 7.5,
            "pingpong": true
          }
        }
        """;

    Map<String, ProjectileStats> map = ProjectileLoader.loadProjectiles(toStream(json));

    ProjectileStats proj = map.get("AxeManProjectile");
    assertThat(proj).isNotNull();
    assertThat(proj.isReturning()).isTrue();
    assertThat(proj.isHoming()).isFalse();
    assertThat(proj.getProjectileRange()).isEqualTo(tiles(7.5));
  }

  @Test
  void loadProjectiles_shouldLoadAllFromResource() {
    try (InputStream is = getClass().getResourceAsStream("/cards/projectiles.json")) {
      Map<String, ProjectileStats> map = ProjectileLoader.loadProjectiles(is);
      assertThat(map).isNotEmpty();
      assertThat(map.size()).isGreaterThanOrEqualTo(20);

      // Spot check
      assertThat(map.get("ArcherArrow")).isNotNull();
      assertThat(map.get("ArcherArrow").isHoming()).isTrue();

      // AxeManProjectile should have returning=true (from pingpong field)
      ProjectileStats axeMan = map.get("AxeManProjectile");
      assertThat(axeMan).isNotNull();
      assertThat(axeMan.isReturning())
          .as("AxeManProjectile should be a returning projectile")
          .isTrue();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
