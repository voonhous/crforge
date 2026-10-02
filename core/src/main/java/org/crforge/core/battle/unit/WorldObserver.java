package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.TargetIndicatorAttack;
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
   * A typed hit was dealt to an entity by the drain. An observer that does not tell typed hits
   * apart hears of it as of any hit dealt.
   *
   * @param tick the tick the hit landed in
   * @param source the entity that dealt it, or null for none or one that has left the battle
   * @param target the entity it was dealt to
   * @param amount the amount after the type's pipeline
   * @param damageId the hit's damage id; 0 for none
   * @param result what the hit did to the target
   */
  default void typedHitDealt(
      int tick,
      WorldEntity source,
      WorldEntity target,
      int amount,
      int damageId,
      DamageResult result) {
    damageDealt(tick, target, amount, result);
  }

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
   * A taunt's perform reached a unit, just before its run is armed.
   *
   * @param tick the battle tick
   * @param unit the taunted unit
   * @param action the taunt's name
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @param instigator the area effect that caused it
   * @param forced the object the unit is forced onto, the area effect's parent
   */
  default void tauntPerformed(
      int tick,
      CharacterEntity unit,
      String action,
      int phase,
      ActionOwner instigator,
      WorldEntity forced) {}

  /**
   * A taunt's run was armed or stepped, or ended as its forced object left.
   *
   * @param tick the battle tick
   * @param unit the taunted unit
   * @param forced the object it is forced onto, or null once that object has left
   * @param durationMs what is left of the taunt
   * @param falloffMs what is left of its falloff
   * @param calls what the arming or step did, in order
   */
  default void tauntStepped(
      int tick,
      CharacterEntity unit,
      WorldEntity forced,
      int durationMs,
      int falloffMs,
      List<String> calls) {}

  /**
   * An action's spawn row made an area effect, just after it was created.
   *
   * @param tick the battle tick
   * @param owner the owner of the holder that ran the action, at whose point it stands
   * @param action the spawn row's name
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @param source the entity that caused the action, its parent
   * @param areaEffect the area effect
   */
  default void areaEffectSpawned(
      int tick,
      SpawnHost owner,
      String action,
      int phase,
      SpawnHost source,
      AreaEffectEntity areaEffect) {}

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
   * A dying object's death slot launched one of its death projectiles, handed to the holder.
   *
   * @param dying the dying object, its launcher and owner
   * @param projectile the projectile
   */
  default void deathProjectileLaunched(int tick, WorldEntity dying, ProjectileEntity projectile) {}

  /**
   * A limited spawner whose firings are spent asked to leave, as its row destroys it at its limit:
   * the tick's closing cleanup removes it, with no death.
   */
  default void destroyedAtLimit(int tick, CharacterEntity spawner) {}

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
   * A Kamikaze unit's hit ended, before the kill it may run.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param kills true when the end kills it; false for a row that drains its hit points instead
   */
  default void kamikazeHitEnded(int tick, WorldEntity unit, boolean kills) {}

  /**
   * A Goblin Hut's life state did something: started or stepped, found, placed a child, or heard
   * its target leave.
   *
   * @param tick the battle tick
   * @param hut the building running it
   * @param event what it did
   */
  default void goblinHutLogged(int tick, CharacterEntity hut, GoblinHutLifeState.Event event) {}

  /**
   * A target indicator attack's run did something on a unit: it started, its finder found an
   * object, it made a signal, launched a projectile or ended a signal, a step did more than ask the
   * finder for nobody, or it stopped as the unit left.
   *
   * @param tick the battle tick
   * @param unit the unit running it
   * @param event what it did
   */
  default void targetIndicatorLogged(
      int tick, CharacterEntity unit, TargetIndicatorAttack.Event event) {}

  /**
   * A Berserker's run set its unit's attack sequence index: to 0 as it started, or flipped on the
   * notice of an attack that landed.
   *
   * @param tick the battle tick
   * @param unit the unit whose index it set
   * @param event the start or a notice
   * @param before the index before
   * @param index the index after
   */
  default void berserked(int tick, WorldEntity unit, Berserk.Event event, int before, int index) {}

  /**
   * One step of a Kamikaze unit's drain landed on it, in its state visit.
   *
   * @param tick the battle tick
   * @param unit the unit, its own attacker
   * @param damage the step
   * @param hitPointsBefore its hit points before the step
   * @param result what the step did
   */
  default void kamikazeDrained(
      int tick, WorldEntity unit, int damage, int hitPointsBefore, DamageResult result) {}

  /**
   * A spawn whose children take a fixed priority asked the lane of its source's point before
   * placing a child on its ring.
   *
   * @param tick the battle tick
   * @param source what spawns
   * @param x the source's point along the width
   * @param y the source's point along the length
   * @param lane the lane, 1 for the one whose ring is turned over across the width
   */
  default void ringLaneAsked(int tick, SpawnHost source, int x, int y, int lane) {}

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

  /**
   * A projectile's impact made the area effect its row names, at the impact point.
   *
   * @param tick the battle tick
   * @param projectile the landing projectile
   * @param areaEffect the area effect it made, already given its id
   */
  default void projectileAreaEffect(
      int tick, ProjectileEntity projectile, AreaEffectEntity areaEffect) {}

  /**
   * A shape selector's run started on an area effect.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param action the selector's row
   * @param phase the pending pass it started in
   * @param due the battle tick each entry is due on, in order
   */
  default void selectorStarted(
      int tick, AreaEffectEntity areaEffect, String action, int phase, List<Integer> due) {}

  /**
   * A shape selector's step queried its circle or finished its run.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect it runs on
   * @param action the selector's row
   * @param step what the step did
   */
  default void selectorStepped(
      int tick, AreaEffectEntity areaEffect, String action, ShapeSelector.Step step) {}

  /**
   * An air-to-ground run started on a unit.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param action the row
   * @param phase the pending pass it started in
   * @param runPhase the run's phase: 0 held on the ground, 1 descending, 2 held, 3 climbing
   * @param counter what is left of the phase
   * @param height the height the unit flies at, or -1 for a ground unit
   */
  default void airToGroundStarted(
      int tick,
      WorldEntity unit,
      String action,
      int phase,
      int runPhase,
      int counter,
      int height) {}

  /**
   * An air-to-ground run changed its phase or finished.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param phaseBefore the phase as the step began
   * @param phaseAfter the phase as it ended
   * @param counterBefore what was left of the phase as the step began
   * @param counterAfter what was left as it ended
   * @param done true when the step finished the run
   * @param pushes the height changes the step pushed
   */
  default void airToGroundStepped(
      int tick,
      WorldEntity unit,
      int phaseBefore,
      int phaseAfter,
      int counterBefore,
      int counterAfter,
      boolean done,
      List<Integer> pushes) {}

  /**
   * An air-to-ground run was started again by a second start of its row.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param action the row
   * @param phase the run's phase after it
   * @param counter what is left of the phase after it
   */
  default void airToGroundRetriggered(
      int tick, WorldEntity unit, String action, int phase, int counter) {}

  /**
   * A laser ball's run started on an area effect.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param action the laser ball's row
   * @param phase the pending pass it started in
   * @param timerMs its timer as it starts
   */
  default void laserStarted(
      int tick, AreaEffectEntity areaEffect, String action, int phase, int timerMs) {}

  /**
   * A laser ball's run fired.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect it runs on
   * @param count how many objects its step listed
   * @param index the action list's index the count picked
   * @param targets the objects it scheduled the action on, in order
   * @param action the action scheduled on them, or null for none
   * @param timerBefore its timer as the step began
   * @param timerAfter its timer as the step ended
   */
  default void laserFired(
      int tick,
      AreaEffectEntity areaEffect,
      int count,
      int index,
      List<WorldEntity> targets,
      String action,
      int timerBefore,
      int timerAfter) {}

  /**
   * An area effect's last update scheduled its life-end action on itself.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param action the action
   */
  default void lifeTimeEndScheduled(int tick, AreaEffectEntity areaEffect, String action) {}

  /**
   * An area effect's hit scheduled its on-hit action on a unit in its circle, the area effect as
   * the cause.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param target the unit
   * @param action the action scheduled
   */
  default void onHitActionScheduled(
      int tick, AreaEffectEntity areaEffect, WorldEntity target, BattleAction action) {}

  /**
   * A buff-spawning action put its buff on its owner.
   *
   * @param tick the battle tick
   * @param owner the entity the buff went on
   * @param action the action's name
   * @param buff the buff's row
   * @param time how long it was put on for, in milliseconds
   * @param packedLevel the level it was put on at, packed against its source's rarity
   * @param source what applied it: the action's cause, or the owner for none
   */
  default void buffSpawned(
      int tick,
      WorldEntity owner,
      String action,
      BuffData buff,
      int time,
      int packedLevel,
      SpawnHost source) {}

  /**
   * A Clone's perform refused to clone a unit.
   *
   * @param tick the battle tick
   * @param original the unit
   * @param reason why: "ignore clone", "is clone", "dead" or "attached"
   * @param instigator what caused the clone
   */
  default void cloneRefused(int tick, WorldEntity original, String reason, SpawnHost instigator) {}

  /**
   * A clone was made and registered, set up in its clone state; its buffs are not copied yet.
   *
   * @param tick the battle tick
   * @param original the unit it is a clone of
   * @param clone the clone
   * @param instigator what caused it
   * @param registrationVisits the component slots its registration visit visited
   */
  default void cloned(
      int tick,
      CharacterEntity original,
      CharacterEntity clone,
      SpawnHost instigator,
      List<Integer> registrationVisits) {}

  /**
   * A buff a unit carries was copied onto its clone, with the time it has left.
   *
   * @param tick the battle tick
   * @param original the unit
   * @param clone its clone
   * @param copy the clone's new instance
   */
  default void buffCopied(int tick, WorldEntity original, WorldEntity clone, BuffInstance copy) {}

  /**
   * An area effect with a buff asked its filter's buff test of a clone: the buff row's
   * HealPerSecond, a clone refused when it is 1 or more.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param buff its buff row's name
   * @param clone the clone
   * @param path what asked: "area_damage", "area_buff", "pull", "on_hit_action" or "chooser"
   * @param query the buff row's HealPerSecond
   * @param refused whether the clone was refused
   */
  default void cloneBuffGateAsked(
      int tick,
      AreaEffectEntity areaEffect,
      String buff,
      CharacterEntity clone,
      String path,
      int query,
      boolean refused) {}

  /**
   * An expression read attack_count, the hit count of its context's current attack.
   *
   * @param tick the battle tick
   * @param context the entity the expression was evaluated for
   * @param attackTimeMs the context's attack time, which the count divides
   * @param count the count it answered
   */
  default void attackCountRead(int tick, WorldEntity context, int attackTimeMs, int count) {}

  /**
   * A buff visit asked an instance's life condition of its carrier.
   *
   * @param tick the battle tick
   * @param carrier the entity the buff is on
   * @param buff the instance
   * @param answer what the condition answered; 0 ends the instance
   */
  default void lifeConditionAsked(int tick, WorldEntity carrier, BuffInstance buff, int answer) {}

  /**
   * A clone or its original started its move apart, toward a point straight ahead or behind it.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param targetX the point, along the width
   * @param targetY the point, along the length
   */
  default void cloneMoveStarted(int tick, CharacterEntity unit, int targetX, int targetY) {}

  /**
   * A clone's or its original's move apart finished.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param resumed true when it was still in its clone state and was resumed
   */
  default void cloneMoveEnded(int tick, CharacterEntity unit, boolean resumed) {}

  /**
   * A hit reached a reflecting unit's reflect, which has run: whether it struck back, whom, and
   * with what.
   *
   * @param tick the battle tick
   * @param reflection what the reflect did
   */
  default void reflected(int tick, Reflection reflection) {}

  /**
   * A reflecting unit's reflect dealt its damage to the unit it struck, before the unit's death, if
   * the damage killed it, runs.
   *
   * @param tick the battle tick
   * @param reflector the reflecting unit
   * @param struck the unit struck
   * @param damage the damage dealt
   * @param result what the damage did
   */
  default void reflectedHit(
      int tick, WorldEntity reflector, WorldEntity struck, int damage, DamageResult result) {}

  /**
   * A unit's targeting visit armed its special load on its reference, which lies in the ring.
   *
   * @param tick the battle tick
   * @param unit the unit loading its special attack
   * @param reference the reference it loads at
   * @param distanceSquared the squared distance between their centres
   * @param ringMin the ring's inner bound: the reference's radius plus the special's minimum range
   * @param ringMax the ring's outer bound: the reference's radius plus the special's range
   * @param loadMs the load time the special starts from
   * @param afterMs what is left of it once the arming visit has taken its step
   */
  default void specialArmed(
      int tick,
      CharacterEntity unit,
      WorldEntity reference,
      long distanceSquared,
      int ringMin,
      int ringMax,
      int loadMs,
      int afterMs) {}

  /**
   * A dragging projectile's hook or drag asked a unit's state setter for a state.
   *
   * @param tick the battle tick
   * @param projectile the dragging projectile
   * @param unit the unit asked: its target or its owner
   * @param oldState the unit's state before the request
   * @param newState the state asked for
   */
  default void dragStateSet(
      int tick, ProjectileEntity projectile, WorldEntity unit, int oldState, int newState) {}

  /**
   * A projectile a unit's targeting held on left the battle, and the unit forgot it.
   *
   * @param tick the battle tick
   * @param unit the unit whose targeting held the projectile
   * @param projectile the projectile that left
   */
  default void holdLeft(int tick, WorldEntity unit, ProjectileEntity projectile) {}

  /**
   * A projectile a unit followed left the battle, and the unit forgot it.
   *
   * @param tick the battle tick
   * @param unit the unit that followed the projectile
   * @param projectile the projectile that left
   */
  default void followLeft(int tick, WorldEntity unit, ProjectileEntity projectile) {}
}
