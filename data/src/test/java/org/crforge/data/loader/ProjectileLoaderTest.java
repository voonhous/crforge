package org.crforge.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.effect.StatusEffectType;
import org.junit.jupiter.api.Test;

class ProjectileLoaderTest {

  private static InputStream toStream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void loadProjectiles_shouldParseBasicProjectile() {
    String json = """
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
    assertThat(arrow.getSpeed()).isCloseTo(600f / 60f, within(0.01f));
    assertThat(arrow.isHoming()).isTrue();
    assertThat(arrow.isAoeToAir()).isFalse();
    assertThat(arrow.isAoeToGround()).isFalse();
  }

  @Test
  void loadProjectiles_shouldParseProjectileWithBuff() {
    String json = """
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
    assertThat(proj.getTargetBuff()).isEqualTo(StatusEffectType.SLOW);
    assertThat(proj.getBuffDuration()).isCloseTo(2.5f, within(0.01f));
    assertThat(proj.getBuffName()).isEqualTo("IceWizardSlowDown");
    assertThat(proj.getRadius()).isCloseTo(1.5f, within(0.01f));
  }

  @Test
  void loadProjectiles_shouldParseChainLightning() {
    String json = """
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
    assertThat(proj.getChainedHitRadius()).isCloseTo(4.0f, within(0.01f));
    assertThat(proj.getChainedHitCount()).isEqualTo(3);
    assertThat(proj.getTargetBuff()).isEqualTo(StatusEffectType.STUN);
  }

  @Test
  void loadProjectiles_shouldResolveSpawnProjectile() {
    String json = """
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
    String json = """
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
    assertThat(proj.getProjectileRange()).isCloseTo(6.5f, within(0.01f));
    assertThat(proj.isHoming()).isFalse();
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
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
