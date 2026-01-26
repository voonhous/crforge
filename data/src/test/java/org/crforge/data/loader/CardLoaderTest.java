package org.crforge.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.crfoge.data.loader.CardLoader;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
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
                "speed": 1.0,
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
            "spellDamage": 192,
            "spellRadius": 2.5,
            "spellEffects": [
              {
                "type": "STUN",
                "duration": 0.5
              }
            ]
          }
        ]
        """;

    InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    List<Card> cards = CardLoader.loadCards(is);

    assertThat(cards).hasSize(1);
    Card card = cards.get(0);

    assertThat(card.getId()).isEqualTo("zap");
    assertThat(card.getType()).isEqualTo(CardType.SPELL);
    assertThat(card.getSpellDamage()).isEqualTo(192);
    assertThat(card.getSpellRadius()).isCloseTo(2.5f, within(0.01f));

    assertThat(card.getSpellEffects()).hasSize(1);
    assertThat(card.getSpellEffects().get(0).getType()).isEqualTo(StatusEffectType.STUN);
    assertThat(card.getSpellEffects().get(0).getDuration()).isCloseTo(0.5f, within(0.01f));
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
}
