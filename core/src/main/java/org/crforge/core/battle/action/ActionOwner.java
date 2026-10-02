package org.crforge.core.battle.action;

import org.crforge.core.battle.filter.ObjectCensus;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.RarityTable;

/**
 * The entity an action holder belongs to, as the leaf actions that act on their own owner see it:
 * its hit points, and the variables expressions read.
 */
public interface ActionOwner {

  /** The owner's hit points, or null for an owner without them. */
  HitPoints actionHitPoints();

  /** The value a variable holds for the owner; 0 for one never written. */
  int variable(int key);

  /** Writes a variable for the owner, replacing what it held. */
  void setVariable(int key, int value);

  /** True while the owner is alive; an owner without hit points counts as alive. */
  default boolean actionAlive() {
    return HitPoints.alive(actionHitPoints());
  }

  /** The owner's level, packed against its rarity. */
  default int actionPackedLevel() {
    throw new UnsupportedOperationException("this owner has no level");
  }

  /**
   * Changes the owner's level, as a level-changing action does.
   *
   * @param packed the new level, packed
   */
  default void changeLevel(int packed) {
    throw new UnsupportedOperationException("this owner's level cannot change");
  }

  /**
   * The owner's battle's objects as a game object filter asks about them, with the team and name
   * the filter is asked for.
   */
  default ObjectCensus census() {
    throw new UnsupportedOperationException("this owner cannot count the battle's objects");
  }

  /**
   * Swaps the owner's data row for another, as a data-changing action does.
   *
   * @param rowName the name of the new character row
   * @param resetTarget true to give up the target the owner had rather than keep it
   */
  default void changeData(String rowName, boolean resetTarget) {
    throw new UnsupportedOperationException("this owner's data cannot change");
  }

  /**
   * Stores the owner's attack sequence index, as an index-setting action does.
   *
   * @param index the index
   * @param evenIfCombatDisabled true to store it with the targeting component off too
   */
  default void setAttackSequenceIndex(int index, boolean evenIfCombatDisabled) {
    throw new UnsupportedOperationException("this owner has no attack sequence");
  }

  /** The owner's attack sequence index, which its next hit reads. */
  default int attackSequenceIndex() {
    throw new UnsupportedOperationException("this owner has no attack sequence");
  }

  /**
   * Tells the battle's observers what a Berserker's run did to the owner's attack sequence index.
   *
   * @param event the start or a notice
   * @param before the index before
   * @param index the index after
   */
  default void berserked(Berserk.Event event, int before, int index) {}

  /**
   * What a friend-collecting run asks of the battle around the owner.
   *
   * @return the owner's answers
   */
  default FriendCollecting friendCollecting() {
    throw new UnsupportedOperationException("this owner cannot collect friends");
  }

  /**
   * What a Goblin Hut's life state asks of the battle around the owner.
   *
   * @return the owner's answers
   */
  default GoblinHutLife goblinHutLife() {
    throw new UnsupportedOperationException("this owner cannot run a Goblin Hut's life state");
  }

  /** The owner's id in the battle's holder. */
  default int actionId() {
    throw new UnsupportedOperationException("this owner has no id");
  }

  /**
   * The object that made the owner, as an action walking back from its cause follows it: a
   * projectile's launcher, or null for an owner made by nothing, or whose maker has left.
   */
  default ActionOwner actionCreator() {
    return null;
  }

  /** True for an owner another object made, such as a projectile, whose maker it may name. */
  default boolean actionCreated() {
    return false;
  }

  /** The name of the owner's data row. */
  default String actionRowName() {
    throw new UnsupportedOperationException("this owner has no data row");
  }

  /** True for an owner whose row makes children that ride on it. */
  default boolean actionSpawnsAttached() {
    return false;
  }

  /**
   * True for an owner whose attack sequence replaces its row's attack: two or more in its order.
   */
  default boolean actionAttackSequence() {
    return false;
  }

  /** The owner's rarity, which a level is packed against; null for none. */
  default RarityTable actionRarity() {
    throw new UnsupportedOperationException("this owner has no rarity");
  }

  /** Whether an object with the id is still in the owner's battle's live list. */
  default boolean liveObject(int id) {
    throw new UnsupportedOperationException("this owner cannot look up the battle's objects");
  }

  /** True for a king tower, whose heals stop one short of its maximum. */
  default boolean kingTower() {
    return false;
  }

  /**
   * Whether a Clone's perform may clone the owner, as it tests it: no unit a Clone passes by, no
   * clone, a living one, riding nothing. A refusal is told to the battle's observers; a clone the
   * battle does not model is refused outright.
   *
   * @param instigator what caused the clone
   */
  default boolean mayBeCloned(ActionOwner instigator) {
    throw new UnsupportedOperationException("this owner cannot be cloned");
  }

  /**
   * Makes the owner's clone, as a Clone's creator does, and moves the two apart.
   *
   * @param instigator what caused the clone, whose level it takes
   * @param action the Clone's action, whose clone duration the two move apart for
   */
  default void makeClone(ActionOwner instigator, Clone action) {
    throw new UnsupportedOperationException("this owner cannot be cloned");
  }

  /**
   * Puts a buff on the owner, as a buff-spawning action does.
   *
   * @param action the action's name
   * @param buff the buff row's name
   * @param timeMs how long it lasts
   * @param source what applies it, whose level and side it takes
   */
  default void spawnBuff(String action, String buff, int timeMs, ActionOwner source) {
    throw new UnsupportedOperationException("this owner takes no buff from an action");
  }

  /**
   * For an area effect, the object an action's spawn made it from, while that object is in the
   * battle; null for any other owner.
   */
  default ActionOwner areaEffectParent() {
    return null;
  }

  /**
   * Taunts the owner onto an object, as a taunt's perform does: the run, made and armed at once.
   *
   * @param action the taunt
   * @param instigator the area effect that caused it
   * @param forced the object the owner is forced onto, the area effect's parent
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @return the run, or null for an owner that is not a character, which nothing taunts
   */
  default ActionInstance taunt(
      Taunt action, ActionOwner instigator, ActionOwner forced, int phase) {
    return null;
  }

  /**
   * Kills the owner, as a hit of its whole hit points that ignores the battle's holds.
   *
   * @param killer the entity that caused it, or null for none
   */
  void killBy(ActionOwner killer);

  /**
   * Queues a typed hit on the owner, which the battle deals once per tick after the post-hooks.
   *
   * @param source the entity that deals it, or null for none
   * @param amount the amount, before the type's pipeline
   * @param type the hit's damage type
   */
  void queueTypedHit(ActionOwner source, int amount, DamageType type);
}
