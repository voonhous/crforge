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
}
