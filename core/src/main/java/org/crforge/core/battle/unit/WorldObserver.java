package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * Something that watches one battle's arena from outside the tick: it is told where the tick's
 * entity visits begin and end, about every hit that lands, about every projectile launched and
 * every projectile that arrives, about every arena entity that leaves, and about each step of a
 * king tower's activation, and takes no part in any of it.
 *
 * <p>Both tick calls hand over the tick's arena entities in ascending id, the list the visits ran
 * over. An entity that became removable during the tick is still in it at the end, because the
 * closing cleanup runs after the last call.
 */
public interface WorldObserver {

  /**
   * After the tick's pre-pass: everything the visits query is built, and nothing has been visited.
   */
  default void afterPrePass(int tick, List<WorldEntity> present) {}

  /**
   * After every entity's post-hook and before the closing cleanup: every position and state of the
   * tick is final, an entity that died this tick is still present, and every projectile the tick
   * visited has taken its step.
   *
   * @param tick the tick
   * @param present the tick's arena entities in ascending id
   * @param projectiles the projectiles the tick visited in ascending id, those that arrived
   *     included; the ones launched during the tick are not among them
   */
  default void afterPostHooks(
      int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {}

  /**
   * The damage of one direct hit was dealt to an entity.
   *
   * @param tick the tick the hit landed in
   * @param target the entity the damage was dealt to
   * @param damage hit points the hit dealt, before the target's guards and the clamp to zero
   * @param result what the damage did to the target
   */
  default void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {}

  /**
   * A projectile was launched and handed to the holder; it has its id and its start and aim, and
   * first flies on the next tick.
   */
  default void projectileLaunched(int tick, ProjectileEntity projectile) {}

  /**
   * A projectile arrived and dealt its damage to its target.
   *
   * @param tick the tick of the arrival
   * @param projectile the projectile, standing at its aim
   * @param target the entity the damage was dealt to
   * @param damage hit points the impact dealt, before the target's guards and the clamp to zero
   * @param result what the damage did to the target
   */
  default void projectileImpacted(
      int tick, ProjectileEntity projectile, WorldEntity target, int damage, DamageResult result) {}

  /**
   * An arena entity left the battle. Every remaining entity has been told of it, so what the
   * removal did to another entity's reference is already in place.
   *
   * @param tick the tick last run: the one whose closing cleanup removed the entity, which is where
   *     an entity that dies during a tick leaves
   * @param removed the entity that left
   */
  default void entityRemoved(int tick, WorldEntity removed) {}

  /**
   * A spawned child was linked into its source's group, right after the source, so the group reads
   * newest first.
   *
   * @param tick the tick the spawn ran on
   * @param source the character the child was spawned from
   * @param child the child
   */
  default void groupLinked(int tick, CharacterEntity source, CharacterEntity child) {}

  /**
   * A child left the battle and was unlinked from its source's group, in the cleanup that removed
   * it.
   *
   * @param tick the tick whose closing cleanup removed the child
   * @param source the character whose group it was in
   * @param child the child
   */
  default void groupUnlinked(int tick, CharacterEntity source, CharacterEntity child) {}

  /**
   * A step of a king tower's activation happened: the condition in the run pass, the activating
   * run's start and the effect in a pending pass, its end and its removal in later run passes.
   */
  default void activation(int tick, TowerEntity king, ActivationEvent event) {}

  /**
   * One victim of the area of an entity's hit took its share.
   *
   * @param tick the tick of the hit
   * @param attacker the entity whose hit made the area
   * @param victim the entity the area collected
   * @param damage hit points dealt, before the victim's guards and the clamp to zero
   * @param hitId the id the hit carries
   * @param result what the damage did to the victim
   */
  default void areaHit(
      int tick,
      WorldEntity attacker,
      WorldEntity victim,
      int damage,
      int hitId,
      DamageResult result) {}

  /**
   * The area of an entity's hit is done: who stood in it, whom the validator accepted, and whom it
   * damaged. Told after every victim's own hit.
   */
  default void areaDamaged(
      int tick, WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {}

  /**
   * A unit asked to push itself back after a launch, away from the launch's aim.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param started true when the request aimed a pushback now in flight
   * @param fromX the point pushed away from
   * @param fromY the point pushed away from
   * @param pushback the unit's movement component after the request
   */
  default void pushbackRequested(
      int tick, WorldEntity unit, boolean started, int fromX, int fromY, MovementState pushback) {}

  /**
   * A unit on a cell it may not stand on was moved to the nearest one it may, by its pushback
   * visit.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param x where it stood
   * @param y where it stood
   * @param toX where it was moved to
   * @param toY where it was moved to
   */
  default void relocated(int tick, WorldEntity unit, int x, int y, int toX, int toY) {}

  /**
   * A spawn created a child and registered it, inside the pass that ran the spawn: its registration
   * visit is done and its first-tick immunity set.
   *
   * @param tick the tick the spawn ran on
   * @param source the object the child was spawned from
   * @param child the child
   * @param createdX where the child was created, inside the arena
   * @param createdY where the child was created, inside the arena
   */
  default void characterSpawned(
      int tick, SpawnHost source, CharacterEntity child, int createdX, int createdY) {}

  /**
   * A rider was attached to the parent whose spawner made it, after its registration visit.
   *
   * @param tick the tick it was made on
   * @param parent the character it rides on
   * @param rider the rider
   * @param index its index among the parent's riders
   * @param angle its angle on the parent's ring
   */
  default void riderAttached(
      int tick, CharacterEntity parent, CharacterEntity rider, int index, int angle) {}

  /**
   * A rider's parent left the holder and let it go, before its death slot runs.
   *
   * @param tick the tick of the cleanup
   * @param rider the rider
   * @param parent the parent that left
   */
  default void parentLeft(int tick, CharacterEntity rider, CharacterEntity parent) {}

  /**
   * A dying entity scheduled its death hooks on itself, as its death handler does: its row's death
   * action, then, for a kill by another entity, its killed action. Neither has run yet unless it
   * was scheduled inside a pending pass.
   *
   * @param tick the battle tick
   * @param dying the entity that died
   * @param attacker what killed it: an arena entity, a projectile, or null for nothing
   * @param side the side the kill is credited to
   * @param hooks the names of the rows scheduled, in order
   * @param inPendingPass true when they were scheduled inside a pending pass
   */
  default void deathHooksScheduled(
      int tick,
      WorldEntity dying,
      BattleEntity attacker,
      int side,
      List<String> hooks,
      boolean inPendingPass) {}

  /**
   * A spawn handed a champion it made to its side's champion controllers. Nothing about the child
   * changes.
   *
   * @param tick the battle tick
   * @param source the object the child was spawned from
   * @param child the champion
   */
  default void championHandedOver(int tick, SpawnHost source, CharacterEntity child) {}

  /**
   * A card play made a unit and handed it to the holder, just before it starts the unit.
   *
   * @param tick the battle tick
   * @param unit the unit, with its id
   */
  default void characterPlayed(int tick, CharacterEntity unit) {}

  /**
   * A unit that surfaced was morphed into a new object, which is queued and deploying.
   *
   * @param tick the battle tick
   * @param old the unit, which leaves at the closing cleanup
   * @param made the new object
   */
  default void morphed(int tick, CharacterEntity old, CharacterEntity made) {}

  /**
   * A unit's ability was requested.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param now true when it entered the casting state at once, false when it was left pending
   */
  default void abilityRequested(int tick, CharacterEntity unit, boolean now) {}

  /**
   * A unit's ability fired: its trigger delay reached zero in its state visit.
   *
   * @param tick the battle tick
   * @param unit the unit
   */
  default void abilityFired(int tick, CharacterEntity unit) {}

  /**
   * An area effect was created and handed to the holder, which admits it at the next cleanup.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect, with its id
   * @param how "death", "placed", "chained" or "cast"
   * @param source the name of what it was created from: the dying entity, the area effect that
   *     chains it or the card play that cast it; null for a direct placement
   */
  default void areaEffectCreated(
      int tick, AreaEffectEntity areaEffect, String how, String source) {}

  /**
   * An area effect launched its projectile, after the hits of a step whose hit count rose, or found
   * nobody to drop it onto.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param hit the hits due by the end of the step
   * @param bound the hits due by its start
   * @param choice what its chooser saw, or null for a row that drops its projectile on its point
   * @param projectile the projectile launched, or null when the chooser found nobody
   */
  default void areaEffectLaunched(
      int tick,
      AreaEffectEntity areaEffect,
      int hit,
      int bound,
      AreaEffectEntity.Choice choice,
      ProjectileEntity projectile) {}

  /**
   * An area effect's hit pulled units toward its centre, before its buff was applied: each pulled
   * unit with the vector to the centre and its push accumulators before and after.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param pulls the units pulled, in the order of the battle's live list
   */
  default void areaPulled(
      int tick, AreaEffectEntity areaEffect, List<AreaEffectEntity.Pull> pulls) {}

  /**
   * A unit entering the deploying state pushed the enemies around it: what its query found, and
   * whom it asked to push, after the pushback of each was asked for.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param radius the query's radius
   * @param distance how far each is pushed
   * @param found what the query found, in its order, before any test
   * @param pushed the ones asked to be pushed, in the same order
   */
  default void deployPushed(
      int tick,
      CharacterEntity unit,
      int radius,
      int distance,
      List<WorldEntity> found,
      List<WorldEntity> pushed) {}

  /** An area effect was admitted to the live list, and its starting action scheduled. */
  default void areaEffectAdmitted(int tick, AreaEffectEntity areaEffect) {}

  /**
   * An area effect updated.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param before its countdown before the update
   * @param after its countdown after it
   * @param hits the hits that fell in the step
   * @param radius the radius of its hits
   * @param damages the damage of each hit that dealt one
   */
  default void areaEffectUpdated(
      int tick,
      AreaEffectEntity areaEffect,
      int before,
      int after,
      int hits,
      int radius,
      List<Integer> damages) {}

  /** One victim's share of an area effect's hit, once it is dealt. */
  default void areaEffectHit(
      int tick, AreaEffectEntity areaEffect, WorldEntity victim, int damage, DamageResult result) {}

  /** What one hit of an area effect did, once its victims are dealt and pushed. */
  default void areaEffectDamaged(
      int tick, AreaEffectEntity areaEffect, AreaDamage.Area area, AreaDamage.Outcome outcome) {}

  /** An area effect left the battle, at the cleanup that removed it. */
  default void areaEffectRemoved(int tick, AreaEffectEntity areaEffect) {}

  /**
   * An object without hit points died as its state visit removed it - a bomb as its deploy ended -
   * before its death slot runs.
   */
  default void diedAtRemoval(int tick, WorldEntity entity) {}

  /**
   * A character's spawner fired, after its children were made.
   *
   * @param spawner the character
   * @param row the row of its children
   * @param count how many children the firing made
   * @param radius the ring they stand on, or 0 for in front
   * @param timerAfter its timer to the next firing, in milliseconds
   * @param waveMade how many children of the current wave it has made, 0 once a wave is complete
   */
  default void spawnerFired(
      int tick,
      CharacterEntity spawner,
      String row,
      int count,
      int radius,
      int timerAfter,
      int waveMade) {}

  /**
   * A character's lifetime decay took its last hit point, before its death slot runs.
   *
   * @param hitPointsBefore its hit points before the step
   */
  default void decayDied(int tick, WorldEntity entity, int hitPointsBefore) {}

  /**
   * The combat gate at the tail of an entity's state visit dropped its reference: the entity is
   * dead, still deploying, or stunned.
   *
   * @param tick the tick
   * @param entity the entity whose reference went
   * @param reference the reference it held
   * @param hitSpeed the gate's time step as the entity's buffs scale it; 0 under a stun
   */
  default void combatGateDropped(
      int tick, WorldEntity entity, TargetView reference, int hitSpeed) {}

  /**
   * The combat gate switched an entity's targeting off as a stun holds it, or on again at the first
   * gate after the stun.
   *
   * @param tick the tick
   * @param entity the entity
   * @param on whether its targeting is on now
   * @param hitSpeed the gate's time step as the entity's buffs scale it
   */
  default void combatComponentSwitched(int tick, WorldEntity entity, boolean on, int hitSpeed) {}

  /**
   * An area effect's hit reached the characters its buff applies to, before each is applied.
   *
   * @param tick the tick
   * @param areaEffect the area effect
   * @param buff the buff's row
   * @param time the time it is applied for
   * @param targets the characters that passed its test, in the order they are applied to
   */
  default void areaBuff(
      int tick, AreaEffectEntity areaEffect, BuffData buff, int time, List<WorldEntity> targets) {}

  /**
   * A projectile's impact reached the characters its target buff applies to, before each is
   * applied.
   *
   * @param tick the tick
   * @param projectile the projectile
   * @param buff the buff's row
   * @param time the time it is applied for
   * @param targets the characters that passed its test, in the order they are applied to
   */
  default void projectileBuff(
      int tick, ProjectileEntity projectile, BuffData buff, int time, List<WorldEntity> targets) {}

  /**
   * A buff instance on a dying object made its death spawn's characters, after each was spawned.
   *
   * @param tick the tick
   * @param dying the object that died carrying it
   * @param buff the instance
   * @param made the characters, in the order they were made
   */
  default void buffDeathSpawn(
      int tick, WorldEntity dying, BuffInstance buff, List<CharacterEntity> made) {}

  /**
   * A Kamikaze unit killed itself at the end of its hit.
   *
   * @param damage its hit points before, all of which the kill took
   */
  default void kamikazeKilled(int tick, WorldEntity unit, int damage, DamageResult result) {}

  /**
   * A hiding building's deploy ended, and its state visit ran the combat gate and its targeting
   * visit, one tick before its targeting component's own first visit.
   */
  default void deployEndVisited(int tick, CharacterEntity unit) {}

  /**
   * A hiding building's hide handler visited its hide counter.
   *
   * @param state the state the visit had reached
   * @param before the counter before the visit
   * @param after the counter after it
   * @param step the step the visit took, 0 under a stun that stops time
   * @param effects the effects it played, in order: HideEffect as it starts to hide, AppearEffect
   *     as it rises
   */
  default void hideVisited(
      int tick,
      CharacterEntity unit,
      int state,
      int before,
      int after,
      int step,
      List<String> effects) {}

  /** A new buff instance was listed on an entity. */
  default void buffApplied(int tick, WorldEntity target, BuffInstance buff) {}

  /**
   * A buff instance was refreshed by a re-application. The refresh keeps the instance's own source,
   * which may have left the battle since.
   *
   * @param before what was left of its time before
   * @param source what the re-application came from, or null for nothing
   */
  default void buffRefreshed(
      int tick, WorldEntity target, BuffInstance buff, int before, SpawnHost source) {}

  /**
   * A hit met the entity's shield, which took the whole of it up to its value; whatever was left
   * was lost, and a shield brought to 0 broke.
   *
   * @param damage the hit's amount as it reached the shield
   * @param shieldBefore the shield before the hit
   * @param shieldAfter the shield after it
   */
  default void shieldHit(
      int tick, WorldEntity target, int damage, int shieldBefore, int shieldAfter) {}

  /**
   * A buff instance was removed: its time ran out, or the not-attacking section took its row off.
   */
  default void buffRemoved(int tick, WorldEntity target, BuffInstance buff) {}

  /**
   * A buff instance's heal over time was given to its entity, whether or not it raised anything.
   *
   * @param amount the heal
   * @param hitPointsBefore the entity's hit points before the heal
   */
  default void buffHealed(
      int tick, WorldEntity target, BuffInstance buff, int amount, int hitPointsBefore) {}

  /**
   * A buff instance's damage over time landed on its entity.
   *
   * @param hitPointsBefore the entity's hit points before the hit
   * @param result what the hit did
   */
  default void buffDamaged(
      int tick,
      WorldEntity target,
      BuffInstance buff,
      int damage,
      int hitPointsBefore,
      DamageResult result) {}

  /**
   * A unit's charge completed on the step it just walked.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param progress its charge progress after that step, 10000 or more
   */
  default void chargeCompleted(int tick, CharacterEntity unit, int progress) {}

  /**
   * A unit that was fully charged at the end of its last movement visit is not at the end of this
   * one: it hit, or stopped walking.
   *
   * @param tick the battle tick
   * @param unit the unit
   */
  default void chargeLost(int tick, CharacterEntity unit) {}

  /**
   * A unit's movement pass asked for a state change, and its state setter applied it: a jump over
   * the river starting or landing, or a unit held in place stopping.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param from its state before the request
   * @param to its state after the setter applied it
   */
  default void movementStateRequested(int tick, CharacterEntity unit, int from, int to) {}

  /**
   * A fallen king's circle reached an entity of its side and killed it.
   *
   * @param tick the battle tick
   * @param target the entity
   * @param radius the circle's radius
   */
  default void circleKilled(int tick, WorldEntity target, int radius) {}

  /**
   * A tiebreaker's clearing killed a character.
   *
   * @param tick the battle tick
   * @param target the character
   */
  default void clearingKilled(int tick, WorldEntity target) {}

  /**
   * An elixir collector paid its king.
   *
   * @param tick the battle tick
   * @param collector the collector
   * @param side the side of the king it paid
   * @param amount the whole elixir it paid
   */
  default void elixirCollected(int tick, WorldEntity collector, int side, int amount) {}

  /**
   * A unit's death paid the king of the side that killed it.
   *
   * @param tick the battle tick
   * @param dying the unit
   * @param side the killing side
   * @param amount the elixir paid, in ten-thousandths
   */
  default void deathElixirPaid(int tick, WorldEntity dying, int side, int amount) {}

  /**
   * A tiebreaker's drain took a step off a tower.
   *
   * @param tick the battle tick
   * @param target the tower
   * @param damage the step
   * @param hitPoints the tower's hit points after it
   * @param died whether the step killed it
   */
  default void drained(int tick, WorldEntity target, int damage, int hitPoints, boolean died) {}

  /**
   * A unit's dash wind-up ran out and it started a dash, its state already the dashing one.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param reference the reference it dashed at
   * @param fromX where it stood, along the width
   * @param fromY where it stood, along the length
   * @param aimX the point it dashed toward, along the width
   * @param aimY the point it dashed toward, along the length
   */
  default void dashStarted(
      int tick,
      CharacterEntity unit,
      TargetView reference,
      int fromX,
      int fromY,
      int aimX,
      int aimY) {}

  /**
   * A unit's dash ended and it landed: its landing hit dealt, and its state either the moving one
   * or still the dashing one with its landing hold started.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param hit the one target its landing hit, or null for an area or none
   * @param damage the landing's damage, or 0 when it hit nothing
   * @param area true when the landing hit an area around it
   */
  default void dashLanded(
      int tick, CharacterEntity unit, WorldEntity hit, int damage, boolean area) {}
}
