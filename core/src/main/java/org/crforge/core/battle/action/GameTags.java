package org.crforge.core.battle.action;

/**
 * The game tags actions set, as bits of the entity's one tag word, the same word the entity flags
 * are bits of. An entity's word is recomputed at its pre-hook from the tags of every action
 * instance it runs, so a tag lasts exactly as long as the action that sets it stays listed.
 */
public final class GameTags {

  /** The entity takes no part in the fight: its targeting component is switched off. */
  public static final long INACTIVE = 1L << 20;

  /** The entity is waking up: its targeting component is switched off until the run ends. */
  public static final long ACTIVATING = 1L << 21;

  /** The tags under which the combat gate keeps an entity's targeting component off. */
  public static final long KEEPS_TARGETING_OFF = INACTIVE | ACTIVATING;

  private GameTags() {
    // Constants
  }
}
