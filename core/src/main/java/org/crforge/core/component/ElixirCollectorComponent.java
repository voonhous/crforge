package org.crforge.core.component;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Component for buildings that passively generate elixir for their owner (e.g. Elixir Collector).
 * Tracks the collection timer, hold state (when owner is at max elixir), and death refund amount.
 */
@Getter
@Setter
@Builder
public class ElixirCollectorComponent {

  // Configuration (immutable after construction)
  private final int manaCollectAmount; // elixir per cycle (e.g. 1)
  private final float manaGenerateTime; // seconds between cycles (e.g. 13.0)
  private final int manaOnDeath; // elixir refunded to owner on death (e.g. 1)

  // Runtime state
  @Builder.Default private float collectionTimer = 0f;
  @Builder.Default private boolean holdingElixir = false;
}
