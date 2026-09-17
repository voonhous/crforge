package org.crforge.core.pathfinding.target;

import org.crforge.core.pathfinding.grid.PathfindingGlobals;

/**
 * The attack range and minimum range of a targeting component.
 *
 * <p>Both are distances from the attacker's centre to the target's edge, in integer game units: the
 * geometric test in {@link RangeTest} adds the target's own collision radius before it compares.
 * The order of operations matters and is the one below.
 */
public final class AttackRange {

  /** Attack range against a type-3 reference, which is fought at contact distance. */
  public static final int CONTACT_RANGE = 250;

  /** Entity type of a reference that is fought at {@link #CONTACT_RANGE}. */
  public static final int CONTACT_RANGE_TYPE = 3;

  private AttackRange() {
    // Utility class
  }

  /**
   * The component's current attack range, in the order the rules apply:
   *
   * <ol>
   *   <li>a reference of the contact type answers {@link #CONTACT_RANGE} at once;
   *   <li>a loaded special attack uses SpecialRange, otherwise Range, which the active attack
   *       sequence step may override;
   *   <li>the owner's own collision radius is added under {@link
   *       PathfindingGlobals#ADD_CHARACTER_RANGE_TO_RADIUS};
   *   <li>a unit with an attack sequence and a movement component gives up {@link
   *       PathfindingGlobals#LOGIC_CHARACTER_CONTINUOUS_DAMAGE_ATTACK_CLOSER} of range while it is
   *       moving, so it walks closer before it stops.
   * </ol>
   */
  public static int attackRange(TargetingState t) {
    TargetView reference = t.getReference();
    if (reference != null && reference.getEntity().getType() == CONTACT_RANGE_TYPE) {
      return CONTACT_RANGE;
    }
    TargetingConfig cfg = t.getConfig();
    int range;
    if (t.isSpecialLoadPending()) {
      range = cfg.specialRange();
    } else {
      range = cfg.range();
      AttackSequenceEntry entry = sequenceEntry(t, cfg);
      if (entry != null && entry.rangeOverride() != AttackSequenceEntry.NO_OVERRIDE) {
        range = entry.rangeOverride();
      }
    }
    if (PathfindingGlobals.ADD_CHARACTER_RANGE_TO_RADIUS) {
      range += cfg.collisionRadius();
    }
    if (PathfindingGlobals.LOGIC_CHARACTER_CONTINUOUS_DAMAGE_ATTACK_CLOSER == 0) {
      return range;
    }
    if (!t.isMovementComponentActive()) {
      return range;
    }
    if (cfg.attackSequenceMode() == 0) {
      return range;
    }
    if (t.getOwner().getState() != 1) {
      return range;
    }
    return range - PathfindingGlobals.LOGIC_CHARACTER_CONTINUOUS_DAMAGE_ATTACK_CLOSER;
  }

  /**
   * The component's current minimum range: MinimumRange, which the active attack sequence step may
   * override, plus the owner's own collision radius when the minimum is positive and {@link
   * PathfindingGlobals#ADD_CHARACTER_RANGE_TO_RADIUS} holds.
   */
  public static int minRange(TargetingState t) {
    TargetingConfig cfg = t.getConfig();
    int minimum = cfg.minimumRange();
    AttackSequenceEntry entry = sequenceEntry(t, cfg);
    if (entry != null && entry.minimumRangeOverride() != AttackSequenceEntry.NO_OVERRIDE) {
      minimum = entry.minimumRangeOverride();
    }
    if (minimum >= 1 && PathfindingGlobals.ADD_CHARACTER_RANGE_TO_RADIUS) {
      minimum += cfg.collisionRadius();
    }
    return minimum;
  }

  /**
   * The attack range an entity advertises to the rest of the simulation: the attack range of its
   * targeting component, or zero when the entity has no active one. Route preparation uses it as
   * the radius it stops at, and the flying waypoint rule uses it as the distance it keeps.
   */
  public static int attackRangeWithRadius(TargetingState t) {
    if (t == null || !t.isTargetingComponentActive()) {
      return 0;
    }
    return attackRange(t);
  }

  /**
   * The attack sequence step the component's index selects, or null when no step is active. An
   * index past the end of the sequence is read anyway, which is what the game does.
   */
  static AttackSequenceEntry sequenceEntry(TargetingState t, TargetingConfig cfg) {
    int index = t.getAttackSequenceIndex();
    if (index == TargetingState.NO_SEQUENCE_STEP) {
      return null;
    }
    return cfg.entry(cfg.stepId(index));
  }
}
