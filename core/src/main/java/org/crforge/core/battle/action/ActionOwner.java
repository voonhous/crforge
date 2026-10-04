package org.crforge.core.battle.action;

import java.util.List;
import java.util.function.IntSupplier;
import org.crforge.core.battle.filter.GameObjectFilter;
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

  /** What {@link #actionUnitGlobalId} answers for an owner of no character or building row. */
  int NO_UNIT_ROW = -1;

  /**
   * The global id of the owner's character or building row, which a check of what caused an action
   * compares; {@link #NO_UNIT_ROW} for an owner of another kind, such as a projectile.
   */
  default int actionUnitGlobalId() {
    return NO_UNIT_ROW;
  }

  /**
   * Tells the battle's observers what a check of an action's cause found.
   *
   * @param action the checking action's row
   * @param instigator what caused it, or null for nothing
   * @param scheduled the action it scheduled on the owner, or null for none
   */
  default void instigatorChecked(String action, ActionOwner instigator, String scheduled) {}

  /**
   * What a friend-collecting run asks of the battle around the owner.
   *
   * @return the owner's answers
   */
  default FriendCollecting friendCollecting() {
    throw new UnsupportedOperationException("this owner cannot collect friends");
  }

  /**
   * What a Royal Chef's cooking run asks of the battle around the owner. Only a king tower runs
   * one.
   *
   * @return the owner's answers
   */
  default CookingHost cookingHost() {
    throw new UnsupportedOperationException(
        "a Royal Chef's cooking on an owner other than a king tower is not modelled");
  }

  /**
   * The candidates a snipe's look lists around the owner: the objects in the box about the owner's
   * position, as wide as twice the half width and as long as twice the half length, that pass the
   * filter for the owner's team and row, a building by its square overlapping the box and anything
   * else by its circle meeting it; less those standing closer than the minimum range, which keeps
   * an object only while its squared distance from the owner, less its collision radius squared and
   * never below 0, is at least the square of the minimum range plus the owner's collision radius.
   * Only a character runs a snipe.
   *
   * @param halfWidth half the box's width
   * @param halfLength half the box's length
   * @param minimumRange the minimum range, the owner's collision radius added
   * @param filter the filter
   * @return the candidates' ids, nearest first, objects at the same distance in the box's order
   */
  default List<Integer> snipeCandidates(
      int halfWidth, int halfLength, int minimumRange, GameObjectFilter filter) {
    throw new UnsupportedOperationException(
        "a snipe on an owner other than a character is not modelled");
  }

  /**
   * What a shape selector's run asks of the battle around the owner. Only an area effect and a
   * character run one.
   *
   * @return the owner's answers
   */
  default ShapeSelectorHost shapeSelectorHost() {
    throw new UnsupportedOperationException(
        "a shape selector on an owner other than an area effect or a character is not modelled");
  }

  /**
   * Empties the owner's route for a path reset row. Only a character or a building owner is
   * modelled: a character's route is emptied and a building, which does not move, is left alone.
   *
   * @param action the row
   */
  default void resetPath(ResetPath action) {
    throw new UnsupportedOperationException(
        action.name() + " resets the path of an owner that is neither a character nor a building");
  }

  /**
   * Starts an air-to-ground run on the owner, which only a character or a tower takes.
   *
   * @param action the row
   * @param phase the pending pass it starts in
   * @return the run
   */
  default ActionInstance airToGround(AirToGround action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " holds an owner that is neither a character nor a tower, not modelled");
  }

  /**
   * What a laser ball's run asks of the battle around the owner. Only an area effect runs one.
   *
   * @return the owner's answers
   */
  default LaserBallHost laserBallHost() {
    throw new UnsupportedOperationException(
        "a laser ball on an owner other than an area effect is not modelled");
  }

  /**
   * What a guard-spawning run asks of the battle around the owner. Only an area effect runs one.
   *
   * @param action the row
   * @return the owner's answers
   */
  default SpawnGuard.Maker guardMaker(SpawnGuard action) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than an area effect, not modelled");
  }

  /**
   * What a Boss Bandit ability's run asks of the battle about the owner. Only a character runs one.
   *
   * @param action the row
   * @return the owner's answers
   */
  default BossBanditAbility.Host bossBanditHost(BossBanditAbility action) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * What a mark's run, or its hand-over's, asks of the battle about the owner. Only a character
   * runs one.
   *
   * @param action the row
   * @return the owner's answers
   */
  default SetIndicatorOnTarget.Host markHost(BattleAction action) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Warps the owner, as a warp's perform does. Only a character is warped.
   *
   * @param action the row
   * @param phase the pending pass it starts in
   */
  default void warp(WarpCharacter action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * What the evolved Royal Ghost's run asks of the battle about this owner. Only a character runs
   * one.
   *
   * @param action the row
   */
  default GhostEvo.Host ghostEvoHost(GhostEvo action) {
    throw new UnsupportedOperationException(
        action.name() + " runs on " + actionRowName() + ", which is not a character, not modelled");
  }

  /**
   * What the evolved Rage Barbarian's ghost wait asks of the battle about this owner. Only a
   * character runs one.
   *
   * @param action the row
   */
  default LumberjackGhostWait.Host lumberjackGhostHost(LumberjackGhostWait action) {
    throw new UnsupportedOperationException(
        action.name() + " runs on " + actionRowName() + ", which is not a character, not modelled");
  }

  /**
   * What a charge counter's run reads of and does to this owner. Only an entity with a targeting
   * component and an attack sequence runs one.
   *
   * @param action the row
   */
  default BurstAttack.Host burstAttackHost(BurstAttack action) {
    throw new UnsupportedOperationException(
        action.name() + " runs on " + actionRowName() + ", which does not attack, not modelled");
  }

  /** What a summon area's run asks of the battle about this owner. Only an area effect runs one. */
  default GhostEvo.SummonHost ghostSummonHost() {
    throw new UnsupportedOperationException(
        "a summon run on " + actionRowName() + ", which is not an area effect, is not modelled");
  }

  /**
   * What a target indicator attack's run asks of the battle around the owner. Only a character runs
   * one.
   *
   * @return the owner's answers
   */
  default TargetIndicatorHost targetIndicatorHost() {
    throw new UnsupportedOperationException(
        "a target indicator attack on an owner other than a character is not modelled");
  }

  /**
   * What a chain projectile attack's run asks of the battle around the owner. Only a character runs
   * one.
   *
   * @return the owner's answers
   */
  default ChainAttackHost chainAttackHost() {
    throw new UnsupportedOperationException(
        "a chain projectile attack on an owner other than a character is not modelled");
  }

  /**
   * What a net attack's run asks of the battle around the owner. Only a character runs one.
   *
   * @return the owner's answers
   */
  default NetAttackHost netAttackHost() {
    throw new UnsupportedOperationException(
        "a net attack on an owner other than a character is not modelled");
  }

  /**
   * Makes the run of Goblinstein's ability action on the owner. Only an area effect that follows
   * its parent runs one.
   *
   * @param action the row
   * @param phase the pending pass it starts in
   * @return the run
   */
  default ActionInstance goblinsteinAbility(GoblinsteinAbility action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than an area effect that follows, not modelled");
  }

  /**
   * Makes a run of the champion ability controller's row on the owner. Only a king, which makes its
   * two slots as it starts, runs one.
   *
   * @param action the row
   * @return the run
   */
  default ActionInstance championAbility(ChampionAbility action) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a king, not modelled");
  }

  /**
   * Writes a button state override, and refills the charges when it asks, into the champion slot of
   * the owner's player that follows the row's champion; nothing when no slot follows it. Only a
   * character or a building, which is its own player's, answers it; an owner that hands the
   * question to another object is not modelled.
   *
   * @param action the row
   */
  default void overrideAbilityButton(OverrideAbilityButtonState action) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * The button state override's first write, as the row is performed: the same as every later write
   * of its run for an owner that is its own player's, a character or a building. A projectile hands
   * the question to the object that launched it, and writes nothing unless that is a character or a
   * building; the run's later writes go by the projectile's own side.
   *
   * @param action the row
   */
  default void performAbilityButtonOverride(OverrideAbilityButtonState action) {
    overrideAbilityButton(action);
  }

  /**
   * The owner's group chain, for the actions that check or run over its group. Only a character
   * keeps one.
   */
  default GroupChain groupChain() {
    throw new UnsupportedOperationException(
        "a group check on an owner other than a character is not modelled");
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
   * Starts the evolved Mega Knight's uppercut on the owner, as the unit attacks.
   *
   * @param action the uppercut
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @param instigator what caused it, the target of the hit, or null for none
   * @return the run
   */
  default ActionInstance uppercut(MegaKnightUppercut action, int phase, ActionOwner instigator) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Starts a push of the owner away from the object that caused it.
   *
   * @param action the push
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @param instigator what caused it
   * @return the run
   */
  default ActionInstance pushbackFromInstigator(
      DoPushbackFromInstigator action, int phase, ActionOwner instigator) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Knocks the owner into the air.
   *
   * @param action the knock
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @param instigator what caused it, or null for none
   * @return the run
   */
  default ActionInstance knockback(Knockback action, int phase, ActionOwner instigator) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Starts the evolved Goblin Drill's relocation on the owner.
   *
   * @param action the relocation
   * @param holder the owner's holder, which schedules the first-appear action
   * @return the run
   */
  default ActionInstance goblinDrillRelocate(GoblinDrillEvoRelocate action, ActionHolder holder) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Starts the evolved Dart Goblin's dart choice on the owner. Only a character runs one.
   *
   * @param action the row
   * @param instigator the holder of the entity that caused it, or null for none
   * @return the run
   */
  default ActionInstance blowdartDartSelect(BlowdartDartSelect action, ActionHolder instigator) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Starts the evolved Dart Goblin's poison controller on the owner, the object its darts hit.
   *
   * @param action the row
   * @param instigator the holder of the entity that caused it, or null for none
   * @return the run
   */
  default ActionInstance blowdartController(BlowdartController action, ActionHolder instigator) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner that is not a unit, building or tower, not modelled");
  }

  /**
   * Starts the evolved Dart Goblin's poison damage on the owner, the object a poison area reached.
   *
   * @param action the row
   * @param instigator the holder of the entity that caused it, or null for none
   * @return the run
   */
  default ActionInstance blowdartDamage(BlowdartDamage action, ActionHolder instigator) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner that is not a unit, building or tower, not modelled");
  }

  /**
   * Starts a push the owner carries ahead of itself, as the evolved Battle Ram's completed charge
   * runs it.
   *
   * @param action the push
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @return the run
   */
  default ActionInstance damagingPushBack(DamagingPushBack action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Starts the evolved Executioner's axe controller on the owner, a run that listens to its hits.
   *
   * @param action the controller
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @return the run
   */
  default ActionInstance executionerController(ExecutionerEvoProjectile action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a projectile, not modelled");
  }

  /**
   * Swaps the owner's projectile row for another, as a data-changing action gives it.
   *
   * @param rowName the projectile row it takes
   */
  default void changeProjectileData(String rowName) {
    throw new UnsupportedOperationException(
        "a projectile row's swap on an owner other than a projectile, not modelled");
  }

  /**
   * Throws a mirrored extra spell from the owner as its cause, as the action's perform reads it.
   * Only a projectile is a cause the perform reads; any other is refused.
   *
   * @param action the mirrored extra spell, which names the projectile row it throws
   */
  default void mirroredExtraSpell(MirroredExtraSpell action) {
    throw new UnsupportedOperationException(
        action.name() + " mirrors a cause other than a projectile, which is not modelled");
  }

  /**
   * Starts a rolling run on the owner, which moves it in place of its flight.
   *
   * @param action the roll
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @return the run
   */
  default ActionInstance rollingProjectile(RollingProjectile action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a projectile, not modelled");
  }

  /**
   * Starts a capture run on the owner, which captures the enemies around it and carries them.
   *
   * @param action the capture
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @return the run
   */
  default ActionInstance captureCharacter(CaptureCharacter action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a projectile or a character, not modelled");
  }

  /**
   * Tells the owner's battle a run that waited for its cause to leave schedules its action. By
   * default nothing.
   *
   * @param action the waiting row
   * @param scheduled the action it schedules on the owner
   */
  default void instigatorGone(RunActionOnInstigatorDeath action, BattleAction scheduled) {}

  /**
   * What a run listening for destroyed objects needs from the object it runs on: the battle's death
   * notices, and the side, team and row a destroyed object is tested against.
   *
   * @param action the action the run is of
   */
  default RunActionOnTroopDestroyed.Host troopDestroyedHost(RunActionOnTroopDestroyed action) {
    throw new UnsupportedOperationException(
        action.name() + " listens for destroyed objects on an object that is not modelled for it");
  }

  /**
   * The battle tick a run that times itself by the battle clock reads, for the object it runs on.
   *
   * @param action the action the run is of
   */
  default IntSupplier actionClock(BattleAction action) {
    throw new UnsupportedOperationException(
        action.name() + " reads the battle clock on an object that is not modelled for it");
  }

  /**
   * Starts a barrage run on the owner, which makes every bomb's area effect in its first update.
   *
   * @param action the barrage
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @return the run
   */
  default ActionInstance cannonBarrage(CannonBarrage action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Drops a barrage's bomb onto this owner, the area effect that caused the drop.
   *
   * @param action the drop
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   */
  default void cannonBomb(CannonProjectileSpawn action, int phase) {
    throw new UnsupportedOperationException(
        action.name() + " drops a bomb onto an object other than an area effect, not modelled");
  }

  /**
   * Drops a container of a balloon pop: its area effect at the owner's point moved by the offsets,
   * the one along the length turned toward the enemy side, for the owner's side and level, the
   * owner its parent.
   *
   * @param action the pop row
   * @param areaEffect the container's area effect row
   * @param offsetX how far along the width from the owner it drops
   * @param offsetY how far along the length, toward the enemy side
   */
  default void dropContainer(PopBalloons action, String areaEffect, int offsetX, int offsetY) {
    throw new UnsupportedOperationException(
        action.name() + " drops a container from an owner other than a character, not modelled");
  }

  /**
   * Starts a run that makes an area effect the row may give its lifetime back to.
   *
   * @param action the row
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @param instigator what caused it, or null for none
   * @return the run
   */
  default ActionInstance resetableAreaEffect(
      SpawnResetableAreaEffect action, int phase, ActionOwner instigator) {
    throw new UnsupportedOperationException(
        action.name() + " on an owner other than a character, not modelled");
  }

  /**
   * Tells the owner's battle an action run at an age was scheduled on it. By default nothing.
   *
   * @param action the name of the action scheduled
   */
  default void aliveTimerFired(String action) {}

  /** The owner's age, which actions run at ages of an area effect read. */
  default AliveTimer.Age aliveAge() {
    throw new UnsupportedOperationException(
        "an action run at an age of an owner other than an area effect is not modelled");
  }

  /** The owner's team: 2 for a neutral side, else its side's lowest bit. */
  default int actionTeam() {
    throw new UnsupportedOperationException("this owner has no team");
  }

  /**
   * Tells the owner's battle a choice by team ran on it. By default nothing.
   *
   * @param action the choice's name
   * @param instigator what caused it
   * @param sameTeam true when the owner and its cause are on the same team
   * @param chosen the name of the action it scheduled, or null for none
   */
  default void filteredByTeam(
      String action, ActionOwner instigator, boolean sameTeam, String chosen) {}

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
