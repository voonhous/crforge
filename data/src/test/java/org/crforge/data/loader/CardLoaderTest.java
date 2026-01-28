package org.crforge.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.crfoge.data.loader.CardLoader;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
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

    // Should have 3 identical stats in the list
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
    // Scenario 1: Unit has damage, Projectile has damage -> Use Projectile damage (e.g. Ice Wizard/Firecracker specific logic)
    // Scenario 2: Unit has damage, Projectile has 0 -> Inherit Unit damage (e.g. Musketeer)
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

    // Firecracker: Projectile damage (100) should override unit damage (50) for the projectile
    Card firecracker = cards.get(0);
    TroopStats fcStats = firecracker.getTroops().get(0);
    assertThat(fcStats.getDamage()).isEqualTo(50); // Unit damage remains
    assertThat(fcStats.getProjectile().getDamage()).isEqualTo(100); // Projectile uses its own

    // Musketeer: Projectile damage (0) should inherit unit damage (200)
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
}
