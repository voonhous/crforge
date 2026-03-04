package org.crforge.core.player;

import java.util.HashMap;
import java.util.Map;
import org.crforge.core.card.Card;

/**
 * Defines card levels for a player. Supports a blanket default level
 * with optional per-card overrides (keyed by card id).
 */
public class LevelConfig {

  /** Level 1 = base stats as stored in cards.json (no scaling applied). */
  public static final int DEFAULT_LEVEL = 1;

  private final int defaultLevel;
  private final Map<String, Integer> cardOverrides;

  public LevelConfig(int defaultLevel) {
    this.defaultLevel = defaultLevel;
    this.cardOverrides = new HashMap<>();
  }

  /** No scaling - stats are used exactly as loaded from cards.json. */
  public static LevelConfig standard() {
    return new LevelConfig(DEFAULT_LEVEL);
  }

  public LevelConfig withCardLevel(String cardId, int level) {
    cardOverrides.put(cardId, level);
    return this;
  }

  public int getLevelFor(Card card) {
    return cardOverrides.getOrDefault(card.getId(), defaultLevel);
  }
}
