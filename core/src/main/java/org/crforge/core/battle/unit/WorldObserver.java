package org.crforge.core.battle.unit;

import java.util.List;
import java.util.Set;
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
   * A unit was linked into its card's group chain, after the unit the card made before it.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param after the unit before it, or null for the first
   */
  default void chainLinked(int tick, CharacterEntity unit, CharacterEntity after) {}

  /**
   * A unit left its card's group chain as it was released.
   *
   * @param tick the battle tick
   * @param unit the unit
   */
  default void chainUnlinked(int tick, CharacterEntity unit) {}

  /**
   * A run of Goblinstein's ability started on an area effect.
   *
   * @param tick the battle tick
   * @param owner the area effect
   * @param action the row
   * @param phase the pending pass it started in
   */
  default void goblinsteinStarted(int tick, AreaEffectEntity owner, String action, int phase) {}

  /**
   * A run of Goblinstein's ability connected, on its first step.
   *
   * @param tick the battle tick
   * @param owner the area effect it runs on
   * @param connected what it connected to, or null for nothing
   */
  default void goblinsteinConnected(int tick, AreaEffectEntity owner, BattleEntity connected) {}

  /**
   * A run of Goblinstein's ability made its death area where its connected unit left.
   *
   * @param tick the battle tick
   * @param owner the area effect it runs on
   * @param left the unit that left
   * @param deathArea the death area
   * @param x its point along the width
   * @param y its point along the length
   */
  default void goblinsteinDeathAreaMade(
      int tick,
      AreaEffectEntity owner,
      WorldEntity left,
      AreaEffectEntity deathArea,
      int x,
      int y) {}

  /**
   * A run of Goblinstein's ability ended the death area it held, as its area effect left.
   *
   * @param tick the battle tick
   * @param owner the area effect it ran on
   * @param deathArea the death area
   */
  default void goblinsteinDeathAreaEnded(
      int tick, AreaEffectEntity owner, AreaEffectEntity deathArea) {}

  /**
   * A run of Goblinstein's ability saw its followed object cast, saw the cast end and started its
   * tether, or ended its tether.
   *
   * @param tick the battle tick
   * @param owner the area effect it runs on
   * @param step {@code cast_seen}, {@code tether_start} with the tether's time, or {@code
   *     tether_end}
   */
  default void goblinsteinStepped(int tick, AreaEffectEntity owner, String step) {}

  /**
   * A card-play listener heard a card play.
   *
   * @param tick the battle tick
   * @param owner the listener's owner
   * @param action the listener's row
   * @param side the side that played
   * @param played the card played
   * @param deployed the card the play put down
   * @param total the elixir the listener has counted after the play
   * @param scheduled the action the play scheduled on the owner, or null for none
   */
  default void cardPlayHeard(
      int tick,
      WorldEntity owner,
      String action,
      int side,
      String played,
      String deployed,
      int total,
      String scheduled) {}

  /**
   * A tether scheduled one of its activation rows as it started.
   *
   * @param tick the battle tick
   * @param owner the area effect the tether runs on, the row's cause
   * @param target the object the row is scheduled on: the area effect or the connected object
   * @param action the row
   */
  default void tetherActivated(
      int tick, AreaEffectEntity owner, BattleEntity target, String action) {}

  /**
   * A tether's damage pass ran its segment query.
   *
   * @param tick the battle tick
   * @param owner the area effect the tether runs on
   * @param ax the segment's start along the width: the area effect's point
   * @param ay the segment's start along the length
   * @param bx the segment's end along the width: the connected object's point
   * @param by the segment's end along the length
   * @param found what the query answered, in its order, or null when it had no list to answer
   */
  default void tetherDamagePass(
      int tick, AreaEffectEntity owner, int ax, int ay, int bx, int by, List<WorldEntity> found) {}

  /**
   * A tether's damage pass hit an object.
   *
   * @param tick the battle tick
   * @param owner the area effect the tether runs on, the attacker
   * @param target the object hit
   * @param damage the damage dealt
   * @param directionX the hit's direction along the width
   * @param directionY the hit's direction along the length
   * @param result what the hit did
   */
  default void tetherHit(
      int tick,
      AreaEffectEntity owner,
      WorldEntity target,
      int damage,
      int directionX,
      int directionY,
      DamageResult result) {}

  /**
   * A tether's damage pass scheduled its hit action on an object.
   *
   * @param tick the battle tick
   * @param owner the area effect the tether runs on, the action's cause
   * @param target the object
   * @param action the row
   */
  default void tetherHitAction(
      int tick, AreaEffectEntity owner, WorldEntity target, String action) {}

  /**
   * The deck pass at a match's setup gave a champion slot its champion, from a card of its deck.
   *
   * @param tick the tick it happened on
   * @param slot the slot, its champion and deck index set
   */
  default void championDeckPass(int tick, ChampionController slot) {}

  /**
   * A champion slot followed a card play of its champion: its charges, cooldown and state set.
   *
   * @param tick the tick it happened on
   * @param slot the slot
   * @param play the play's name
   */
  default void championFollowed(int tick, ChampionController slot, String play) {}

  /**
   * A champion slot heard a paid ability of its champion and requested its live copies': its
   * cooldown, charges and refund window set.
   *
   * @param tick the tick it happened on
   * @param slot the slot
   * @param requested the live copies it requested, in order
   */
  default void championActivated(
      int tick, ChampionController slot, List<CharacterEntity> requested) {}

  /**
   * A champion slot's cooldown ran out in its step.
   *
   * @param tick the tick it happened on
   * @param slot the slot
   */
  default void championCooldownOut(int tick, ChampionController slot) {}

  /**
   * A champion slot gave its king back its last use's cost, its last live copy gone inside the
   * refund window.
   *
   * @param tick the tick it happened on
   * @param slot the slot
   * @param mana the cost given back, in whole elixir
   * @param elixirBefore the king's elixir before, in ten-thousandths
   * @param elixirAfter the king's elixir after
   */
  default void championRefunded(
      int tick, ChampionController slot, int mana, int elixirBefore, int elixirAfter) {}

  /**
   * A champion slot that follows a champion stepped in its king's run pass.
   *
   * @param tick the tick it happened on
   * @param slot the slot after its step
   * @param elixir its king's elixir before any refund, in ten-thousandths
   * @param views its side's characters of a champion row as the step found them
   */
  default void championStepped(
      int tick, ChampionController slot, int elixir, List<ChampionView> views) {}

  /**
   * An ability's dash looked around its unit: every object of the neighbour query, whether it was
   * valid and how far, the stuns the unit shed, and the winner.
   *
   * @param tick the tick it happened on
   * @param unit the unit
   * @param candidates the objects looked at, in the query's order
   * @param cleansed the stun buffs removed from the unit, in removal order
   * @param chosen the winner, or null for none
   */
  default void abilityDashed(
      int tick,
      CharacterEntity unit,
      List<CharacterEntity.DashCandidate> candidates,
      List<String> cleansed,
      WorldEntity chosen) {}

  /**
   * A unit whose dashes chain started a dash: its count, hit list and first vector already kept.
   *
   * @param tick the tick it happened on
   * @param unit the unit
   * @param fromX where it started, along the width
   * @param fromY where it started, along the length
   * @param aimX the point it dashes toward
   * @param aimY the point it dashes toward
   * @param radius how far short of the point it stops
   */
  default void chainDashStarted(
      int tick, CharacterEntity unit, int fromX, int fromY, int aimX, int aimY, int radius) {}

  /**
   * A chain found its next target as the unit left its dash.
   *
   * @param tick the tick it happened on
   * @param unit the unit
   * @param next the next target
   * @param count the dashes made before it
   * @param x where the unit stood
   * @param y where the unit stood
   */
  default void chainDashed(
      int tick, CharacterEntity unit, WorldEntity next, int count, int x, int y) {}

  /**
   * A chain ended as the unit left its dash: no next target, its count reached, or a crown tower as
   * its last target.
   *
   * @param tick the tick it happened on
   * @param unit the unit
   * @param count the dashes made
   * @param reference the unit's reference as it ended, before the end gave it up
   */
  default void chainDashEnded(int tick, CharacterEntity unit, int count, TargetView reference) {}

  /**
   * An ability's effect gave its unit its buff.
   *
   * @param tick the tick it happened on
   * @param unit the unit
   * @param buff the buff's row
   * @param timeMs how long it lasts
   * @param packedLevel the level it is applied at, packed
   */
  default void abilityBuffed(
      int tick, CharacterEntity unit, String buff, int timeMs, int packedLevel) {}

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
   * A run started or stopped listening for destroyed objects.
   *
   * @param tick the tick it happened on
   * @param owner the object the run is on
   * @param action the run's row name
   * @param listening true as it starts listening, false as it is let go
   */
  default void destroyedListening(int tick, WorldEntity owner, String action, boolean listening) {}

  /**
   * An object's death slot started with runs listening for destroyed objects, which hear of it.
   *
   * @param tick the tick it happened on
   * @param dying the object dying
   * @param listeners how many runs hear of it
   */
  default void destroyedNoticed(int tick, WorldEntity dying, int listeners) {}

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
   * A taunt's perform reached a character, a unit or a crown tower, just before its run is armed.
   *
   * @param tick the battle tick
   * @param unit the taunted character
   * @param action the taunt's name
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @param instigator the area effect that caused it
   * @param forced the object the unit is forced onto, the area effect's parent
   */
  default void tauntPerformed(
      int tick,
      WorldEntity unit,
      String action,
      int phase,
      ActionOwner instigator,
      WorldEntity forced) {}

  /**
   * A taunt's run was armed or stepped, or ended as its forced object left.
   *
   * @param tick the battle tick
   * @param unit the taunted character
   * @param forced the object it is forced onto, or null once that object has left
   * @param durationMs what is left of the taunt
   * @param falloffMs what is left of its falloff
   * @param calls what the arming or step did, in order
   */
  default void tauntStepped(
      int tick,
      WorldEntity unit,
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
   * A unit's ability sent it across the arena: aimed at the mirror of its position, moved inside
   * the arena and off water, and into the in-game pathfinding state.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param mirroredX the mirror of its position along the width
   * @param mirroredY its position along the length
   * @param toX the point it routes to, along the width
   * @param toY the point it routes to, along the length
   * @param reference the reference it held as it left, which it keeps, or null for none
   */
  default void lanesSwitched(
      int tick,
      CharacterEntity unit,
      int mirroredX,
      int mirroredY,
      int toX,
      int toY,
      TargetView reference) {}

  /**
   * A deflecting area effect turned a projectile around, sending it back at its source.
   *
   * @param tick the battle tick
   * @param deflector the area effect
   * @param projectile the projectile
   * @param parent the object the area effect follows, which took the projectile's damage and is the
   *     projectile's launcher now
   * @param source the projectile's root owner, which it is sent back at
   */
  default void projectileDeflected(
      int tick,
      AreaEffectEntity deflector,
      ProjectileEntity projectile,
      WorldEntity parent,
      WorldEntity source) {}

  /**
   * A unit's ability held it in its follow-up state, its first step run.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param countdown the steps of the state left
   */
  default void abilityStateEntered(int tick, CharacterEntity unit, int countdown) {}

  /**
   * A unit whose ability collects souls counted one for a death.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param dying the object whose death it counted
   * @param souls its souls now
   */
  default void soulCounted(int tick, CharacterEntity unit, WorldEntity dying, int souls) {}

  /**
   * A unit's ability spent its souls on the area effect it created, giving it its lifetime.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param areaEffect the area effect
   * @param souls the souls spent
   * @param count how many characters the area effect makes
   * @param lifetimeMs the lifetime it was given
   */
  default void soulsSpent(
      int tick,
      CharacterEntity unit,
      AreaEffectEntity areaEffect,
      int souls,
      int count,
      int lifetimeMs) {}

  /**
   * An area effect's spawner shuffled the order of its directions on its first update.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param order the order
   * @param stateBefore the battle's random state before the shuffle
   * @param stateAfter the state after it
   */
  default void spawnOrdered(
      int tick, AreaEffectEntity areaEffect, int[] order, int stateBefore, int stateAfter) {}

  /**
   * An area effect's spawner made a character, after its registration visit and its clone setter.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param child the character
   * @param retries how many times its point was drawn again
   * @param stateAfter the battle's random state after its draws
   */
  default void areaSpawned(
      int tick, AreaEffectEntity areaEffect, CharacterEntity child, int retries, int stateAfter) {}

  /**
   * Projectiles aimed at a unit lost it as their target as it went into a pathfinding state.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param projectiles the names of the projectiles, in the live list's order
   */
  default void projectilesDropped(int tick, CharacterEntity unit, List<String> projectiles) {}

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

  /** A projectile admitted to the battle scheduled its row's starting action. */
  default void projectileStarting(int tick, ProjectileEntity projectile, String action) {}

  /** A data-changing action swapped a projectile's row. */
  default void projectileSwapped(int tick, ProjectileEntity projectile, String from, String to) {}

  /** The evolved Executioner's axe controller started on its axe. */
  default void executionerStarted(int tick, ProjectileEntity axe, String action, int phase) {}

  /**
   * The axe controller was asked for a hit's damage.
   *
   * @param target what the hit lands on, or null
   * @param before the damage handed in
   * @param after the damage handed on
   * @param edge the target's distance from the axe's start less its radius, or null without one
   * @param strong true for a strong hit
   */
  default void axeDamage(
      int tick,
      ProjectileEntity axe,
      WorldEntity target,
      int hitId,
      int before,
      int after,
      Integer edge,
      boolean strong) {}

  /** A strong hit of the axe scheduled its action on its target. */
  default void axeHitAction(
      int tick, ProjectileEntity axe, WorldEntity target, String action, int hitId) {}

  /** A strong hit of the axe on its way out asked for a push of its target from the axe's start. */
  default void axePushed(
      int tick, ProjectileEntity axe, WorldEntity target, int x, int y, int distance, int hitId) {}

  /**
   * A barrage's run started on a character.
   *
   * @param action the barrage row's name
   * @param phase the phase of the pending pass that ran it, or 0 outside every pass
   */
  default void barrageStarted(int tick, CharacterEntity owner, String action, int phase) {}

  /**
   * A barrage's update made its bombs' area effects and finished.
   *
   * @param action the barrage row's name
   * @param made the area effects, in the order it made them
   */
  default void barrageStepped(
      int tick, CharacterEntity owner, String action, List<AreaEffectEntity> made) {}

  /**
   * A barrage's bomb was dropped onto the area effect that caused the drop.
   *
   * @param area the area effect
   * @param action the drop row's name
   * @param phase the phase of the pending pass that ran the drop
   * @param projectile the bomb, placed at its start and aimed
   * @param lifetime the area effect's lifetime its speed was worked out from
   */
  default void bombDropped(
      int tick,
      AreaEffectEntity area,
      String action,
      int phase,
      ProjectileEntity projectile,
      int lifetime) {}

  /**
   * An action's spawn row launched a projectile from the entity it runs on.
   *
   * @param action the spawn row's name
   * @param phase the phase of the pending pass that ran the action, or 0 outside every pass
   * @param projectile the projectile, placed at its start and aimed
   */
  default void actionProjectileLaunched(
      int tick, WorldEntity owner, String action, int phase, ProjectileEntity projectile) {}

  /**
   * A hiding building scheduled its row's action on itself beside an effect of its hide handler.
   *
   * @param column OnDisappearAction as it starts to hide, OnAppearAction as it rises
   * @param action the row scheduled
   */
  default void hidingHookScheduled(int tick, CharacterEntity unit, String column, String action) {}

  /** An evolved Royal Ghost's run started on it, in the given pending pass or 0 outside. */
  default void ghostEvoStarted(int tick, CharacterEntity ghost, String action, int phase) {}

  /**
   * An evolved Royal Ghost's run made an area effect: a summon area, or the damage area.
   *
   * @param target the reference the run kept, which the damage area carries; null for a summon area
   */
  default void ghostAreaMade(
      int tick, CharacterEntity ghost, AreaEffectEntity area, WorldEntity target) {}

  /**
   * An evolved Royal Ghost's hit summoned: its reference, the summon point and the damage area's
   * countdown.
   */
  default void ghostSummoned(
      int tick, CharacterEntity ghost, WorldEntity reference, int x, int y, int countdownMs) {}

  /** A summon area made its summon, before the summon takes its reference. */
  default void ghostSummonMade(int tick, AreaEffectEntity area, CharacterEntity summon) {}

  /** A summon area's summon took its reference, faced its point and ran its combat gate. */
  default void ghostSummonSpawned(int tick, AreaEffectEntity area, CharacterEntity summon) {}

  /**
   * A buff's start action, as an instance was listed, or its remove action, as one was removed, is
   * about to be scheduled on its carrier.
   *
   * @param start true for the start action
   */
  default void buffHookScheduled(
      int tick, WorldEntity carrier, BuffInstance buff, String action, boolean start) {}

  /**
   * A character or tower counted a hit it dealt that the target's hit points let through.
   *
   * @param attacker what counted it
   * @param target what the hit reached
   * @param before the counter before the hit
   * @param after the counter after it, 0 where the last BuffAfterHits entry came round
   * @param buff the BuffAfterHits buff the count reached, applied to the attacker next, or null
   * @param timeMs that buff's time
   */
  default void hitCounted(
      int tick,
      WorldEntity attacker,
      WorldEntity target,
      int before,
      int after,
      String buff,
      int timeMs) {}

  /** A new buff instance was listed on an entity. */
  default void buffApplied(int tick, WorldEntity target, BuffInstance buff) {}

  /**
   * A parent that carries riders handed a buff it was given to one of them, through the rider's own
   * apply.
   *
   * @param parent the parent the buff was applied to
   * @param rider the rider it was handed to
   * @param buff the row handed over: the buff's own, or the one it names for riders
   * @param time the time of the parent's apply
   * @param packedLevel the level of the parent's apply
   * @param source what applied it to the parent, or null for nothing
   * @param instances the rider's instances of the row after its apply
   * @param heldBefore the keys of the instances the rider listed before its apply
   */
  default void buffHandedOver(
      int tick,
      WorldEntity parent,
      WorldEntity rider,
      BuffData buff,
      int time,
      int packedLevel,
      SpawnHost source,
      List<BuffInstance> instances,
      Set<String> heldBefore) {}

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
   * A listed buff that gives a charge range reset a character's charge.
   *
   * @param unit the character
   * @param instance the instance just listed
   * @param before the charge progress before the reset, -1 for none tracked
   * @param after the charge progress after it
   */
  default void buffChargeReset(
      int tick, CharacterEntity unit, BuffInstance instance, int before, int after) {}

  /**
   * An entity's broken shield scheduled its row's action on the entity's own holder.
   *
   * @param unit the entity
   * @param action the action's row
   * @param cause what the breaking hit came from, or null for none
   * @param inPendingPass whether the battle was inside a pending pass, which starts it at once
   */
  default void shieldLostScheduled(
      int tick, WorldEntity unit, String action, SpawnHost cause, boolean inPendingPass) {}

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
   * A ground-to-air run started on a character.
   *
   * @param tick the battle tick
   * @param unit the character
   * @param action the row
   * @param phase the pending pass it started in
   * @param runPhase the run's phase: 1 climbing, 2 held
   * @param counter what is left of the phase
   */
  default void groundToAirStarted(
      int tick, WorldEntity unit, String action, int phase, int runPhase, int counter) {}

  /**
   * A ground-to-air run took a step.
   *
   * @param tick the battle tick
   * @param unit the character
   * @param phaseBefore the phase as the step began
   * @param phaseAfter the phase as it ended
   * @param counterBefore what was left of the phase as the step began
   * @param counterAfter what was left as it ended
   * @param pushes the height changes the step pushed
   */
  default void groundToAirStepped(
      int tick,
      WorldEntity unit,
      int phaseBefore,
      int phaseAfter,
      int counterBefore,
      int counterAfter,
      List<Integer> pushes) {}

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
   * A kill scheduled its killer's killed-done action on the killer, with what it killed as the
   * cause.
   *
   * @param tick the battle tick
   * @param killer the entity whose hit killed
   * @param killed the entity it killed
   * @param action the killer's row's killed-done action
   * @param inPendingPass true when a pending pass was running, so the action runs at once
   */
  default void killedDoneScheduled(
      int tick, WorldEntity killer, WorldEntity killed, String action, boolean inPendingPass) {}

  /**
   * An action checked what caused it against the rows it names.
   *
   * @param tick the battle tick
   * @param owner the entity the action runs on
   * @param action the action's row
   * @param instigator what caused it, or null for nothing
   * @param scheduled the action it scheduled on its owner, or null for none
   */
  default void instigatorChecked(
      int tick, WorldEntity owner, String action, ActionOwner instigator, String scheduled) {}

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

  /**
   * A guard was made and registered, before its maker faced it and put it into its deploy: where
   * its registration visit left it, its state and its reference.
   *
   * @param tick the battle tick
   * @param guard the guard
   */
  default void guardRegistered(int tick, CharacterEntity guard) {}

  /**
   * A guard-spawning run started on an area effect and made its guard.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param action the row's name
   * @param phase the pending pass it started in
   * @param guard the guard, deploying
   * @param x the point behind the area effect's, along the width
   * @param y the point behind the area effect's, along the length
   * @param toX that point moved off water, along the width
   * @param toY that point moved off water, along the length
   */
  default void guardStarted(
      int tick,
      AreaEffectEntity areaEffect,
      String action,
      int phase,
      CharacterEntity guard,
      int x,
      int y,
      int toX,
      int toY) {}

  /**
   * A guard-spawning run's first step on its area effect, which finished it.
   *
   * @param tick the battle tick
   * @param areaEffect the area effect
   * @param action the row's name
   */
  default void guardFirstStepped(int tick, AreaEffectEntity areaEffect, String action) {}

  /**
   * A step of a guard's run.
   *
   * @param tick the battle tick
   * @param guard the guard
   * @param charging whether the run is charging after the step
   * @param tags the tags the run sets after the step
   * @param done whether the step finished the run
   * @param calls what the step did, in order: the query with the names it found and the damage,
   *     each push with whether the setter ran, each object skipped as untouchable, each hit with
   *     its damage, the deploy cut, the charge point and the finish with its cause
   */
  default void guardStepped(
      int tick,
      CharacterEntity guard,
      boolean charging,
      long tags,
      boolean done,
      List<String> calls) {}

  /**
   * A Boss Bandit ability's run started on a unit.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param action the row
   * @param phase the pending pass it started in
   * @param warpTick the tick it warps on
   * @param lockTick the tick it claims its lock from
   * @param requests the answer of each ask for the lock, in order
   */
  default void bossBanditAbilityStarted(
      int tick,
      CharacterEntity unit,
      String action,
      int phase,
      int warpTick,
      int lockTick,
      List<Boolean> requests) {}

  /**
   * A step of a Boss Bandit ability's run that changed it or asked for its lock again.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param locked whether the run holds its lock after the step
   * @param releaseMs the release countdown after the step
   * @param calls what the step did, in order: the claim and the second ask with their answers, the
   *     warp row scheduled and the finish
   */
  default void bossBanditAbilityStepped(
      int tick, CharacterEntity unit, boolean locked, int releaseMs, List<String> calls) {}

  /**
   * A warp moved a unit; its new position is on the unit.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param action the warp row
   * @param phase the pending pass it ran in
   * @param fromX where it stood along the width
   * @param fromY where it stood along the length
   * @param referenceBefore the name of the reference it held before the warp, or null for none
   */
  default void warped(
      int tick,
      CharacterEntity unit,
      String action,
      int phase,
      int fromX,
      int fromY,
      String referenceBefore) {}

  /**
   * The killer's hook reached a unit whose row passes over buffed targets, on a hit it landed.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param hit the entity the hit reached
   * @param via the projectile that carried the hit, or null for a direct hit or an area
   * @param kills whether the hit killed
   * @param active whether the unit's targeting component was on, so the reference dropped
   * @param dropped the reference it dropped, or null for none
   */
  default void referenceDroppedOnHit(
      int tick,
      CharacterEntity unit,
      WorldEntity hit,
      ProjectileEntity via,
      boolean kills,
      boolean active,
      TargetView dropped) {}

  /**
   * The hold, layer and contact tags of an entity's word changed at its pre-hook, once an uppercut
   * or a knock has raised tags on it.
   *
   * @param word the word's NO_MOVE, NO_ATTACK, LOCK_TARGET, FORCE_IS_AIR and
   *     DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS bits
   */
  default void tagWordChanged(int tick, WorldEntity entity, long word) {}

  /**
   * An uppercut started on a unit.
   *
   * @param target the object it keeps as its target
   * @param finished true when the target has no movement component, which ends the run at once
   */
  default void uppercutStarted(
      int tick,
      CharacterEntity unit,
      String action,
      int phase,
      WorldEntity instigator,
      WorldEntity target,
      boolean finished) {}

  /**
   * An uppercut's update changed its delay, pushed or finished.
   *
   * @param outcome what the update did
   * @param pushPoint the point it pushed away from and the vector from the nearest tower, as x, y,
   *     vx and vy, or null for no push
   */
  default void uppercutStepped(
      int tick,
      CharacterEntity unit,
      int delay,
      boolean finished,
      String outcome,
      int[] pushPoint) {}

  /**
   * An uppercut marked its target in the unit's targeting queue.
   *
   * @param current the unit's current target
   */
  default void uppercutMarked(
      int tick, CharacterEntity unit, WorldEntity target, int priority, WorldEntity current) {}

  /** A unit's pre-hook emptied its targeting queue, taking nothing from it. */
  default void targetQueueFlushed(int tick, CharacterEntity unit) {}

  /** An uppercut's target left the battle and was forgotten. */
  default void uppercutTargetLeft(int tick, CharacterEntity unit, WorldEntity target) {}

  /**
   * A knock started on a unit.
   *
   * @param counter its duration, what is left of it
   */
  default void knockbackStarted(
      int tick,
      CharacterEntity unit,
      String action,
      int phase,
      WorldEntity instigator,
      int counter) {}

  /**
   * A knock's update ran.
   *
   * @param before the counter before it
   * @param after the counter after it
   * @param height the height it pushed last
   * @param tags the tags it raised
   */
  default void knockbackStepped(
      int tick,
      CharacterEntity unit,
      int before,
      int after,
      int height,
      long tags,
      boolean finished) {}

  /** A resetable action made its area effect at a point. */
  default void resetableStarted(
      int tick,
      CharacterEntity unit,
      int phase,
      WorldEntity instigator,
      AreaEffectEntity areaEffect,
      int x,
      int y) {}

  /** A resetable action's run ended, its area effect gone. */
  default void resetableEnded(int tick, CharacterEntity unit, String areaEffect) {}

  /**
   * A resetable action's singleton row was started again.
   *
   * @param countdown the countdown its area effect got back, or null with none live
   */
  default void resetableRetriggered(
      int tick, CharacterEntity unit, int phase, String areaEffect, Integer countdown) {}

  /** A resetable action's area effect left the battle. */
  default void resetableLeft(int tick, CharacterEntity unit, String areaEffect) {}

  /** A resetable action's area effect had its life cut as the unit left. */
  default void resetableReleased(
      int tick, CharacterEntity unit, String areaEffect, int before, int after) {}

  /** A shaped area effect listed what its rectangle reached, in the query's order. */
  default void shapeListed(int tick, AreaEffectEntity areaEffect, List<WorldEntity> listed) {}

  /**
   * A choice by team ran on an entity.
   *
   * @param chosen the name of the action it scheduled, or null for none
   */
  default void filteredByTeam(
      int tick,
      WorldEntity unit,
      String action,
      SpawnHost instigator,
      boolean sameTeam,
      String chosen) {}

  /** An action run at an age was scheduled on an area effect. */
  default void aliveTimerFired(int tick, AreaEffectEntity areaEffect, String action) {}

  /**
   * A run that waited for its cause to leave scheduled its action on its unit.
   *
   * @param tick the battle tick
   * @param unit the unit it ran on
   * @param action the waiting row's name
   * @param scheduled the action's row name
   */
  default void instigatorGone(int tick, WorldEntity unit, String action, String scheduled) {}

  /**
   * A captured unit's pre-hook changed its hidden tag or the capture's tags in its word.
   *
   * @param tick the battle tick
   * @param unit the unit
   * @param hidden whether its word holds the hidden tag
   * @param word the capture's tags its word holds
   */
  default void captureTagsFolded(int tick, WorldEntity unit, boolean hidden, long word) {}

  /**
   * A roll started on a projectile.
   *
   * @param tick the battle tick
   * @param projectile the projectile
   * @param action the roll's row name
   * @param phase the phase of the pending pass that ran it
   * @param destinationX where it ends, along the width
   * @param destinationY where it ends, along the length
   */
  default void rollStarted(
      int tick,
      ProjectileEntity projectile,
      String action,
      int phase,
      int destinationX,
      int destinationY) {}

  /**
   * A capture started on its owner, a projectile or a character.
   *
   * @param tick the battle tick
   * @param owner the owner
   * @param action the capture's row name
   * @param phase the phase of the pending pass that ran it
   */
  default void captureStarted(int tick, BattleEntity owner, String action, int phase) {}

  /**
   * A roll buffed what it found.
   *
   * @param tick the battle tick
   * @param projectile the rolling projectile
   * @param target what it found
   * @param buff the buff's row name
   * @param timeMs how long it lasts
   */
  default void rollBuffed(
      int tick, ProjectileEntity projectile, WorldEntity target, String buff, int timeMs) {}

  /**
   * A roll moved its projectile, or put it on its destination and released it.
   *
   * @param tick the battle tick
   * @param projectile the rolling projectile
   * @param released true for the step that released it
   */
  default void rolled(int tick, ProjectileEntity projectile, boolean released) {}

  /**
   * A capture asked for a lock on a unit.
   *
   * @param tick the battle tick
   * @param owner the capturing owner
   * @param unit the unit
   * @param priority the request's priority
   * @param answer the request's answer
   */
  default void captureRequested(
      int tick, BattleEntity owner, WorldEntity unit, int priority, boolean answer) {}

  /**
   * A capture scheduled an action on an object, with another as its cause.
   *
   * @param tick the battle tick
   * @param owner what it runs on
   * @param cause its cause
   * @param action the action's row name
   */
  default void captureScheduled(int tick, BattleEntity owner, BattleEntity cause, String action) {}

  /**
   * A capture's step ended.
   *
   * @param tick the battle tick
   * @param owner the capturing owner
   * @param captured the ids it holds
   * @param complete the ids whose drag is complete
   * @param timesMs the time of each capture
   */
  default void captureStepped(
      int tick,
      BattleEntity owner,
      List<Integer> captured,
      List<Integer> complete,
      List<Integer> timesMs) {}
}
