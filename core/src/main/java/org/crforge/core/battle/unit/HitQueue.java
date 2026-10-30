/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.TakeDamage;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.move.ContactRule;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * The battle's damage: every hit queued for the drain and the drain that deals them after the
 * post-hooks, in the order they were queued, each followed by what the hit brings - the reflect,
 * the buff on damage, the kill. A hit is queued as the attacker's targeting visit, a projectile's
 * impact, an area's share, a buff's tick, a tether, a capture or a kill hands it, and an entity
 * asks the queue whether the damage queued for it will kill it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: a travelling hit's test of a kill by the damage queued for what it hit, which"
            + " client 16.635.1 makes without asking a global, traced on that client and held by"
            + " no reference of it yet. Traced on game client 16.635.1, held by no reference yet:"
            + " the drain's buff on damage of a direct hit taken from the attacker's attack"
            + " sequence entry where it sets one (WorldEntity), and nothing applied for a time"
            + " of 0; and the drain's skip of a Kamikaze kill on a discarded hit under"
            + " LOGIC_SKIP_KAMIKAZE_ON_DISCARDED_HIT, which the world reads from the tables."
            + " Still refused, reached by no row: a buff on damage beside an area radius, whose"
            + " area branch the 16.635.1 drain no longer has; the rest of that path is not"
            + " traced.")
public final class HitQueue {

  private final BattleWorld world;

  HitQueue(BattleWorld world) {
    this.world = world;
  }

  /**
   * One typed hit waiting for the drain.
   *
   * @param source the entity that deals it, or null for none
   * @param target the entity it lands on
   * @param type its damage type
   * @param amount its amount, before the type's pipeline
   * @param directionX the target's position less the source's, along the width
   * @param directionY the same along the length
   */
  private record TypedHit(
      WorldEntity source,
      WorldEntity target,
      DamageType type,
      int amount,
      int directionX,
      int directionY,
      AreaEffectEntity areaSource,
      AreaDamageType areaDamage)
      implements QueuedHit {

    TypedHit(
        WorldEntity source,
        WorldEntity target,
        DamageType type,
        int amount,
        int directionX,
        int directionY,
        AreaEffectEntity areaSource) {
      this(source, target, type, amount, directionX, directionY, areaSource, null);
    }
  }

  /**
   * One direct hit waiting for the drain: what the attacker's targeting visit hands the damage
   * entry, dealt as {@link #dealDamage(WorldEntity, TargetView, int, int, int)} deals it at once,
   * then followed by the attacker's buff on damage when the hit landed. A hit with no attacker, as
   * a damage run hands the entry one, is dealt the same way, with no buff on damage after it, and
   * kills for the side the run handed with it.
   *
   * @param attacker the entity whose hit it is, or null for none
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @param side the side a hit with no attacker kills for, or -1 for none; a hit with an attacker
   *     kills for the attacker's side
   */
  private record DirectHitDue(
      WorldEntity attacker, TargetView target, int damage, int directionX, int directionY, int side)
      implements QueuedHit {}

  /**
   * A reflected attack's damage waiting for the drain, queued by the reflect behind every hit
   * already waiting: dealt there as {@link #reflectedHitNow(ReflectedHitDue)} deals it.
   *
   * @param reflecting the reflecting unit, the hit's attacker
   * @param attacker what dealt the hit the reflect answered
   * @param source the character the reflect traced that hit to
   * @param struck the one struck back
   * @param hitSpeed the reflecting unit's hit-speed scale of 100 at the reflect
   * @param level the reflecting unit's level, packed
   * @param damage the reflected damage at that level
   * @param directionX the answered hit's direction along the arena's width
   * @param directionY the same along the length
   */
  private record ReflectedHitDue(
      WorldEntity reflecting,
      BattleEntity attacker,
      WorldEntity source,
      WorldEntity struck,
      int hitSpeed,
      int level,
      int damage,
      int directionX,
      int directionY)
      implements QueuedHit {}

  /**
   * One victim's share of a character's area damage waiting for the drain: dealt there as {@link
   * #dealAreaDamageNow(WorldEntity, WorldEntity, int, int)} deals it.
   *
   * @param attacker the entity whose hit made the area
   * @param victim the entity the area collected
   * @param damage hit points the area deals it, before its guards and the clamp to zero
   * @param hitId the id the hit carries
   */
  private record AreaHitDue(WorldEntity attacker, WorldEntity victim, int damage, int hitId)
      implements QueuedHit {}

  /**
   * A tether's hit on one object waiting for the drain: dealt there as {@link
   * #tetherHitNow(AreaEffectEntity, WorldEntity, int, int, int)} deals it.
   *
   * @param owner the area effect the tether runs on
   * @param victim the object hit
   * @param damage hit points the hit deals, before the object's guards and the clamp to zero
   * @param directionX the hit's direction along the width
   * @param directionY the hit's direction along the length
   */
  private record TetherHitDue(
      AreaEffectEntity owner, WorldEntity victim, int damage, int directionX, int directionY)
      implements QueuedHit {}

  /**
   * A carried push's hit on one object waiting for the drain: dealt there as a direct hit is dealt,
   * past the hidden test, with nothing after it.
   *
   * @param attacker the entity carrying the push
   * @param target the view of the object hit, as the hit was queued
   * @param damage hit points the hit deals, before the object's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  private record CarriedPushHitDue(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean noCounter)
      implements QueuedHit {}

  /**
   * One victim's share of a projectile's area impact waiting for the drain: dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it at once,
   * without a direction.
   *
   * @param projectile the projectile, standing at its aim
   * @param victim the entity the area collected
   * @param damage hit points the impact deals it, before its guards and the clamp to zero
   * @param hitId the id the impact carries
   */
  private record ProjectileAreaHitDue(
      ProjectileEntity projectile, WorldEntity victim, int damage, int hitId)
      implements QueuedHit {}

  /**
   * A projectile's hit on its one target waiting for the drain: dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it at once,
   * with the flight's direction.
   *
   * @param projectile the projectile, standing at its aim
   * @param target the entity the impact resolved against
   * @param damage hit points the impact deals, before the target's guards and the clamp to zero
   * @param hitId the id the impact carries
   * @param directionX direction of the flight along the arena's width
   * @param directionY direction of the flight along the arena's length
   */
  private record ProjectileHitDue(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY)
      implements QueuedHit {}

  /**
   * One hit of a buff's damage over time waiting for the drain: dealt there as {@link
   * #dealBuffDamageNow(BuffHitDue)} deals it, with its reflect, its observers and the death it
   * causes.
   *
   * @param target the entity carrying the buff
   * @param buff the instance whose damage it is
   * @param damage hit points it deals, after the carrier's own damage reduction
   * @param source what applied the buff, as the instance holds it when the hit is queued
   * @param side the side the buff was applied for, the killing side of a death it causes
   */
  private record BuffHitDue(
      WorldEntity target, BuffInstance buff, int damage, SpawnHost source, int side)
      implements QueuedHit {}

  /**
   * A capture's hit on a unit it holds waiting for the drain: dealt there as a direct hit is, past
   * the hidden test, with no direction and with the capturing character as the attacker, so a hit
   * that kills runs the unit's death there.
   *
   * @param owner the capturing character
   * @param unit the unit held
   * @param damage the damage per hit at the owner's level
   */
  private record CaptureHitDue(CharacterEntity owner, WorldEntity unit, int damage)
      implements QueuedHit {}

  /**
   * The kill of a fallen king's circle waiting for the drain, where it is dealt.
   *
   * @param target the entity the circle reached
   * @param radius the circle's radius
   * @param amount the target's hit points as the kill was queued
   */
  private record CircleKillDue(WorldEntity target, int radius, int amount) implements QueuedHit {}

  /**
   * A Kamikaze unit's kill of itself waiting for the drain, where it is dealt.
   *
   * @param unit the unit whose hit ended
   * @param amount the unit's hit points as the kill was queued
   */
  private record KamikazeKillDue(WorldEntity unit, int amount) implements QueuedHit {}

  /**
   * One step of a Kamikaze unit's drain over its time waiting for the drain, where it is dealt.
   *
   * @param unit the unit whose hit ended
   * @param damage the step
   */
  private record KamikazeDrainDue(WorldEntity unit, int damage) implements QueuedHit {}

  /**
   * A kill action's kill of its owner waiting for the drain, where it is dealt.
   *
   * @param target the kill action's owner
   * @param killer the entity that caused the action, or null for none
   * @param amount the target's hit points as the kill was queued
   */
  private record ActionKillDue(WorldEntity target, WorldEntity killer, int amount)
      implements QueuedHit {}

  /**
   * A tiebreaker's clearing's kill of a character waiting for the drain, where it is dealt.
   *
   * @param target the character the clearing reached
   * @param amount the character's hit points as the clearing reached it
   */
  private record ClearingKillDue(CharacterEntity target, int amount) implements QueuedHit {}

  /**
   * A hit the damage drain deals: a typed hit, a damage-taking action's hit, or a direct hit, a
   * share of a character's or a projectile's area, a tether's or a carried push's hit, a
   * projectile's hit on its one target, a hit of a buff's damage over time, a capture's hit, a
   * circle's kill, a Kamikaze unit's kill, a step of a Kamikaze unit's drain, a kill action's kill
   * or a tiebreaker's clearing's kill.
   */
  private sealed interface QueuedHit
      permits TypedHit,
          DirectHitDue,
          ReflectedHitDue,
          AreaHitDue,
          TetherHitDue,
          CarriedPushHitDue,
          ProjectileAreaHitDue,
          ProjectileHitDue,
          TravellingHitDue,
          ActionDamageDue,
          BuffHitDue,
          CaptureHitDue,
          CircleKillDue,
          KamikazeKillDue,
          KamikazeDrainDue,
          ActionKillDue,
          ClearingKillDue {}

  /**
   * A travelling hit of a projectile flying to a point waiting for the drain: dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it at once,
   * from the direction of the pass's centre, with nothing after it. A deflection's hit on the
   * deflector's parent goes to the same entry, without a direction, and is followed by the
   * projectile's after-damage target buff, as a queued impact is (see {@link
   * #drainProjectileBuff}): the deflection queues the hit with the projectile as its source, and
   * the drain buffs the victim of every hit it lets through whose source is a projectile.
   *
   * @param projectile the projectile whose body covered the entity
   * @param target the entity it covered
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param hitId the id the pass carries
   * @param directionX the entity's position less the pass's centre, along the width
   * @param directionY the same along the length
   * @param deflection true for a deflection's hit on the deflector's parent, which the projectile's
   *     after-damage target buff follows
   */
  private record TravellingHitDue(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY,
      boolean deflection)
      implements QueuedHit {}

  /**
   * A damage-taking action's hit waiting for the drain: its source, its target, the row's damage
   * and the added amount, with the direction from the source to the target as it was queued.
   *
   * @param source the entity that caused the action, or null for none
   * @param areaSource the area effect whose hit ran the action, or null for none; never set with a
   *     source
   * @param target the entity it lands on
   * @param damage the row's damage
   * @param added the added amount
   * @param directionX the target's position less the source's, along the width
   * @param directionY the same along the length
   */
  private record ActionDamageDue(
      WorldEntity source,
      AreaEffectEntity areaSource,
      ProjectileEntity projectileSource,
      WorldEntity target,
      TakeDamage.Damage damage,
      int added,
      int directionX,
      int directionY)
      implements QueuedHit {}

  /**
   * The hits dealt this tick that wait for the drain, in the order they were dealt: one queue, so a
   * direct hit and a typed hit land in the order they were dealt.
   *
   * <p>The game lands a direct hit's damage at the holder's damage drain, after every post-hook,
   * instead of inside the attacker's targeting visit: it queues the hit as it queues a typed hit,
   * so a unit the hit kills keeps its movement visit of that tick and dies where that visit left
   * it. A dash landing's hit on its one target goes to the same entry and is queued as a direct
   * hit: the dasher lands in its movement visit, so a unit the landing kills keeps its own movement
   * visit of that tick when it comes after the dasher's, and the units visited after the dasher
   * find it still moving.
   *
   * <p>The attacker's buff on damage follows its direct hit to the drain: the drain applies it
   * right after the hit's damage, once the damage entry has let the hit through, a hit that kills
   * included. That is after the tick's buff pass, so a 500 ms stun is first counted down on the
   * next tick and holds its target for all of its ten visits.
   *
   * <p>The same rule lands each victim's share of a character's area damage at the drain (see
   * {@link #dealAreaDamage(WorldEntity, WorldEntity, int, int)}): the area of a direct hit and of a
   * dash landing; no row sets a death damage. The area still pushes its victims at once, before
   * their damage lands, so a victim the area kills is pushed too.
   *
   * <p>It lands each victim's share of a projectile's area impact at the drain too (see {@link
   * #dealProjectileAreaDamage(ProjectileEntity, WorldEntity, int, int)}), pushed at once as well.
   * The share counts for the projectile's shooter, and the projectile's listening runs hear of it,
   * as the drain deals it.
   *
   * <p>It lands a projectile's hit on its one target at the drain as well (see {@link
   * #dealProjectileHit(ProjectileEntity, WorldEntity, int, int, int, int)}), whose guards are then
   * tested as the drain deals it: a dasher hit on the step its immunity runs out, after its dash,
   * has that immunity counted down by its own state visit of the step before the drain, and takes
   * the hit.
   *
   * <p>And it lands the two kills of a whole hit points at the drain: a fallen king's circle's (see
   * {@link #circleKill(WorldEntity, int)}) and a Kamikaze unit's of itself as its hit ends (see
   * {@link #kamikazeKill(WorldEntity)}). The circle runs in the match update, before the holder
   * tick, so an entity it kills is still alive through that tick's passes - a tower or a unit that
   * targets it still attacks it, and it takes its own visits - and dies at the drain. A Kamikaze
   * unit killed in its own hit is still alive for the entities visited after it in that tick: one
   * that targets it walks at it once more, and its body still stands among the others. A kill
   * action's kill of its owner (see {@link #kill(WorldEntity, WorldEntity)}) goes the same way: its
   * owner's kill action is scheduled at once, and the owner dies at the drain, so a building a
   * pending pass kills still pushes the units around it in that tick's movement pass. Each of these
   * kills - and a tiebreaker's clearing's (see {@link #clearingKill(CharacterEntity)}) - carries
   * the hit points its target had as it was queued, and the drain deals that amount through the
   * damage entry, which lowers it by the target's damage reduction: a target with a reduction
   * listed lives through it, and one healed since keeps the heal.
   *
   * <p>A buff's damage over time goes the same way (see {@link #dealBuffDamage(WorldEntity,
   * BuffInstance, int)}): the buff pass queues each hit it finds due, and the carrier takes it, and
   * dies of it, at the drain. So a carrier the damage kills is still alive for the rest of the buff
   * pass and for the post-hooks, and what a later carrier's buff makes in that pass - a spawner
   * buff's child - is made before what the dying carrier's death leaves.
   *
   * <p>Each step of a Kamikaze unit's drain over its time goes the same way (see {@link
   * #kamikazeDrain(WorldEntity, int)}): the unit's state visit queues it, and the step that takes
   * its last hit point kills it at the drain. So its death spawn is made after every state visit of
   * that tick, and a spawner listed after it that fires on that tick makes its child first.
   *
   * <p>A Goblinstein tether's hit (see {@link #tetherHit(AreaEffectEntity, WorldEntity, int, int,
   * int)}) and an evolved Battle Ram's carried push's hit (see {@link
   * #dealCarriedPushDamage(WorldEntity, WorldEntity, int, int, int)}) go the same way: each run, in
   * the run pass, hands its hit to the damage entry, so an object either kills is still alive for
   * the second pending pass and the post-hooks and dies at the drain, and its death action runs in
   * the third pending pass. A kill that death action hands on - the evolved Skeleton Army general's
   * kill of the spectral skeletons of its chain - is queued after the drain and lands at the next
   * tick's, as the recorded battles show.
   *
   * <p>The evolved Dart Goblin's poison damage goes the same way (see {@link
   * #dealQueuedDamage(TargetView, int, int, int, int)}): its run, in the action pass, hands each
   * hit to the damage entry with no attacker and the run's side, so a target the poison kills is
   * still alive for the post-hooks - a spawner's child made in them may still pick it - and dies at
   * the drain, for the run's side.
   */
  private final List<QueuedHit> queuedHits = new ArrayList<>();

  /**
   * Where each entity's queued total starts in {@link #queuedHits}: the number of hits queued when
   * its hit points' own visit, in the third component pass, emptied its total this tick. A hit
   * queued for it before that visit - a direct hit of a targeting visit, an area's share - is still
   * dealt at the drain, but no longer counted in the total a travelling hit asks about. Emptied
   * with the queue at the drain, which empties every total; an entity without an entry counts every
   * hit queued for it since.
   */
  private final Map<WorldEntity, Integer> queuedTotalStarts = new IdentityHashMap<>();

  /**
   * Deals the damage of one hit with no attacker to the entity behind a target view, and tells
   * every observer what it did. An entity that has left the battle takes nothing.
   *
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @return what the damage did to the target
   */
  public DamageResult dealDamage(TargetView target, int damage, int directionX, int directionY) {
    return dealDamage(null, target, damage, directionX, directionY);
  }

  /**
   * Deals the damage of one entity's direct hit to the entity behind a target view, and tells every
   * observer what it did. An entity that has left the battle takes nothing.
   *
   * @param attacker the entity whose hit it is, or null for none
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @return what the damage did to the target
   */
  public DamageResult dealDamage(
      WorldEntity attacker, TargetView target, int damage, int directionX, int directionY) {
    return dealDamage(attacker, target, damage, directionX, directionY, false);
  }

  /**
   * Deals the damage of one entity's direct hit, as its targeting visit's hit application hands it
   * to the damage entry, or of a dash landing's hit on its one target, which the landing hands to
   * the same entry: queued for the damage drain, in the order hits are dealt (see {@link
   * #queuedHits}), where it is dealt as {@link #dealDamage(WorldEntity, TargetView, int, int, int)}
   * deals it. A hit dealt after the tick's drain waits for the next tick's, as a typed hit does.
   *
   * @param attacker the entity whose hit it is
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  public void dealDirectHit(
      WorldEntity attacker, TargetView target, int damage, int directionX, int directionY) {
    queuedHits.add(new DirectHitDue(attacker, target, damage, directionX, directionY, -1));
  }

  /**
   * Empties an entity's queued total, as its hit points' own visit does first, in the third
   * component pass, in every state: from here on only the hits queued for it after this point count
   * against its hit points when a travelling hit asks whether the queued damage kills it (see
   * {@link #queuedKill}). The hits already queued stay in the queue and are dealt at the drain.
   *
   * @param entity the entity whose hit points take their visit
   */
  public void emptyQueuedTotal(WorldEntity entity) {
    queuedTotalStarts.put(entity, queuedHits.size());
  }

  /**
   * Deals the damage of one hit with no attacker, as a damage run hands it to the damage entry with
   * the side it deals it for: queued for the damage drain, in the order hits are dealt (see {@link
   * #queuedHits}), where it is dealt as {@link #dealDamage(TargetView, int, int, int)} deals it at
   * once. The record keeps the side, which the drain hands the death handler with no attacker: a
   * target the hit kills dies for that side, so its killed action and the elixir its death gives
   * the opponent go as for a kill of that side (see {@link EntityDeaths#entityDied}). A hit dealt
   * after the tick's drain waits for the next tick's.
   *
   * @param target the view the hit resolved against
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   * @param side the side the hit is dealt for, or -1 for none
   */
  public void dealQueuedDamage(
      TargetView target, int damage, int directionX, int directionY, int side) {
    queuedHits.add(new DirectHitDue(null, target, damage, directionX, directionY, side));
  }

  /**
   * Deals the damage of a carried push's hit to an entity, as the push's run hands it to the damage
   * entry: queued for the damage drain, in the order hits are dealt (see {@link #queuedHits}),
   * where it is dealt as {@link #dealDamage(WorldEntity, TargetView, int, int, int)} deals a direct
   * hit's, but past the hidden test. So an entity the push kills dies at the drain, after the run
   * pass and the post-hooks, and its death action runs in the tick's last pending pass.
   *
   * @param attacker the entity carrying the push
   * @param target the entity hit
   * @param damage hit points the hit deals, before the target's guards and the clamp to zero
   * @param directionX direction of the hit along the arena's width
   * @param directionY direction of the hit along the arena's length
   */
  public void dealCarriedPushDamage(
      WorldEntity attacker,
      WorldEntity target,
      int damage,
      int directionX,
      int directionY,
      boolean noCounter) {
    queuedHits.add(
        new CarriedPushHitDue(
            attacker, target.getTargetView(), damage, directionX, directionY, noCounter));
  }

  private DamageResult dealDamage(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean passesHidden) {
    return dealDamage(attacker, target, damage, directionX, directionY, passesHidden, -1);
  }

  /**
   * Deals one hit as {@link #dealDamage(WorldEntity, TargetView, int, int, int)} does, with the
   * side a hit with no attacker kills for.
   *
   * @param side the side the hit kills for when it has no attacker, or -1 for none; a hit with an
   *     attacker kills for the attacker's side
   */
  private DamageResult dealDamage(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean passesHidden,
      int side) {
    return dealDamage(attacker, target, damage, directionX, directionY, passesHidden, side, false);
  }

  /**
   * Deals one hit as {@link #dealDamage(WorldEntity, TargetView, int, int, int, boolean, int)}
   * does, with the hit's NoCounter flag.
   *
   * @param noCounter true for a hit whose damage is flagged NoCounter, which no counter answers
   */
  private DamageResult dealDamage(
      WorldEntity attacker,
      TargetView target,
      int damage,
      int directionX,
      int directionY,
      boolean passesHidden,
      int side,
      boolean noCounter) {
    WorldEntity entity = world.entityOf(target.getEntity());
    if (entity == null) {
      return DamageResult.NOTHING;
    }
    int before = hitPointsOf(entity);
    DamageResult result =
        entity.takeDamage(
            damage, 0, directionX, directionY, passesHidden, attacker, attacker, null, noCounter);
    reflect(entity, attacker, before, result, directionX, directionY);
    world.events().damageDealt(entity, damage, result);
    if (result.died()) {
      // A hit with no attacker kills for the side it was dealt for, if any.
      entity.die(attacker, attacker != null ? attacker.side() : side);
    } else if (result.landed()) {
      referenceDrop(attacker, entity, false);
    }
    return result;
  }

  /** An entity's hit points as a hit reaches them, 0 for one without. */
  private static int hitPointsOf(WorldEntity entity) {
    return entity.getHitPoints() == null ? 0 : entity.getHitPoints().getHitPoints();
  }

  /** True while a reflect runs, inside which another is not modelled. */
  private boolean reflecting;

  /**
   * The reflect of a unit whose row sets a reflected-attack buff, the Electro Giant's, run inside a
   * hit that reached its hit points alive, after they took the hit and before its death runs.
   *
   * <p>Nothing happens while the unit is stunned - its own hit-speed scale of 100 below 1 - or for
   * a hit without an attacker. The attacker must be a character, building or tower; a projectile is
   * traced to its root, a character, unless its row ignores the reflect; an area effect and what it
   * launched are never struck back. A source riding on a parent is replaced by the parent. The one
   * struck must not be untouchable, must be of the other team, and must stand strictly inside the
   * reflect radius plus both collision radii. It takes the reflect's buff, at the unit's level and
   * for its side, on every reflected hit; and once per attack - the struck one's attack count and
   * id, so the shots of riders share their parent's key until the parent attacks itself, listed on
   * the unit and never cleared - the reflected damage at the unit's level, its crown tower column
   * against a crown tower. That damage is not dealt here: it is appended to the damage queue, as
   * the damage entry queues any hit, and the drain, which reads the queue's count again after every
   * hit, deals it in the same drain after every hit queued before it (see {@link
   * #reflectedHitNow}), through the damage entry with the unit as the attacker, the battle's holds
   * and the hidden test lifted. So a shot that lands on the attacker in the same step meets its
   * shield before the reflected damage does, a hit that kills the unit runs its death before the
   * one struck back takes the damage, and a child the struck one leaves as it dies is made after
   * both.
   */
  private void reflect(
      WorldEntity target,
      BattleEntity attacker,
      int hitPointsBefore,
      DamageResult result,
      int directionX,
      int directionY) {
    UnitData row = target.getData();
    if (row.reflectedAttackBuff() == null || !result.landed() || hitPointsBefore < 1) {
      return;
    }
    if (reflecting) {
      throw new UnsupportedOperationException(
          target.name() + " reflects inside another reflect, which is not modelled");
    }
    reflecting = true;
    try {
      reflectHit(target, attacker, directionX, directionY);
    } finally {
      reflecting = false;
    }
  }

  private void reflectHit(
      WorldEntity target, BattleEntity attacker, int directionX, int directionY) {
    UnitData row = target.getData();
    int hitSpeed = target.getBuffs().hitSpeed(REFLECT_SCALE_BASE);
    WorldEntity source = null;
    if (attacker instanceof WorldEntity character) {
      source = character;
    } else if (attacker instanceof ProjectileEntity projectile) {
      source = projectile.getRoot();
    }
    WorldEntity struck =
        source instanceof CharacterEntity rider && rider.getParent() != null
            ? rider.getParent()
            : source;
    Reflection none =
        new Reflection(target, attacker, source, struck, hitSpeed, null, 0, 0, 0, 0, 0);
    if (hitSpeed < 1
        || source == null
        || attacker instanceof ProjectileEntity projectile
            && projectile.getData().ignoreReflectedAttack()
        || struck.untouchable(true)
        || (struck.side() & 1) == (target.side() & 1)
        || !withinReflect(target, struck, row.reflectedAttackRadius())) {
      world.events().reflected(none);
      return;
    }
    if (struck.getData().reflectedAttackBuff() != null) {
      throw new UnsupportedOperationException(
          target.name()
              + " strikes back at "
              + struck.name()
              + ", which reflects too, not modelled");
    }
    int level = target.getPackedLevel();
    struck
        .getBuffs()
        .apply(
            world.buffData(row.reflectedAttackBuff()),
            row.reflectedAttackBuffDurationMs(),
            level,
            target,
            target.side());
    // The once-per-attack key is the struck one's, after the swap to the parent: a rider's shots
    // all count as its parent's current attack.
    if (struck.getHitPoints() == null || !target.reflectOnce(struck)) {
      world
          .events()
          .reflected(
              new Reflection(
                  target,
                  attacker,
                  source,
                  struck,
                  hitSpeed,
                  row.reflectedAttackBuff(),
                  row.reflectedAttackBuffDurationMs(),
                  level,
                  0,
                  0,
                  0));
      return;
    }
    int base =
        struck.getTargetView().isCrownTowerTarget()
            ? row.reflectAttackCrownTowerDamage()
            : row.reflectedAttackDamage();
    int damage =
        LevelScaling.scale(
            ScalingGlobals.standard(), base, level, ScalingMode.CARD_DAMAGE, row.rarity());
    // The reflected damage is a hit of its own, put at the end of the damage queue: the drain
    // deals it after every hit queued before it, those of this tick still waiting among them.
    queuedHits.add(
        new ReflectedHitDue(
            target, attacker, source, struck, hitSpeed, level, damage, directionX, directionY));
  }

  /**
   * Deals a reflected attack's damage at the drain: through the damage entry with the reflecting
   * unit as the attacker (see {@link WorldEntity#takeReflectedDamage}), the observers told, and the
   * death of the one struck, for the reflecting unit, when the hit kills it. The reflecting unit
   * may have died since the reflect queued the hit; the hit is dealt all the same.
   */
  private void reflectedHitNow(ReflectedHitDue due) {
    WorldEntity target = due.reflecting();
    WorldEntity struck = due.struck();
    UnitData row = target.getData();
    int before = struck.getHitPoints().getHitPoints();
    DamageResult hit =
        struck.takeReflectedDamage(target, due.damage(), due.directionX(), due.directionY());
    world.events().reflectedHit(target, struck, due.damage(), hit);
    world
        .events()
        .reflected(
            new Reflection(
                target,
                due.attacker(),
                due.source(),
                struck,
                due.hitSpeed(),
                row.reflectedAttackBuff(),
                row.reflectedAttackBuffDurationMs(),
                due.level(),
                due.damage(),
                before,
                struck.getHitPoints().getHitPoints()));
    if (hit.died()) {
      struck.die(target);
    }
  }

  /** The base the reflect scales its unit's hit speed from, which below 1 means a stun. */
  private static final int REFLECT_SCALE_BASE = 100;

  /**
   * Whether the one struck stands strictly inside the reflect radius plus both collision radii, the
   * squares compared as the game compares them: unsigned, in 32 bits.
   */
  private static boolean withinReflect(WorldEntity target, WorldEntity struck, int radius) {
    int dx = target.getView().getX() - struck.getView().getX();
    int dy = target.getView().getY() - struck.getView().getY();
    int reach = radius + target.getData().collisionRadius() + struck.getData().collisionRadius();
    return Integer.compareUnsigned(dx * dx + dy * dy, reach * reach) < 0;
  }

  /**
   * Kills an entity, as a kill action kills its owner, and tells every observer what the kill did
   * as they are told of a hit of its whole hit points. The kill is queued for the damage drain, and
   * the entity stays alive until the drain of the tick (see {@link #queuedHits}).
   *
   * @param target the entity
   * @param killer the entity that caused it, or null for none
   */
  public void kill(WorldEntity target, WorldEntity killer) {
    queuedHits.add(new ActionKillDue(target, killer, target.killAmount()));
  }

  /** A kill action's kill, dealt at the damage drain. */
  private void killNow(WorldEntity target, WorldEntity killer, int amount) {
    int before = target.getHitPoints() == null ? 0 : target.getHitPoints().getHitPoints();
    DamageResult result = target.takeKill(amount, null, killer);
    world.events().damageDealt(target, before, result);
    if (result.died()) {
      target.die(killer);
    }
  }

  /**
   * Kills a Kamikaze unit at the end of its hit: its whole hit points, with itself as the attacker
   * on its own side, so its death action runs alone. Every observer is told of the kill. The kill
   * is queued for the damage drain, and the unit stays alive until the drain of the tick (see
   * {@link #queuedHits}).
   *
   * @param unit the unit
   */
  public void kamikazeKill(WorldEntity unit) {
    queuedHits.add(new KamikazeKillDue(unit, unit.killAmount()));
  }

  /** The Kamikaze kill, dealt at the damage drain. */
  private void kamikazeKillNow(WorldEntity unit, int amount) {
    int before = unit.getHitPoints().getHitPoints();
    DamageResult result = unit.takeKill(amount, unit, unit);
    world.events().kamikazeKilled(unit, before, result);
    if (result.died()) {
      unit.die(unit);
    }
  }

  /**
   * Deals one step of a Kamikaze unit's drain over its time, with itself as the attacker on its own
   * side: refused where damage is forbidden, passing the battle's holds, so that a step that takes
   * the last hit point runs its death as its own killer. The step is queued for the damage drain,
   * as a buff's damage over time is, and the unit stays alive until the drain of the tick (see
   * {@link #queuedHits}).
   *
   * @param unit the unit
   * @param damage the step
   */
  void kamikazeDrain(WorldEntity unit, int damage) {
    queuedHits.add(new KamikazeDrainDue(unit, damage));
  }

  /**
   * The step of a Kamikaze unit's drain, dealt at the damage drain: every observer is told of it,
   * and a step that takes the last hit point runs the unit's death there, after every state visit
   * of the tick.
   */
  private void kamikazeDrainNow(WorldEntity unit, int damage) {
    int before = unit.getHitPoints().getHitPoints();
    DamageResult result = unit.takeKamikazeDrain(damage);
    world.events().kamikazeDrained(unit, damage, before, result);
    if (result.died()) {
      unit.die(unit);
    }
  }

  /**
   * Kills an entity of a fallen king's side that the king's circle reached: its whole hit points,
   * no attacker, told to every observer as the circle's kill rather than a hit. The kill is queued
   * for the damage drain, and the entity stays alive until the drain of the tick (see {@link
   * #queuedHits}).
   *
   * @param target the entity
   * @param radius the circle's radius
   */
  public void circleKill(WorldEntity target, int radius) {
    queuedHits.add(new CircleKillDue(target, radius, target.killAmount()));
  }

  /** The circle's kill, dealt at the damage drain. */
  private void circleKillNow(WorldEntity target, int radius, int amount) {
    DamageResult result = target.takeKill(amount, null, null);
    world.events().circleKilled(target, radius);
    if (result.died()) {
      target.die(null);
    }
  }

  /**
   * Kills a character of either side that a tiebreaker's clearing reached: it is resumed at once,
   * and its kill, its hit points as the clearing reached it with no attacker, is queued for the
   * damage drain, as a fallen king's circle's is. The character stays alive through the passes of
   * the update the clearing runs and dies at that update's drain (see {@link #queuedHits}), so
   * whatever reads it before - a tower aiming at it, its own movement, the place its death spawns
   * are made at - finds it as it was. The drain's damage entry lowers the kill by the character's
   * damage reduction, and a heal over time landing in that update's passes is not in it, so such a
   * character lives through the kill and the next step's clearing reaches it again.
   *
   * @param target the character
   */
  public void clearingKill(CharacterEntity target) {
    target.resume();
    queuedHits.add(new ClearingKillDue(target, target.killAmount()));
  }

  /**
   * The clearing's kill, dealt at the damage drain: the hit points it was queued with, no attacker,
   * told to every observer as the clearing's kill rather than a hit.
   */
  private void clearingKillNow(CharacterEntity target, int amount) {
    DamageResult result = target.takeKill(amount, null, null);
    world.events().clearingKilled(target);
    if (result.died()) {
      target.die(null);
    }
  }

  /**
   * Drains a tower by one step of a tiebreaker, which passes the battle's holds, with no attacker.
   *
   * @param target the tower
   * @param damage the step
   */
  public void drain(WorldEntity target, int damage) {
    DamageResult result = target.takeDrain(damage);
    world.events().drained(target, damage, target.getHitPoints().getHitPoints(), result.died());
    if (result.died()) {
      target.die(null);
    }
  }

  /**
   * Deals one victim's share of the area of an entity's hit, and tells every observer what it did:
   * queued for the damage drain, in the order hits are dealt (see {@link #queuedHits}). A victim
   * that has left the battle takes nothing.
   *
   * @param attacker the entity whose hit made the area
   * @param victim the entity the area collected
   * @param damage hit points the area deals it, before its guards and the clamp to zero
   * @param hitId the id the hit carries
   * @return what the damage did to the victim; nothing yet for a share queued for the drain
   */
  public DamageResult dealAreaDamage(
      WorldEntity attacker, WorldEntity victim, int damage, int hitId) {
    queuedHits.add(new AreaHitDue(attacker, victim, damage, hitId));
    return DamageResult.NOTHING;
  }

  /**
   * Deals one victim's share of the area of an entity's hit at once, and tells every observer what
   * it did. A victim that has left the battle takes nothing.
   */
  private DamageResult dealAreaDamageNow(
      WorldEntity attacker, WorldEntity victim, int damage, int hitId) {
    if (victim == null || world.entityOf(victim.getView()) != victim) {
      return DamageResult.NOTHING;
    }
    // A character's area carries no dedupe id and no direction.
    int before = hitPointsOf(victim);
    DamageResult result = victim.takeDamage(damage, 0, 0, 0, false, attacker, attacker);
    reflect(victim, attacker, before, result, 0, 0);
    world.events().areaHit(attacker, victim, damage, hitId, result);
    if (result.died()) {
      victim.die(attacker);
    } else if (result.landed()) {
      referenceDrop(attacker, victim, false);
    }
    return result;
  }

  /**
   * Deals one victim's share of a projectile's area impact: queued for the damage drain, in the
   * order hits are dealt (see {@link #queuedHits}), where it is dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it, without a
   * direction. Dealt at the drain, the share counts for the projectile's shooter as it is then, and
   * the projectile's listening runs hear of it there.
   *
   * @param projectile the projectile, standing at its aim
   * @param victim the entity the area collected
   * @param damage hit points the impact deals it, before its guards and the clamp to zero
   * @param hitId the id the impact carries
   * @return what the damage did to the victim; nothing yet for a share queued for the drain
   */
  public DamageResult dealProjectileAreaDamage(
      ProjectileEntity projectile, WorldEntity victim, int damage, int hitId) {
    queuedHits.add(new ProjectileAreaHitDue(projectile, victim, damage, hitId));
    return DamageResult.NOTHING;
  }

  /**
   * Deals the damage of a projectile's hit on its one target: queued for the damage drain, in the
   * order hits are dealt (see {@link #queuedHits}), where it is dealt as {@link
   * #dealProjectileDamage(ProjectileEntity, WorldEntity, int, int, int, int)} deals it. The
   * target's guards are tested as the drain deals it, after every post-hook of the tick: a dasher
   * whose immunity its own state visit of that tick counted down to 0 takes the hit.
   *
   * @param projectile the projectile, standing at its aim
   * @param target the entity the impact resolved against
   * @param damage hit points the impact deals, before the target's guards and the clamp to zero
   * @param hitId the id the impact carries, counted by the battle
   * @param directionX direction of the flight along the arena's width
   * @param directionY direction of the flight along the arena's length
   */
  public void dealProjectileHit(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY) {
    queuedHits.add(new ProjectileHitDue(projectile, target, damage, hitId, directionX, directionY));
  }

  /**
   * Deals the damage of a projectile's impact to its target, and tells every observer what it did.
   * A target that has left the battle takes nothing.
   *
   * @param projectile the projectile, standing at its aim
   * @param target the entity the impact resolved against
   * @param damage hit points the impact deals, before the target's guards and the clamp to zero
   * @param hitId the id the impact carries, counted by the battle
   * @param directionX direction of the flight along the arena's width
   * @param directionY direction of the flight along the arena's length
   * @return what the damage did to the target
   */
  public DamageResult dealProjectileDamage(
      ProjectileEntity projectile,
      WorldEntity target,
      int damage,
      int hitId,
      int directionX,
      int directionY) {
    if (target == null || world.entityOf(target.getView()) != target) {
      return DamageResult.NOTHING;
    }
    // A projectile's hit carries its group id as the dedupe id: 0 for one of no group, which lands
    // every time; the projectiles of one group land on the target once.
    int before = hitPointsOf(target);
    // The impact counts for the projectile's shooter, while it is in the battle.
    // A projectile with an action holder has its listening runs hear of the hit in the
    // subtraction.
    DamageResult result =
        target.takeDamage(
            damage,
            projectile.getGroupId(),
            directionX,
            directionY,
            false,
            projectile.getOwner(),
            projectile,
            projectile.hasActionHolder() ? () -> projectile.hitHeard(hitId, target) : null);
    reflect(target, projectile, before, result, directionX, directionY);
    world.events().projectileImpacted(projectile, target, damage, result);
    if (result.died()) {
      target.die(projectile);
    } else if (result.landed()) {
      referenceDrop(projectile, target, false);
    }
    return result;
  }

  /**
   * A tether's hit, as its damage pass hands it to the damage entry: queued for the damage drain,
   * in the order hits are dealt (see {@link #queuedHits}), where it is dealt as {@link
   * #tetherHitNow(AreaEffectEntity, WorldEntity, int, int, int)} deals it. So an object the tether
   * kills dies at the drain, after the run pass and the post-hooks, and its death action runs in
   * the tick's last pending pass: a kill that action hands on lands at the next tick's drain.
   *
   * @param owner the area effect the tether runs on
   * @param victim the object hit
   * @param damage the damage
   * @param directionX the hit's direction along the width
   * @param directionY the hit's direction along the length
   */
  void tetherHit(
      AreaEffectEntity owner, WorldEntity victim, int damage, int directionX, int directionY) {
    queuedHits.add(new TetherHitDue(owner, victim, damage, directionX, directionY));
  }

  /**
   * A tether's hit at the drain: an area effect's hit, the area effect the attacker, with the
   * damage entry's hidden test lifted and the hit's direction carried to a death.
   */
  private void tetherHitNow(
      AreaEffectEntity owner, WorldEntity victim, int damage, int directionX, int directionY) {
    int before = hitPointsOf(victim);
    DamageResult result = victim.takeDamage(damage, 0, directionX, directionY, true, null, owner);
    reflect(victim, owner, before, result, directionX, directionY);
    world.events().tetherHit(owner, victim, damage, directionX, directionY, result);
    if (result.died()) {
      victim.die(owner);
    }
  }

  /**
   * The first block of the killer's hook, which the hit-points chain calls on the attacker of every
   * hit that lands, a kill or not: a character whose row both passes over and ranks lower the
   * targets carrying a buff - the Ram Rider's rider - drops its reference through the setter's null
   * path while its targeting component is on, before the kill's blocks, the death handler and a
   * projectile's target buff. It selects again on its next targeting visit, where the buff's
   * carriers rank lower; its attack time runs on. A projectile's hit reaches its shooter, while the
   * shooter is in the battle and a character. Every hit that reaches such a row is told to the
   * observers.
   *
   * <p>A hit on a target already below 1 hit point - killed earlier in the same drain by another
   * hit - lands, but the subtraction leaves before its tail, so the attacker's hook is not called
   * and nothing is dropped. The rider then still holds the dead target when the cleanup removes it,
   * and the removal notice starts its target-lost countdown as for any attacker, unless the rider
   * kept the target only for the lethal damage of its own shot in flight.
   *
   * @param attacker what landed the hit: an arena entity, a projectile, or anything else
   * @param hit the entity it hit
   * @param kills whether the hit killed it
   */
  void referenceDrop(BattleEntity attacker, WorldEntity hit, boolean kills) {
    // A hit that lands without killing leaves the target below 1 hit point only when it was there
    // already: the subtraction takes nothing from such a target and skips the attacker's hook.
    if (!kills && hitPointsOf(hit) < 1) {
      return;
    }
    ProjectileEntity via = attacker instanceof ProjectileEntity projectile ? projectile : null;
    BattleEntity shooter = via != null ? via.getOwner() : attacker;
    if (!(shooter instanceof CharacterEntity unit)) {
      return;
    }
    UnitData data = unit.getData();
    if (data.ignoreTargetsWithBuff() == null || !data.deprioritizeTargetsWithBuff()) {
      return;
    }
    boolean active = unit.isActive(CharacterEntity.TARGETING_SLOT);
    TargetView dropped = active ? unit.dropReferenceOnHit() : null;
    world.events().referenceDroppedOnHit(unit, hit, via, kills, active, dropped);
  }

  /**
   * Whether the damage queued for the drain will kill an entity, as a travelling hit just queued
   * asks it: the hits queued for the entity and not yet dealt, the one just queued among them, add
   * up to at least its hit points and shield. The hits counted are those of the queueing damage
   * entry, which adds each one's damage to the entity's queued total and the drain empties:
   * travelling hits, direct hits, a projectile's hit on its one target, the shares of a character's
   * or a projectile's area and the hits of a buff's damage over time. A damage-taking action's hit
   * is queued by an entry of its own, which adds nothing to the total: its amount is worked out
   * only at the drain, so it never counts here. The entity's hit points' own visit empties the
   * total in the third component pass (see {@link #emptyQueuedTotal}), so a hit queued before it in
   * the tick - a targeting visit's direct hit, an area's share - is not counted by a travelling hit
   * flying in the post-hooks, though it is dealt at the drain all the same; held by crawled battles
   * where a Hunter's volley met a target another unit's hit kills on the same step, each pellet
   * that reaches it then stopping at it.
   *
   * <p>Client 16.635.1 asks no global here and keeps only this test; its globals table has no
   * V16_PROJECTILE_DAMAGE_BUG. Client 16.402.17 asked that global first and, with it clear, held
   * the one hit's damage alone against the entity's hit points; every data version of its range set
   * it. So the global is not read, whatever a table holds.
   *
   * @param entity the entity hit
   */
  private boolean queuedKill(WorldEntity entity) {
    HitPoints hitPoints = entity.getHitPoints();
    int queued = 0;
    int start = queuedTotalStarts.getOrDefault(entity, 0);
    for (QueuedHit hit : queuedHits.subList(start, queuedHits.size())) {
      if (hit instanceof TravellingHitDue travelling && travelling.target() == entity) {
        queued += travelling.damage();
      } else if (hit instanceof DirectHitDue direct
          && world.entityOf(direct.target().getEntity()) == entity) {
        queued += direct.damage();
      } else if (hit instanceof AreaHitDue area && area.victim() == entity) {
        queued += area.damage();
      } else if (hit instanceof TetherHitDue tether && tether.victim() == entity) {
        queued += tether.damage();
      } else if (hit instanceof CarriedPushHitDue push
          && world.entityOf(push.target().getEntity()) == entity) {
        queued += push.damage();
      } else if (hit instanceof ProjectileAreaHitDue share && share.victim() == entity) {
        queued += share.damage();
      } else if (hit instanceof BuffHitDue buffHit && buffHit.target() == entity) {
        queued += buffHit.damage();
      } else if (hit instanceof KamikazeDrainDue drain && drain.unit() == entity) {
        queued += drain.damage();
      } else if (hit instanceof CaptureHitDue capture && capture.unit() == entity) {
        queued += capture.damage();
      } else if (hit instanceof ProjectileHitDue single && single.target() == entity) {
        queued += single.damage();
      }
      // A damage-taking action's hit adds nothing: its amount is worked out only at the drain.
    }
    return queued >= hitPoints.getHitPoints() + hitPoints.getShield();
  }

  /**
   * A flying body's hit on one entity it covers, as the translated hit runs it. Held by the
   * reference battles card_Log, spell_log_into_push, card_BarbLog and spell_barblog_into_push: the
   * damage at the level, the id list, and no push from a hit that kills; held by no run: the own
   * side spared, the crown-tower share, the untouchable listing, the air and jump checks, and the
   * push itself. Held by the Log's and the Bowler's over a jumping Mega Knight: a dash under a row
   * with a jump height spared, and the Log's hit once the Mega Knight has landed under it. In
   * order: none on its own side when it hits enemies only, none on an entity it has hit already; a
   * character that takes no part in contact in any state - under the no-check-collisions tag, as
   * the Little Prince's guard is while it deploys and charges, attached to a parent, or a building
   * of the characters table beside another building - is spared without being listed, as the
   * recorded Logs and bodies over a deploying guard show; a character in its dash under a row with
   * a dash immunity is spared without being listed, as the recorded Hunter pellets over a Golden
   * Knight in its chain show; any other untouchable character - one protected by the immunity left
   * after its dash among them - is listed as hit and spared, but for one tunnelling to its
   * placement, which is no exception here: it is listed, takes the hit into its queue, which the
   * damage entry then refuses at the drain, and stops a projectile that stops at collisions, as the
   * recorded Hunter pellets over a tunnelling Miner or a relocating evolved Goblin Drill show; a
   * character on a layer the projectile does not reach, in the air, or, for a projectile that does
   * not reach the air, in a jump or in a dash under a row with a jump height, is spared without
   * being listed. An entity with hit points takes the projectile's damage at its level, or its
   * crown-tower share, never a deflected share - a deflected body hits a crown tower for the whole
   * of it, as the Bowler's ball rolled back into its own princess tower does - as the listening
   * runs of a projectile with an action holder change it - the evolved Executioner's controller in
   * place of it - from the direction of the pass's centre, and is listed as hit, a carrier whose
   * row attaches its riders with the ids of its riders (held by the Bowler's over a Goblin Giant,
   * whose riders' Spear Goblins it then passes over); then a character whose movement is still on
   * is pushed the row's pushback away from the projectile, the row's push-all lifting the gates but
   * for the hidden one: a hidden character, a tunnelling one among them, is not pushed. A
   * projectile that stops at collisions is finished by a hit that landed on an entity with hit
   * points left, and the pass ends; held by the Hunter's pellets in the reference battle
   * card_Hunter.
   *
   * @return true when the hit finished the projectile
   */
  boolean travellingHit(ProjectileEntity projectile, WorldEntity entity, int x, int y, int hitId) {
    ProjectileData data = projectile.getData();
    if (data.onlyEnemies() && (entity.side() & 1) == (projectile.side() & 1)) {
      return false;
    }
    int id = entity.getId();
    if (projectile.getHitIds().contains(id)) {
      return false;
    }
    // An entity its volley's group has doomed is passed over, and not listed as hit.
    if (projectile.getGroup() != null && projectile.getGroup().dooms(id)) {
      return false;
    }
    GridEntity view = entity.getView();
    // A character first answers whether it takes part in contact, the state clauses left out: one
    // under the no-check-collisions tag - the Little Prince's guard from its run's start to its
    // finish, its deploy included - or attached to a parent is spared without being listed.
    if (entity instanceof CharacterEntity && ContactRule.collidesInAnyState(view) == 0) {
      return false;
    }
    // A character in its dash under a row with a dash immunity is spared without being listed, so
    // the body can still hit it once its dash and the immunity left after it are over.
    if (entity instanceof CharacterEntity
        && view.getState() == GridEntityState.DASHING
        && entity.getData().dashImmuneToDamageTimeMs() > 0) {
      return false;
    }
    // A character tunnelling to its placement is not spared here: the hit is queued for it, and the
    // damage entry refuses it at the drain, but a projectile that stops at collisions stops at it.
    if (entity.untouchable() && !entity.tunnelling()) {
      projectile.getHitIds().add(id);
      return false;
    }
    if (!data.aoeToAir() && view.isAir()) {
      return false;
    }
    if (!data.aoeToGround() && !view.isAir()) {
      return false;
    }
    if (!data.aoeToAir() && view.getState() == GridEntityState.JUMPING) {
      return false;
    }
    // A dasher whose row has a jump height is off the ground while it dashes, as the Mega Knight
    // is in its jump: spared, and not listed, so the body can still hit it once it has landed.
    if (!data.aoeToAir()
        && view.getState() == GridEntityState.DASHING
        && entity.getData().jumpHeight() >= 1) {
      return false;
    }
    if (entity.getHitPoints() == null) {
      return false;
    }
    boolean crownTower = entity.getTargetView().crownTower();
    // The travelling hit reads no deflection: the row's amounts at the level.
    int damage = crownTower ? projectile.undeflectedTowerDamage() : projectile.undeflectedDamage();
    // A projectile with an action holder has its listening runs change the damage.
    if (projectile.hasActionHolder()) {
      damage = projectile.listenedDamage(damage, hitId, crownTower, entity);
    }
    boolean standing = entity.getHitPoints().getHitPoints() >= 1;
    projectile.getHitIds().add(id);
    // A carrier whose row attaches its riders has their ids listed with its own, so they are never
    // hit apart; a rider's death spawn that inherits the ignore list joins this list in its turn.
    if (entity instanceof CharacterEntity carrier && carrier.getData().spawnAttach()) {
      for (CharacterEntity rider : carrier.riders()) {
        projectile.getHitIds().add(rider.getId());
      }
    }
    // Queued for the drain, where the entity takes it and may die, after every movement visit of
    // the tick; then, should the entity's queued damage now kill it, it is doomed for the rest of
    // the volley.
    queuedHits.add(
        new TravellingHitDue(
            projectile, entity, damage, hitId, view.getX() - x, view.getY() - y, false));
    if (projectile.getGroup() != null && queuedKill(entity)) {
      projectile.getGroup().doom(id);
    }
    if (data.pushback() >= 1 && entity instanceof CharacterEntity character) {
      character.pushedByTravellingHit(
          projectile.getX(), projectile.getY(), data.pushback(), data.pushbackAll());
    }
    // A projectile that stops at collisions is finished by a hit on an entity that had hit points
    // left: any, as the hit waits for the drain, whose guards are not asked yet.
    if (standing && data.checkCollisions()) {
      projectile.finishOnCollision();
      return true;
    }
    return false;
  }

  /**
   * Queues a typed hit, which the drain deals after the post-hooks of the tick; one queued after
   * that lands on the next tick.
   *
   * @param source the entity that deals it, or null for none
   * @param target the entity it lands on
   * @param type its damage type
   * @param amount its amount, before the type's pipeline
   */
  public void queueTypedHit(WorldEntity source, WorldEntity target, DamageType type, int amount) {
    int directionX = source == null ? 0 : target.getView().getX() - source.getView().getX();
    int directionY = source == null ? 0 : target.getView().getY() - source.getView().getY();
    queuedHits.add(new TypedHit(source, target, type, amount, directionX, directionY, null));
  }

  /**
   * Queues a damage-taking action's hit, which the drain deals after the post-hooks of the tick;
   * one queued after that lands on the next tick. Its source is the character, building or tower
   * that caused the action, the area effect whose hit ran it, or the projectile whose deflection
   * ran it on the deflector's parent; anything else that caused it is no source, and the level
   * scaling of a damage from such a cause is refused.
   *
   * @param cause what caused the action, or null for nothing
   * @param target the entity it lands on
   * @param damage the row's damage
   * @param added the added amount
   */
  void queueActionDamage(
      ActionOwner cause, WorldEntity target, TakeDamage.Damage damage, int added) {
    WorldEntity source = cause instanceof WorldEntity entity ? entity : null;
    AreaEffectEntity areaSource = cause instanceof AreaEffectEntity area ? area : null;
    ProjectileEntity projectileSource = cause instanceof ProjectileEntity shot ? shot : null;
    if (cause != null
        && source == null
        && areaSource == null
        && projectileSource == null
        && damage.levelScaled()) {
      throw new UnsupportedOperationException(
          "a damage-taking action's hit scaled by the level of "
              + cause.actionRowName()
              + ", neither a character nor an area effect, is not modelled");
    }
    int directionX = 0;
    int directionY = 0;
    if (source != null) {
      directionX = target.getView().getX() - source.getView().getX();
      directionY = target.getView().getY() - source.getView().getY();
    } else if (areaSource != null) {
      directionX = target.getView().getX() - areaSource.getX();
      directionY = target.getView().getY() - areaSource.getY();
    } else if (projectileSource != null) {
      // Taken from the projectile's point as from a character's: the direction is kept only for a
      // death, and no recording holds one by such a hit.
      directionX = target.getView().getX() - projectileSource.getX();
      directionY = target.getView().getY() - projectileSource.getY();
    }
    queuedHits.add(
        new ActionDamageDue(
            source, areaSource, projectileSource, target, damage, added, directionX, directionY));
  }

  /**
   * Queues a typed hit from an area effect, as a shaped area effect's damage does: the area effect
   * its source, with no direction. The drain deals it after the post-hooks of the tick.
   *
   * @param source the area effect that deals it
   * @param target the entity it lands on
   * @param type its damage type
   * @param amount its amount, before the type's pipeline
   */
  public void queueTypedHit(
      AreaEffectEntity source, WorldEntity target, DamageType type, int amount) {
    queuedHits.add(new TypedHit(null, target, type, amount, 0, 0, source));
  }

  /**
   * Queues the damage a filter form area effect deals to one object it lists, as a typed hit of its
   * damage type with the area effect its source, its direction from the area effect's point to
   * where the object stands now. The drain deals it after the post-hooks of the tick.
   *
   * @param source the area effect that deals it
   * @param target the object it lands on
   * @param damage the row's damage type
   */
  void queueAreaDamage(AreaEffectEntity source, WorldEntity target, AreaDamageType damage) {
    queuedHits.add(
        new TypedHit(
            null,
            target,
            null,
            0,
            target.getView().getX() - source.getX(),
            target.getView().getY() - source.getY(),
            source,
            damage));
  }

  /**
   * Deals every queued hit, in the order they were queued, a hit queued while the drain runs - a
   * reflect's damage - included: the drain reads the queue's count again after every hit, and
   * empties the queue once it reaches the end. A typed hit: the type's pipeline, a damage id from
   * the battle's hit counter when the type takes one, the typed hit's entry, then the type's action
   * on the source and its action on the target, and the observers are told. A direct hit, a share
   * of a character's or a projectile's area, a tether's or a carried push's hit, a projectile's hit
   * on its one target, or a capture's hit: the damage dealt, with its reflect, its observers, its
   * death or the reference drop; a hit of a buff's damage over time; a circle's, a Kamikaze unit's,
   * a kill action's or a tiebreaker's clearing's kill; a reflect's damage.
   */
  void drainTypedHits() {
    queuedTotalStarts.clear();
    // The drain reads the queue's length again after each hit, so a hit queued while it runs - a
    // reflected attack's damage - is dealt in this drain too, after every hit queued before it.
    for (int index = 0; index < queuedHits.size(); index++) {
      QueuedHit queued = queuedHits.get(index);
      if (queued instanceof ReflectedHitDue reflected) {
        reflectedHitNow(reflected);
        continue;
      }
      if (queued instanceof DirectHitDue direct) {
        DamageResult result =
            dealDamage(
                direct.attacker(),
                direct.target(),
                direct.damage(),
                direct.directionX(),
                direct.directionY(),
                false,
                direct.side());
        drainBuffOnDamage(direct.attacker(), direct.target(), result);
        continue;
      }
      if (queued instanceof AreaHitDue area) {
        dealAreaDamageNow(area.attacker(), area.victim(), area.damage(), area.hitId());
        continue;
      }
      if (queued instanceof TetherHitDue tether) {
        tetherHitNow(
            tether.owner(),
            tether.victim(),
            tether.damage(),
            tether.directionX(),
            tether.directionY());
        continue;
      }
      if (queued instanceof CarriedPushHitDue push) {
        dealDamage(
            push.attacker(),
            push.target(),
            push.damage(),
            push.directionX(),
            push.directionY(),
            true,
            -1,
            push.noCounter());
        continue;
      }
      if (queued instanceof ProjectileAreaHitDue share) {
        DamageResult result =
            dealProjectileDamage(
                share.projectile(), share.victim(), share.damage(), share.hitId(), 0, 0);
        drainProjectileBuff(share.projectile(), share.victim(), result);
        continue;
      }
      if (queued instanceof ProjectileHitDue hit) {
        DamageResult result =
            dealProjectileDamage(
                hit.projectile(),
                hit.target(),
                hit.damage(),
                hit.hitId(),
                hit.directionX(),
                hit.directionY());
        drainProjectileBuff(hit.projectile(), hit.target(), result);
        continue;
      }
      if (queued instanceof TravellingHitDue hit) {
        DamageResult result =
            dealProjectileDamage(
                hit.projectile(),
                hit.target(),
                hit.damage(),
                hit.hitId(),
                hit.directionX(),
                hit.directionY());
        if (hit.deflection()) {
          drainProjectileBuff(hit.projectile(), hit.target(), result);
        }
        continue;
      }
      if (queued instanceof ActionDamageDue actionDamage) {
        drainActionDamage(actionDamage);
        continue;
      }
      if (queued instanceof BuffHitDue buffHit) {
        dealBuffDamageNow(buffHit);
        continue;
      }
      if (queued instanceof CaptureHitDue capture) {
        dealDamage(capture.owner(), capture.unit().getTargetView(), capture.damage(), 0, 0, true);
        continue;
      }
      if (queued instanceof CircleKillDue kill) {
        circleKillNow(kill.target(), kill.radius(), kill.amount());
        continue;
      }
      if (queued instanceof KamikazeKillDue kamikaze) {
        kamikazeKillNow(kamikaze.unit(), kamikaze.amount());
        continue;
      }
      if (queued instanceof KamikazeDrainDue drain) {
        kamikazeDrainNow(drain.unit(), drain.damage());
        continue;
      }
      if (queued instanceof ActionKillDue kill) {
        killNow(kill.target(), kill.killer(), kill.amount());
        continue;
      }
      if (queued instanceof ClearingKillDue kill) {
        clearingKillNow(kill.target(), kill.amount());
        continue;
      }
      TypedHit hit = (TypedHit) queued;
      WorldEntity target = hit.target();
      if (target.getHitPoints() == null) {
        continue;
      }
      if (hit.areaDamage() != null) {
        drainAreaDamage(hit);
        continue;
      }
      if (hit.areaSource() != null) {
        drainAreaTypedHit(hit);
        continue;
      }
      int amount = pipeline(hit);
      int damageId = hit.type().acquireDamageId() ? world.nextHitId() : 0;
      // A source that has left the battle kills as nothing does.
      WorldEntity source = hit.source() == null || hit.source().isLeft() ? null : hit.source();
      DamageResult result =
          target.takeTypedHit(source, amount, damageId, hit.directionX(), hit.directionY());
      // The death runs inside the hit, before the type's actions are scheduled.
      if (result.died()) {
        target.die(source);
      }
      // The game applies a buff on damage at the drain, so it applies the source's after a typed
      // hit it lets through as well, one after the battle's end included; no reference holds a
      // typed hit from such a source.
      if (result.accepted() && source != null && source.getData().mayBuffOnDamage()) {
        throw new UnsupportedOperationException(
            source.name() + " deals a typed hit with a BuffOnDamage, not modelled");
      }
      if (hit.source() != null && hit.type().actionOnSource() != null) {
        hit.source()
            .actionHolder()
            .schedule(
                hit.type().actionOnSource(), ActionHolder.OWN_DELAY, false, target.actionHolder());
      }
      if (hit.type().actionOnTarget() != null) {
        target
            .actionHolder()
            .schedule(
                hit.type().actionOnTarget(),
                ActionHolder.OWN_DELAY,
                false,
                hit.source() == null ? null : hit.source().actionHolder());
      }
      world.events().typedHitDealt(source, target, amount, damageId, result);
    }
    queuedHits.clear();
    queuedTotalStarts.clear();
  }

  /**
   * Deals a damage-taking action's hit: nothing to a target without hit points; nothing at all from
   * a target that takes no damage; otherwise the damage's amount for the target - its tower amount
   * against a crown tower when it gives one - plus the added amount, scaled by the source's level
   * unless NoScaling, floored at 0 behind the source's percentages (which no buff changes) unless
   * NoAmplification - an area effect and a projectile have none - then lowered by the target's
   * protection and floored at 0 unless NoProtection. The damage entry deals it with the source
   * counting it (an area effect or a projectile counts nothing), the target's runs told whether it
   * is a Reflected hit, the hidden test lifted under DamagesHidden; then the reflect of a
   * reflecting target, the action's cause its attacker - a character, building or tower is struck
   * back as at any hit, an area effect never - and the death the hit causes, at once. A source that
   * has left the battle is no source, as the game clears every reference to an object as it leaves:
   * a projectile's hit is dealt as one without a source, unscaled, with no percentages, lowered by
   * the target's protection unless NoProtection, and with no attacker; so is an area effect's that
   * has left while every hit is held, as from the tiebreaker's clearing on, where it lands nothing;
   * a hit of no amount from a character or an area effect that has left deals 0, and the level
   * scaling of one with an amount is otherwise refused. A source whose row sets a buff on damage,
   * which the drain applies after a hit it lets through, is refused.
   *
   * <p>The level scaling is the card damage scaling of the source's own row and level: a
   * character's, a building's or a tower's, an area effect's, or a projectile's.
   */
  private void drainActionDamage(ActionDamageDue due) {
    WorldEntity target = due.target();
    if (target.getHitPoints() == null) {
      return;
    }
    WorldEntity source = due.source() == null || due.source().isLeft() ? null : due.source();
    AreaEffectEntity areaSource =
        due.areaSource() == null || world.liveObject(due.areaSource().getId()) != due.areaSource()
            ? null
            : due.areaSource();
    ProjectileEntity projectileSource =
        due.projectileSource() == null
                || world.liveObject(due.projectileSource().getId()) != due.projectileSource()
            ? null
            : due.projectileSource();
    TakeDamage.Damage damage = due.damage();
    int amount = 0;
    if ((target.getView().getFlags() & target.getView().getFlagBits().noDamage()) == 0) {
      amount = damage.amount(target.getTargetView().isCrownTowerTarget()) + due.added();
      // A source that has left is none of the three here: the game's reference to it is cleared as
      // it leaves, so its hit takes no level scaling and no percentages, as a hit without a source.
      // A recorded battle holds that for a projectile source. An area effect leaves before its hit
      // is drained only as the tiebreaker's clearing removes it, and from then on every hit is
      // held, so such a hit lands nothing whatever its amount: recorded battles hold that. For a
      // character source, and for an area effect source while hits are not held, no recording
      // holds the step it leaves at against the game's, so a scaled hit with an amount from one
      // that has left stays refused (an amount of 0 scales to 0 at any level).
      if (damage.levelScaled()
          && amount != 0
          && ((source == null && due.source() != null)
              || (areaSource == null && due.areaSource() != null && !world.isHitsHeld()))) {
        throw new UnsupportedOperationException(
            "a damage-taking action's hit of an amount scaled by the level of a character or area"
                + " effect source no longer in the battle is not modelled");
      }
      if (damage.levelScaled() && source != null) {
        amount =
            LevelScaling.scale(
                ScalingGlobals.standard(),
                amount,
                source.getPackedLevel(),
                ScalingMode.CARD_DAMAGE,
                source.getData().rarity());
      } else if (damage.levelScaled() && areaSource != null) {
        amount =
            LevelScaling.scale(
                ScalingGlobals.standard(),
                amount,
                areaSource.getPackedLevel(),
                ScalingMode.CARD_DAMAGE,
                areaSource.getData().rarity());
      } else if (damage.levelScaled() && projectileSource != null) {
        // A projectile's own row and level, as its own damage is scaled; it carries no buffs, so
        // no percentages follow.
        amount =
            LevelScaling.scale(
                ScalingGlobals.standard(),
                amount,
                projectileSource.packedLevel(),
                ScalingMode.CARD_DAMAGE,
                projectileSource.getData().rarity());
      }
      if (source != null && !damage.noAmplification()) {
        refuseTypedPercents(source, "a damage-taking action's hit");
        amount = Math.max(amount, 0);
      }
      if (!damage.noProtection()) {
        amount = Math.max(target.getBuffs().damageReduction(amount), 0);
      }
    }
    int before = hitPointsOf(target);
    // The hit's cause: the area effect, the projectile or the character, building or tower.
    SpawnHost cause =
        areaSource != null ? areaSource : projectileSource != null ? projectileSource : source;
    DamageResult result =
        target.takeActionDamage(
            source,
            cause,
            amount,
            damage.reflected(),
            damage.damagesHidden(),
            due.directionX(),
            due.directionY());
    // The action's cause is the hit's attacker: a reflecting target strikes back at a character,
    // building or tower that caused it, as at any hit, and at nothing for an area effect's.
    BattleEntity attacker =
        areaSource != null ? areaSource : projectileSource != null ? projectileSource : source;
    reflect(target, attacker, before, result, due.directionX(), due.directionY());
    if (result.died()) {
      target.die(attacker);
    }
    if (result.accepted() && source != null && source.getData().mayBuffOnDamage()) {
      throw new UnsupportedOperationException(
          source.name() + " deals a damage-taking action's hit with a BuffOnDamage, not modelled");
    }
    world.events().typedHitDealt(source, target, amount, 0, result);
  }

  /**
   * Deals a typed hit an area effect queued: the type's pipeline, in which the area effect, which
   * carries no buffs, leaves the source's multiplier out; a damage id when the type takes one; the
   * typed hit's entry, which no source counts and whose shield break names the area effect as its
   * cause; the reflect of a reflecting target, the area effect its attacker, which strikes nothing
   * back; the death the area effect caused; and the type's action on the target, the area effect
   * its cause. A type that scales by the source's level, whose level an area effect source would
   * give, and one with an action on the source are refused.
   */
  private void drainAreaTypedHit(TypedHit hit) {
    WorldEntity target = hit.target();
    AreaEffectEntity source = hit.areaSource();
    DamageType type = hit.type();
    if (type.enableLevelScaling() || type.actionOnSource() != null) {
      throw new UnsupportedOperationException(
          type.name()
              + " of a typed hit from "
              + source.getData().name()
              + " scales by its source's level or runs an action on its source, not modelled");
    }
    boolean noDamage =
        (target.getView().getFlags() & target.getView().getFlagBits().noDamage()) != 0;
    int amount =
        type.pipeline(hit.amount(), noDamage, false, null, 0, target.getBuffs()::damageReduction);
    int damageId = type.acquireDamageId() ? world.nextHitId() : 0;
    int before = hitPointsOf(target);
    DamageResult result = target.takeTypedHit(null, source, amount, damageId, 0, 0);
    // The area effect is the hit's attacker: a reflecting target's reflect strikes nothing back.
    reflect(target, source, before, result, 0, 0);
    if (result.died()) {
      target.die(source);
    }
    if (type.actionOnTarget() != null) {
      target
          .actionHolder()
          .schedule(type.actionOnTarget(), ActionHolder.OWN_DELAY, false, source.actionHolder());
    }
    world.events().typedHitDealt(null, target, amount, damageId, result);
  }

  /**
   * Deals the damage a filter form area effect queued: nothing to a target that takes no damage;
   * otherwise the type's amount for the target - its tower amount for a crown tower when it gives
   * one, its base amount else - scaled by the area effect's level against its rarity as card
   * damage, then lowered by the target's protection and floored at 0. An amount of 0 is dealt as
   * nothing at all; any other, with no damage id, through the typed hit's entry, which lowers it by
   * the target's protection once more and floors it at 1, which no source counts and whose shield
   * break names the area effect as its cause; the reflect of a reflecting target, the area effect
   * its attacker, which strikes nothing back; and the death the area effect caused.
   */
  private void drainAreaDamage(TypedHit hit) {
    WorldEntity target = hit.target();
    AreaEffectEntity source = hit.areaSource();
    if ((target.getView().getFlags() & target.getView().getFlagBits().noDamage()) != 0) {
      return;
    }
    int amount =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            hit.areaDamage().amount(target.getTargetView().crownTower()),
            source.getPackedLevel(),
            ScalingMode.CARD_DAMAGE,
            source.getData().rarity());
    amount = Math.max(target.getBuffs().damageReduction(amount), 0);
    if (amount == 0) {
      return;
    }
    int before = hitPointsOf(target);
    DamageResult result =
        target.takeTypedHit(null, source, amount, 0, hit.directionX(), hit.directionY());
    // The area effect is the hit's attacker: a reflecting target's reflect strikes nothing back.
    reflect(target, source, before, result, hit.directionX(), hit.directionY());
    if (result.died()) {
      target.die(source);
    }
    world.events().typedHitDealt(null, target, amount, 0, result);
  }

  /**
   * A typed hit's amount after its type's pipeline. The level scaling reads the source's own rarity
   * row and packed level while the source is in the battle; once it has left, the level it had and
   * the Common row, and the multiplier no longer counts it as a source.
   */
  private static int pipeline(TypedHit hit) {
    WorldEntity source = hit.source();
    boolean noDamage =
        (hit.target().getView().getFlags() & hit.target().getView().getFlagBits().noDamage()) != 0;
    if (source == null) {
      if (hit.type().enableLevelScaling()) {
        throw new UnsupportedOperationException(
            "the level of a typed hit without a source is not established; "
                + hit.type().name()
                + " asks for it");
      }
      return hit.type()
          .pipeline(
              hit.amount(), noDamage, false, null, 0, hit.target().getBuffs()::damageReduction);
    }
    boolean present = !source.isLeft();
    if (present && hit.type().enableDamageMultiplier()) {
      refuseTypedPercents(source, "a typed hit");
    }
    RarityTable rarity = present ? source.getData().rarity() : RarityTable.COMMON;
    return hit.type()
        .pipeline(
            hit.amount(),
            noDamage,
            present,
            rarity,
            source.getPackedLevel(),
            hit.target().getBuffs()::damageReduction);
  }

  /**
   * Refuses a typed hit, or a damage-taking action's hit, whose source carries a buff percent other
   * than 100: the game scales such a hit by the source's percents in its type's stage, floored at
   * 0, and once more at the damage entry, floored at 1, which the typed hit's entry here does not
   * model; no reference holds such a hit.
   */
  private static void refuseTypedPercents(WorldEntity source, String hit) {
    if (source.getBuffs().damagePercent() != 100
        || source.getBuffs().crownTowerDamagePercent() != 100) {
      throw new UnsupportedOperationException(
          hit + " from " + source.name() + ", whose buffs scale its damage, is not modelled");
    }
  }

  /**
   * A capture's hit on a unit it holds: the damage entry with no hit id and no dedupe id, the
   * capturing character the attacker, passing the hidden unit. The entry queues it for the damage
   * drain, after the tick's post-hooks, as it queues a direct hit (see {@link #queuedHits}); a hit
   * that kills runs the unit's death there, the capturing character its killer, so the unit's death
   * slot runs - its death damage, its death spawn, a building included, and its area effect - and
   * its death handler after it.
   *
   * @param owner the capturing character
   * @param unit the unit held
   * @param damage the damage per hit at the owner's level
   */
  public void captureHit(CharacterEntity owner, WorldEntity unit, int damage) {
    queuedHits.add(new CaptureHitDue(owner, unit, damage));
  }

  /**
   * A queued direct hit's buff on damage, as the drain deals the hit: applied right after the
   * damage, the hit's death and its reflect, when the damage entry let the hit through (the drain
   * applies it only for a record its bookkeeping accepted), a hit that kills included; nothing for
   * an attacker whose row sets none, or a target that has left the battle. The bookkeeping accepts
   * a hit after the battle's end too, which takes nothing; the drain would apply the buff on damage
   * of such a hit as well, which no reference holds, so it is refused. A hit with no attacker
   * carries none.
   *
   * <p>The buff and its time are read here, at the drain, from the attacker as it is then: its
   * attack sequence entry's CustomBuffOnDamage and CustomBuffOnDamageTime where it has them (see
   * {@link WorldEntity#buffOnDamageAtDrain()}), else its row's. No buff, or a time of 0, applies
   * nothing.
   *
   * @param attacker the entity whose hit it is, or null for none
   * @param target the view the hit resolved against
   * @param result what the hit's damage did
   */
  private void drainBuffOnDamage(WorldEntity attacker, TargetView target, DamageResult result) {
    if (!result.accepted()
        || attacker == null
        || attacker.buffOnDamageAtDrain() == null
        || attacker.buffOnDamageTimeAtDrain() == 0) {
      return;
    }
    if (!result.landed()) {
      throw new UnsupportedOperationException(
          attacker.name()
              + " lands a direct hit with a BuffOnDamage after the battle's end, which is not"
              + " modelled");
    }
    WorldEntity entity = world.entityOf(target.getEntity());
    if (entity != null) {
      buffOnDamage(attacker, entity);
    }
  }

  /**
   * A hit's buff on damage on what it reached, after its direct hit: nothing for a target
   * untouchable at that moment, the immunity left after a dash counted; otherwise applied with the
   * attacker as the source, at its level and for its side, for its BuffOnDamageTime. The buff and
   * the time are the attacker's attack sequence entry's where it has them, else its row's (see
   * {@link WorldEntity#buffOnDamageAtDrain()}). There is no alive test, so a target the hit has
   * just killed takes it too and leaves at the tick's closing cleanup; the buff decides for itself
   * whether the target takes it. A second hit, of the same attack or another attacker, refreshes
   * the one instance.
   *
   * @param attacker the entity whose hit it is
   * @param target what the hit reached
   */
  void buffOnDamage(WorldEntity attacker, WorldEntity target) {
    UnitData data = attacker.getData();
    BuffData buff = world.buffData(attacker.buffOnDamageAtDrain());
    // A hit applies its buff with the attacker as its parent, which a stacking buff keeps: its
    // instance would leave with the attacker and bar another from the same parent. No row does.
    if (buff.enableStacking()) {
      throw new UnsupportedOperationException(
          data.name() + " applies a stacking BuffOnDamage kept with its parent, not modelled");
    }
    if (!target.untouchable()) {
      target
          .getBuffs()
          .apply(
              buff,
              attacker.buffOnDamageTimeAtDrain(),
              attacker.getPackedLevel(),
              attacker,
              attacker.side());
    }
  }

  /**
   * A queued projectile hit's target buff, as the drain deals the hit: right after the damage, the
   * hit's death and its reflect, to the victim of that one hit, for a projectile whose row sets a
   * target buff and does not apply it before the damage, when the damage entry let the hit through
   * - a hit that kills included, and one after the battle's end, which the bookkeeping accepts
   * though it takes nothing. It reaches the victims of an area impact one by one, as the drain
   * deals their shares, so only what the area damaged takes it. A deflection's hit on the
   * deflector's parent takes it too: the projectile is that hit's source, though it flies on to its
   * new target, so the Monk takes the slow or the stun of a shot it turns. Applied with the
   * projectile as the source, at its level and for its side, for the row's buff time at that level;
   * the drain asks nothing of the victim's dash immunity, which the damage entry has answered
   * already.
   *
   * <p>Being applied after every post-hook of the tick, the buff is first read by the victim's
   * combat gate on the next tick: a stun leaves the victim its targeting visit of that tick, under
   * a hit speed scaled to nothing, and drops its reference at that tick's gate.
   *
   * @param projectile the projectile whose hit it is
   * @param victim the entity the hit reached
   * @param result what the hit's damage did
   */
  private void drainProjectileBuff(
      ProjectileEntity projectile, WorldEntity victim, DamageResult result) {
    ProjectileData data = projectile.getData();
    if (!result.accepted() || data.targetBuff() == null || data.applyBuffBeforeDamage()) {
      return;
    }
    if (world.entityOf(victim.getView()) != victim) {
      return;
    }
    BuffData buff = world.buffData(data.targetBuff());
    int time = data.buffTime(projectile.getPackedLevel());
    world.events().projectileBuff(projectile, buff, time, List.of(victim));
    victim.getBuffs().apply(buff, time, projectile.getPackedLevel(), projectile, projectile.side());
  }

  /**
   * Queues the hit a deflection deals the deflector's parent: the projectile's own damage as a
   * travelling hit without a direction, the projectile its source, so the drain follows it with the
   * projectile's after-damage target buff as it does an impact's.
   */
  void queueDeflectedHit(ProjectileEntity projectile, WorldEntity parent, int damage, int hitId) {
    queuedHits.add(new TravellingHitDue(projectile, parent, damage, hitId, 0, 0, true));
  }

  /**
   * Queues one hit of a buff's damage over time for the damage drain, as the game's damage entry
   * queues every hit it is handed, the buff pass's included: the carrier takes it, and dies of it,
   * at the drain of the tick, after the post-hooks (see {@link #queuedHits}). The source and the
   * side are the instance's as the hit is queued; no object leaves the battle between the buff pass
   * and the drain.
   *
   * @param target the entity carrying the buff
   * @param buff the instance whose damage it is
   * @param damage hit points it deals, after the carrier's own damage reduction
   */
  void dealBuffDamage(WorldEntity target, BuffInstance buff, int damage) {
    queuedHits.add(new BuffHitDue(target, buff, damage, buff.getSource(), buff.getSide()));
  }

  /**
   * Deals one hit of a buff's damage over time at the drain, tells the observers, and runs the
   * death it causes. The game hands the hit-points chain the buff's source as the instance held it
   * when the hit was queued, as it hands a direct hit its attacker: the source is what killed the
   * carrier, for the side the buff was applied for, so its killer's hook and the carrier's killed
   * action see the source (see {@link EntityDeaths#entityDied}); a hit that lands and does not kill
   * reaches the source's reference drop as any other hit does (see {@link #referenceDrop}). A buff
   * with no source - one applied with none, or one whose source has left the battle, which it then
   * forgets as the game clears it - kills with nothing as what killed it, for its side, so the
   * killed action's cause is that side alone (see {@link EntityDeaths#entityDied}).
   *
   * @param due the queued hit
   */
  private void dealBuffDamageNow(BuffHitDue due) {
    WorldEntity target = due.target();
    if (target.getHitPoints() == null) {
      return;
    }
    int before = target.getHitPoints().getHitPoints();
    // The damage of a buff comes from its source: an area effect, which is never struck back, or a
    // character, whose buff on a reflecting unit is not modelled.
    SpawnHost source = due.source();
    if (target.getData().reflectedAttackBuff() != null && source instanceof WorldEntity) {
      throw new UnsupportedOperationException(
          target.name() + " reflects and takes a buff's damage from a character, not modelled");
    }
    DamageResult result = target.takeDamageOverTime(due.damage(), source);
    reflect(target, (BattleEntity) source, before, result, 0, 0);
    world.events().buffDamaged(target, due.buff(), due.damage(), before, result);
    // Every object a buff's source can be is a battle entity; with no source, nothing.
    BattleEntity attacker = (BattleEntity) source;
    if (result.died()) {
      // The killing side is the one the buff was applied for.
      target.die(attacker, due.side());
    } else if (result.landed()) {
      referenceDrop(attacker, target, false);
    }
  }

  /**
   * Gives one heal of a buff's heal over time to its entity, capped by the buff's over-heal share,
   * and tells the observers.
   */
  void dealBuffHeal(WorldEntity target, BuffInstance buff, int heal) {
    int before = target.getHitPoints().getHitPoints();
    target.takeHeal(heal, buff.getBuff().allowedOverHealPercent());
    world.events().buffHealed(target, buff, heal, before);
  }

  /**
   * Deals one victim its share of an area effect's hit, tells the observers, and runs the death it
   * causes, with the area effect as what killed it. A victim that has left takes nothing.
   */
  DamageResult dealAreaEffectDamage(AreaEffectEntity areaEffect, WorldEntity victim, int damage) {
    if (victim == null || world.entityOf(victim.getView()) != victim) {
      return DamageResult.NOTHING;
    }
    // The damage entry lets the hit of an area effect that reaches hidden units through while its
    // victim is hidden.
    int before = hitPointsOf(victim);
    DamageResult result =
        victim.takeDamage(damage, 0, 0, 0, areaEffect.getData().affectsHidden(), null, areaEffect);
    reflect(victim, areaEffect, before, result, 0, 0);
    world.events().areaEffectHit(areaEffect, victim, damage, result);
    if (result.died()) {
      victim.die(areaEffect);
    }
    return result;
  }
}
