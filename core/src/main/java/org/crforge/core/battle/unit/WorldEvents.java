/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Berserk;
import org.crforge.core.battle.action.BossBanditAbility;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.action.ShapeSelector;
import org.crforge.core.battle.action.SpawnGuard;
import org.crforge.core.battle.action.TargetIndicatorAttack;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * What the world tells its observers: every event of the arena, at the battle tick of the entity
 * tick in progress, to each observer in the order it attached. The events carry nothing of the
 * battle's own state; an observer that is not attached costs the tick nothing.
 */
public final class WorldEvents {

  private final BattleWorld world;

  /** The observers, in the order they attached. */
  private final List<WorldObserver> observers = new ArrayList<>();

  WorldEvents(BattleWorld world) {
    this.world = world;
  }

  /** Attaches an observer; it is told about every event from the next one on. */
  void add(WorldObserver observer) {
    observers.add(observer);
  }

  /** Whether any observer is attached: what a snapshot taken only for them can be spared by. */
  boolean listening() {
    return !observers.isEmpty();
  }

  /** The battle tick of the entity tick in progress. */
  private int tick() {
    return world.tick();
  }

  /** Tells every observer of a special load a unit's targeting visit armed. */
  void specialArmed(
      CharacterEntity unit,
      WorldEntity reference,
      long distanceSquared,
      int ringMin,
      int ringMax,
      int loadMs,
      int afterMs) {
    for (WorldObserver observer : observers) {
      observer.specialArmed(
          tick(), unit, reference, distanceSquared, ringMin, ringMax, loadMs, afterMs);
    }
  }

  /** Tells every observer that a unit's targeting forgot the projectile it was held on. */
  void holdLeft(WorldEntity unit, ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.holdLeft(tick(), unit, projectile);
    }
  }

  /** Tells every observer that a unit forgot the hooking projectile it followed. */
  void followLeft(WorldEntity unit, ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.followLeft(tick(), unit, projectile);
    }
  }

  void reflected(Reflection reflection) {
    for (WorldObserver observer : observers) {
      observer.reflected(tick(), reflection);
    }
  }

  /** Tells every observer a card play made the unit, before the play starts it. */
  void characterPlayed(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.characterPlayed(tick(), unit);
    }
  }

  /** Tells every observer a unit's ability was requested, and whether it is cast at once. */
  void abilityRequested(CharacterEntity unit, boolean now) {
    for (WorldObserver observer : observers) {
      observer.abilityRequested(tick(), unit, now);
    }
  }

  /** Tells every observer a unit's ability fired. */
  void abilityFired(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.abilityFired(tick(), unit);
    }
  }

  /**
   * Tells every observer a Kamikaze unit's hit ended, before the kill it may run.
   *
   * @param unit the unit
   * @param kills true when the end kills it
   */
  void kamikazeHitEnded(WorldEntity unit, boolean kills) {
    for (WorldObserver observer : observers) {
      observer.kamikazeHitEnded(tick(), unit, kills);
    }
  }

  /** Tells every observer a unit asked to push itself back after a launch. */
  void pushbackRequested(
      WorldEntity unit, boolean started, int fromX, int fromY, MovementState pushback) {
    for (WorldObserver observer : observers) {
      observer.pushbackRequested(tick(), unit, started, fromX, fromY, pushback);
    }
  }

  /** Tells every observer a unit's pushback visit moved it off a cell it may not stand on. */
  void relocated(WorldEntity unit, int x, int y, int toX, int toY) {
    for (WorldObserver observer : observers) {
      observer.relocated(tick(), unit, x, y, toX, toY);
    }
  }

  /** Tells every observer what the area of an entity's hit did, once its victims are dealt. */
  void areaDamaged(WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
    for (WorldObserver observer : observers) {
      observer.areaDamaged(tick(), owner, area, outcome);
    }
  }

  /**
   * Tells every observer an elixir collector paid its king.
   *
   * @param collector the collector
   * @param amount the whole elixir it paid
   */
  void elixirCollected(WorldEntity collector, int amount) {
    for (WorldObserver observer : observers) {
      observer.elixirCollected(tick(), collector, collector.side(), amount);
    }
  }

  /** Tells every observer that a hiding building's deploy end ran its targeting visit. */
  void deployEndVisited(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.deployEndVisited(tick(), unit);
    }
  }

  /** Tells every observer of one visit of a hiding building's hide handler. */
  void hideVisited(
      CharacterEntity unit, int state, int before, int after, int step, List<String> effects) {
    for (WorldObserver observer : observers) {
      observer.hideVisited(tick(), unit, state, before, after, step, List.copyOf(effects));
    }
  }

  /** Tells every observer that a hiding building scheduled one of its hide handler's actions. */
  void hidingHookScheduled(CharacterEntity unit, String column, String action) {
    for (WorldObserver observer : observers) {
      observer.hidingHookScheduled(tick(), unit, column, action);
    }
  }

  /** Tells every observer of one step of a king tower's activation. */
  void activation(TowerEntity king, ActivationEvent event) {
    for (WorldObserver observer : observers) {
      observer.activation(tick(), king, event);
    }
  }

  /** Tells the observers a unit was linked into its card's group chain. */
  void chainLinked(CharacterEntity unit, CharacterEntity after) {
    for (WorldObserver observer : observers) {
      observer.chainLinked(tick(), unit, after);
    }
  }

  /** Tells the observers a unit left its card's group chain. */
  void chainUnlinked(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.chainUnlinked(tick(), unit);
    }
  }

  /** Tells the observers a run of Goblinstein's ability started on an area effect. */
  void goblinsteinStarted(AreaEffectEntity owner, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinStarted(tick(), owner, action, phase);
    }
  }

  /** Tells the observers a run of Goblinstein's ability connected, to nothing for null. */
  void goblinsteinConnected(AreaEffectEntity owner, BattleEntity connected) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinConnected(tick(), owner, connected);
    }
  }

  /** Tells the observers a run of Goblinstein's ability made its death area. */
  void goblinsteinDeathAreaMade(
      AreaEffectEntity owner, WorldEntity left, AreaEffectEntity deathArea, int x, int y) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinDeathAreaMade(tick(), owner, left, deathArea, x, y);
    }
  }

  /** Tells the observers a run of Goblinstein's ability ended its death area. */
  void goblinsteinDeathAreaEnded(AreaEffectEntity owner, AreaEffectEntity deathArea) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinDeathAreaEnded(tick(), owner, deathArea);
    }
  }

  /** Tells the observers a run of Goblinstein's ability saw a cast, its end, or ended a tether. */
  void goblinsteinStepped(AreaEffectEntity owner, String step) {
    for (WorldObserver observer : observers) {
      observer.goblinsteinStepped(tick(), owner, step);
    }
  }

  /** Tells the observers a tether's damage pass ran its query. */
  void tetherDamagePass(
      AreaEffectEntity owner, int ax, int ay, int bx, int by, List<WorldEntity> found) {
    for (WorldObserver observer : observers) {
      observer.tetherDamagePass(tick(), owner, ax, ay, bx, by, found);
    }
  }

  /** Tells the observers a champion slot stepped in its king's run pass. */
  void championStepped(ChampionController slot, int elixir, List<ChampionView> views) {
    for (WorldObserver observer : observers) {
      observer.championStepped(tick(), slot, elixir, views);
    }
  }

  /** Tells the observers the deck pass gave a champion slot its champion. */
  void championDeckPass(ChampionController slot) {
    for (WorldObserver observer : observers) {
      observer.championDeckPass(tick(), slot);
    }
  }

  /** Tells the observers a champion slot followed a card play. */
  void championFollowed(ChampionController slot, String play) {
    for (WorldObserver observer : observers) {
      observer.championFollowed(tick(), slot, play);
    }
  }

  /** Tells the observers a champion slot heard a paid ability and requested its live copies'. */
  void championActivated(ChampionController slot, List<CharacterEntity> requested) {
    for (WorldObserver observer : observers) {
      observer.championActivated(tick(), slot, requested);
    }
  }

  /** Tells the observers a champion slot's cooldown ran out. */
  void championCooldownOut(ChampionController slot) {
    for (WorldObserver observer : observers) {
      observer.championCooldownOut(tick(), slot);
    }
  }

  /** Tells the observers a unit whose dashes chain started a dash, with its chain's bookkeeping. */
  void chainDashStarted(
      CharacterEntity unit, int fromX, int fromY, int aimX, int aimY, int radius) {
    for (WorldObserver observer : observers) {
      observer.chainDashStarted(tick(), unit, fromX, fromY, aimX, aimY, radius);
    }
  }

  /** Tells the observers a chain found its next target as the unit left its dash. */
  void chainDashed(CharacterEntity unit, WorldEntity next, int count, int x, int y) {
    for (WorldObserver observer : observers) {
      observer.chainDashed(tick(), unit, next, count, x, y);
    }
  }

  /** Tells the observers a chain ended as the unit left its dash. */
  void chainDashEnded(CharacterEntity unit, int count, TargetView reference) {
    for (WorldObserver observer : observers) {
      observer.chainDashEnded(tick(), unit, count, reference);
    }
  }

  /** Tells the observers an ability gave its unit its buff. */
  void abilityBuffed(CharacterEntity unit, String buff, int timeMs, int packedLevel) {
    for (WorldObserver observer : observers) {
      observer.abilityBuffed(tick(), unit, buff, timeMs, packedLevel);
    }
  }

  /** Tells the observers a child was linked into its source's group. */
  void groupLinked(CharacterEntity source, CharacterEntity child) {
    for (WorldObserver observer : observers) {
      observer.groupLinked(tick(), source, child);
    }
  }

  void destroyedAtLimit(CharacterEntity spawner) {
    for (WorldObserver observer : observers) {
      observer.destroyedAtLimit(tick(), spawner);
    }
  }

  /** Tells the observers the combat gate dropped an entity's reference. */
  void combatGateDropped(WorldEntity entity, TargetView reference, int hitSpeed) {
    for (WorldObserver observer : observers) {
      observer.combatGateDropped(tick(), entity, reference, hitSpeed);
    }
  }

  /** Tells the observers the combat gate switched a stunned entity's targeting off or on. */
  void combatComponentSwitched(WorldEntity entity, boolean on, int hitSpeed) {
    for (WorldObserver observer : observers) {
      observer.combatComponentSwitched(tick(), entity, on, hitSpeed);
    }
  }

  /** Tells the observers a hit met an entity's shield. */
  void shieldHit(WorldEntity target, int damage, int before, int after) {
    for (WorldObserver observer : observers) {
      observer.shieldHit(tick(), target, damage, before, after);
    }
  }

  /**
   * Tells the observers a listed buff that gives a charge range reset a character's charge.
   *
   * @param unit the character
   * @param instance the instance just listed
   * @param before the charge progress before the reset
   * @param after the charge progress after it
   */
  void buffChargeReset(CharacterEntity unit, BuffInstance instance, int before, int after) {
    for (WorldObserver observer : observers) {
      observer.buffChargeReset(tick(), unit, instance, before, after);
    }
  }

  /**
   * Tells every observer that a unit started a dash.
   *
   * @param unit the dashing unit
   * @param reference what it dashed at
   * @param fromX where it stood, along the width
   * @param fromY where it stood, along the length
   * @param aimX the point it dashed toward, along the width
   * @param aimY the point it dashed toward, along the length
   */
  void dashStarted(
      CharacterEntity unit, TargetView reference, int fromX, int fromY, int aimX, int aimY) {
    for (WorldObserver observer : observers) {
      observer.dashStarted(tick(), unit, reference, fromX, fromY, aimX, aimY);
    }
  }

  /**
   * Tells every observer that a unit landed its dash.
   *
   * @param unit the unit
   * @param hit what its landing hit: the target of a single hit, or null for an area or nothing
   * @param damage the landing's damage, or 0 when nothing was hit
   * @param area true when the landing hit an area
   */
  void dashLanded(CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
    for (WorldObserver observer : observers) {
      observer.dashLanded(tick(), unit, hit, damage, area);
    }
  }

  /** Tells every observer that a unit's charge completed. */
  void chargeCompleted(CharacterEntity unit, int progress) {
    for (WorldObserver observer : observers) {
      observer.chargeCompleted(tick(), unit, progress);
    }
  }

  /** Tells every observer that a unit fully charged at its last movement visit is no longer. */
  void chargeLost(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.chargeLost(tick(), unit);
    }
  }

  /** Tells every observer that a unit's movement pass had its state changed. */
  void movementStateRequested(CharacterEntity unit, int from, int to) {
    for (WorldObserver observer : observers) {
      observer.movementStateRequested(tick(), unit, from, to);
    }
  }

  void spawnerFired(
      CharacterEntity spawner, String row, int count, int radius, int timerAfter, int waveMade) {
    for (WorldObserver observer : observers) {
      observer.spawnerFired(tick(), spawner, row, count, radius, timerAfter, waveMade);
    }
  }

  /** Tells the observers a unit counted a soul for a death. */
  void soulCounted(CharacterEntity unit, WorldEntity dying, int souls) {
    for (WorldObserver observer : observers) {
      observer.soulCounted(tick(), unit, dying, souls);
    }
  }

  /** A guard-spawning run's first step on an area effect, which finishes it. */
  void guardFirstStepped(AreaEffectEntity areaEffect, SpawnGuard action) {
    for (WorldObserver observer : observers) {
      observer.guardFirstStepped(tick(), areaEffect, action.name());
    }
  }

  /** A step of a guard's run. */
  void guardStepped(
      CharacterEntity guard, boolean charging, long tags, boolean done, List<String> calls) {
    for (WorldObserver observer : observers) {
      observer.guardStepped(tick(), guard, charging, tags, done, calls);
    }
  }

  /** A Boss Bandit ability's run started on a unit. */
  void bossBanditAbilityStarted(
      CharacterEntity unit,
      BossBanditAbility action,
      int phase,
      int warpTick,
      int lockTick,
      List<Boolean> requests) {
    for (WorldObserver observer : observers) {
      observer.bossBanditAbilityStarted(
          tick(), unit, action.name(), phase, warpTick, lockTick, requests);
    }
  }

  /** A step of a Boss Bandit ability's run that changed it or asked for its lock again. */
  void bossBanditAbilityStepped(
      CharacterEntity unit, boolean locked, int releaseMs, List<String> calls) {
    for (WorldObserver observer : observers) {
      observer.bossBanditAbilityStepped(tick(), unit, locked, releaseMs, calls);
    }
  }

  /** Tells the observers the hold, layer and contact tags of an entity's word changed. */
  void tagWordChanged(WorldEntity entity, long word) {
    for (WorldObserver observer : observers) {
      observer.tagWordChanged(tick(), entity, word);
    }
  }

  /** Tells the observers an uppercut started on a unit. */
  void uppercutStarted(
      CharacterEntity unit,
      String action,
      int phase,
      WorldEntity instigator,
      WorldEntity target,
      boolean finished) {
    for (WorldObserver observer : observers) {
      observer.uppercutStarted(tick(), unit, action, phase, instigator, target, finished);
    }
  }

  /** Tells the observers an uppercut's update changed its delay, pushed or finished. */
  void uppercutStepped(
      CharacterEntity unit, int delay, boolean finished, String outcome, int[] pushPoint) {
    for (WorldObserver observer : observers) {
      observer.uppercutStepped(tick(), unit, delay, finished, outcome, pushPoint);
    }
  }

  /**
   * Tells the observers an uppercut marked its target, or a taunt its forced object, in the unit's
   * targeting queue.
   */
  void uppercutMarked(CharacterEntity unit, WorldEntity target, int priority) {
    for (WorldObserver observer : observers) {
      observer.uppercutMarked(tick(), unit, target, priority, unit.currentTarget());
    }
  }

  /** Tells the observers a unit's pre-hook emptied its targeting queue. */
  void targetQueueFlushed(CharacterEntity unit) {
    for (WorldObserver observer : observers) {
      observer.targetQueueFlushed(tick(), unit);
    }
  }

  /** Tells the observers an uppercut's target left the battle. */
  void uppercutTargetLeft(CharacterEntity unit, WorldEntity target) {
    for (WorldObserver observer : observers) {
      observer.uppercutTargetLeft(tick(), unit, target);
    }
  }

  /** Tells the observers a knock started on a unit. */
  void knockbackStarted(
      CharacterEntity unit, String action, int phase, WorldEntity instigator, int counter) {
    for (WorldObserver observer : observers) {
      observer.knockbackStarted(tick(), unit, action, phase, instigator, counter);
    }
  }

  /** Tells the observers a knock's update ran. */
  void knockbackStepped(
      CharacterEntity unit, int before, int after, int height, long tags, boolean finished) {
    for (WorldObserver observer : observers) {
      observer.knockbackStepped(tick(), unit, before, after, height, tags, finished);
    }
  }

  /** Tells the observers a resetable action made its area effect. */
  void resetableStarted(
      CharacterEntity unit,
      int phase,
      WorldEntity instigator,
      AreaEffectEntity areaEffect,
      int x,
      int y) {
    for (WorldObserver observer : observers) {
      observer.resetableStarted(tick(), unit, phase, instigator, areaEffect, x, y);
    }
  }

  /** Tells the observers a resetable action's run ended after its area effect left. */
  void resetableEnded(CharacterEntity unit, String areaEffect) {
    for (WorldObserver observer : observers) {
      observer.resetableEnded(tick(), unit, areaEffect);
    }
  }

  /**
   * Tells the observers a resetable action's singleton row was started again: the countdown its
   * area effect got back, or null with none live.
   */
  void resetableRetriggered(CharacterEntity unit, int phase, String areaEffect, Integer countdown) {
    for (WorldObserver observer : observers) {
      observer.resetableRetriggered(tick(), unit, phase, areaEffect, countdown);
    }
  }

  /** Tells the observers a resetable action's area effect left. */
  void resetableLeft(CharacterEntity unit, String areaEffect) {
    for (WorldObserver observer : observers) {
      observer.resetableLeft(tick(), unit, areaEffect);
    }
  }

  /** Tells the observers a resetable action's area effect had its life cut as the unit left. */
  void resetableReleased(CharacterEntity unit, String areaEffect, int before, int after) {
    for (WorldObserver observer : observers) {
      observer.resetableReleased(tick(), unit, areaEffect, before, after);
    }
  }

  /** Tells the observers what a shaped area effect listed. */
  void shapeListed(AreaEffectEntity areaEffect, List<WorldEntity> listed) {
    for (WorldObserver observer : observers) {
      observer.shapeListed(tick(), areaEffect, listed);
    }
  }

  /** Tells the observers a choice by team ran on an entity. */
  void filteredByTeam(
      WorldEntity unit, String action, SpawnHost instigator, boolean sameTeam, String chosen) {
    for (WorldObserver observer : observers) {
      observer.filteredByTeam(tick(), unit, action, instigator, sameTeam, chosen);
    }
  }

  /** Tells the observers a run that waited for its cause to leave scheduled its action. */
  void instigatorGone(WorldEntity unit, String action, String scheduled) {
    for (WorldObserver observer : observers) {
      observer.instigatorGone(tick(), unit, action, scheduled);
    }
  }

  /** Tells the observers an action run at an age was scheduled on an area effect. */
  void aliveTimerFired(AreaEffectEntity areaEffect, String action) {
    for (WorldObserver observer : observers) {
      observer.aliveTimerFired(tick(), areaEffect, action);
    }
  }

  /**
   * Tells the observers a unit's ability spent its souls on its area effect, whose lifetime they
   * set.
   */
  void soulsSpent(
      CharacterEntity unit, AreaEffectEntity areaEffect, int souls, int count, int lifetimeMs) {
    for (WorldObserver observer : observers) {
      observer.soulsSpent(tick(), unit, areaEffect, souls, count, lifetimeMs);
    }
  }

  /** Tells the observers a unit's ability held it in its follow-up state. */
  void abilityStateEntered(CharacterEntity unit, int countdown) {
    for (WorldObserver observer : observers) {
      observer.abilityStateEntered(tick(), unit, countdown);
    }
  }

  /** Tells the observers a unit's ability sent it across the arena. */
  void lanesSwitched(
      CharacterEntity unit, int mirroredX, int mirroredY, int toX, int toY, TargetView reference) {
    for (WorldObserver observer : observers) {
      observer.lanesSwitched(tick(), unit, mirroredX, mirroredY, toX, toY, reference);
    }
  }

  /** Tells the observers what a check of an action's cause found. */
  void instigatorChecked(
      WorldEntity owner, String action, ActionOwner instigator, String scheduled) {
    for (WorldObserver observer : observers) {
      observer.instigatorChecked(tick(), owner, action, instigator, scheduled);
    }
  }

  /** Tells the observers what a Berserker's run did to its unit's attack sequence index. */
  void berserked(WorldEntity unit, Berserk.Event event, int before, int index) {
    for (WorldObserver observer : observers) {
      observer.berserked(tick(), unit, event, before, index);
    }
  }

  /** Tells the observers what a Goblin Hut's life state did. */
  void goblinHutLogged(CharacterEntity hut, GoblinHutLifeState.Event event) {
    for (WorldObserver observer : observers) {
      observer.goblinHutLogged(tick(), hut, event);
    }
  }

  /**
   * Tells every observer that a projectile admitted to the battle scheduled its starting action.
   */
  public void projectileStarting(ProjectileEntity projectile, String action) {
    for (WorldObserver observer : observers) {
      observer.projectileStarting(tick(), projectile, action);
    }
  }

  /** Tells every observer that a data-changing action swapped a projectile's row. */
  public void projectileSwapped(ProjectileEntity projectile, String from, String to) {
    for (WorldObserver observer : observers) {
      observer.projectileSwapped(tick(), projectile, from, to);
    }
  }

  /**
   * Tells every observer that a row's own deflection action counted a deflection on its projectile
   * and handed it the side of the deflector's parent.
   */
  public void projectileSelfDeflected(
      ProjectileEntity projectile, String action, WorldEntity deflector) {
    for (WorldObserver observer : observers) {
      observer.projectileSelfDeflected(tick(), projectile, action, deflector);
    }
  }

  /** Tells every observer that the evolved Executioner's axe controller started on its axe. */
  public void executionerStarted(ProjectileEntity axe, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.executionerStarted(tick(), axe, action, phase);
    }
  }

  /**
   * Tells every observer of a damage the axe controller was asked for.
   *
   * @param target what the hit lands on, or null
   * @param before the damage handed in
   * @param after the damage handed on
   * @param edge the target's distance from the axe's start less its radius, or null without one
   * @param strong true for a strong hit
   */
  public void axeDamage(
      ProjectileEntity axe,
      WorldEntity target,
      int hitId,
      int before,
      int after,
      Integer edge,
      boolean strong) {
    for (WorldObserver observer : observers) {
      observer.axeDamage(tick(), axe, target, hitId, before, after, edge, strong);
    }
  }

  /** Tells every observer that a strong hit of the axe scheduled its action on its target. */
  public void axeHitAction(ProjectileEntity axe, WorldEntity target, String action, int hitId) {
    for (WorldObserver observer : observers) {
      observer.axeHitAction(tick(), axe, target, action, hitId);
    }
  }

  /** A roll started on a projectile, with the destination it took. */
  public void rollStarted(
      ProjectileEntity projectile, String action, int phase, int destinationX, int destinationY) {
    for (WorldObserver observer : observers) {
      observer.rollStarted(tick(), projectile, action, phase, destinationX, destinationY);
    }
  }

  /** A capture started on its owner, a projectile or a character. */
  public void captureStarted(BattleEntity owner, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.captureStarted(tick(), owner, action, phase);
    }
  }

  /**
   * A roll moved its projectile, or put it on its destination and released it.
   *
   * @param projectile the rolling projectile
   * @param released true for the step that released it
   */
  public void rolled(ProjectileEntity projectile, boolean released) {
    for (WorldObserver observer : observers) {
      observer.rolled(tick(), projectile, released);
    }
  }

  /** A capture asked for a lock on a unit, with the request's answer. */
  public void captureRequested(BattleEntity owner, WorldEntity unit, int priority, boolean answer) {
    for (WorldObserver observer : observers) {
      observer.captureRequested(tick(), owner, unit, priority, answer);
    }
  }

  /** A capture scheduled an action on an object, with another as its cause. */
  public void captureScheduled(BattleEntity owner, BattleEntity cause, String action) {
    for (WorldObserver observer : observers) {
      observer.captureScheduled(tick(), owner, cause, action);
    }
  }

  /** A captured unit's pre-hook changed its hidden tag or the capture's tags in its word. */
  void captureTagsFolded(WorldEntity unit, boolean hidden, long word) {
    for (WorldObserver observer : observers) {
      observer.captureTagsFolded(tick(), unit, hidden, word);
    }
  }

  /**
   * A capture's step ended.
   *
   * @param owner the capturing owner
   * @param captured the ids it holds
   * @param complete the ids whose drag is complete
   * @param timesMs the time of each capture
   */
  public void captureStepped(
      BattleEntity owner, List<Integer> captured, List<Integer> complete, List<Integer> timesMs) {
    for (WorldObserver observer : observers) {
      observer.captureStepped(
          tick(), owner, List.copyOf(captured), List.copyOf(complete), List.copyOf(timesMs));
    }
  }

  /** Tells every observer that a barrage's run started on a character. */
  void barrageStarted(CharacterEntity owner, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.barrageStarted(tick(), owner, action, phase);
    }
  }

  /** Tells every observer of the area effects a barrage's update made. */
  void barrageStepped(CharacterEntity owner, String action, List<AreaEffectEntity> made) {
    for (WorldObserver observer : observers) {
      observer.barrageStepped(tick(), owner, action, List.copyOf(made));
    }
  }

  /** Tells the observers an evolved Royal Ghost's run started. */
  void ghostEvoStarted(CharacterEntity ghost, String action, int phase) {
    for (WorldObserver observer : observers) {
      observer.ghostEvoStarted(tick(), ghost, action, phase);
    }
  }

  /** Tells the observers an evolved Royal Ghost's hit summoned. */
  void ghostSummoned(CharacterEntity ghost, WorldEntity reference, int x, int y, int countdownMs) {
    for (WorldObserver observer : observers) {
      observer.ghostSummoned(tick(), ghost, reference, x, y, countdownMs);
    }
  }

  /** Tells the observers what a target indicator attack's run did. */
  void targetIndicatorLogged(CharacterEntity unit, TargetIndicatorAttack.Event event) {
    for (WorldObserver observer : observers) {
      observer.targetIndicatorLogged(tick(), unit, event);
    }
  }

  /** Tells the observers a taunt's perform reached a character. */
  void tauntPerformed(
      WorldEntity unit, String action, int phase, ActionOwner instigator, WorldEntity forced) {
    for (WorldObserver observer : observers) {
      observer.tauntPerformed(tick(), unit, action, phase, instigator, forced);
    }
  }

  /** Tells the observers what a taunt's arming or step did. */
  void tauntStepped(
      WorldEntity unit, WorldEntity forced, int durationMs, int falloffMs, List<String> calls) {
    for (WorldObserver observer : observers) {
      observer.tauntStepped(tick(), unit, forced, durationMs, falloffMs, calls);
    }
  }

  void cloneRefused(WorldEntity original, String reason, SpawnHost instigator) {
    for (WorldObserver observer : observers) {
      observer.cloneRefused(tick(), original, reason, instigator);
    }
  }

  void buffCopied(WorldEntity original, WorldEntity clone, BuffInstance copy) {
    for (WorldObserver observer : observers) {
      observer.buffCopied(tick(), original, clone, copy);
    }
  }

  void cloneBuffGateAsked(
      AreaEffectEntity areaEffect,
      String buff,
      CharacterEntity clone,
      String path,
      int query,
      boolean refused) {
    for (WorldObserver observer : observers) {
      observer.cloneBuffGateAsked(tick(), areaEffect, buff, clone, path, query, refused);
    }
  }

  void cloneMoveStarted(CharacterEntity unit, int targetX, int targetY) {
    for (WorldObserver observer : observers) {
      observer.cloneMoveStarted(tick(), unit, targetX, targetY);
    }
  }

  void cloneMoveEnded(CharacterEntity unit, boolean resumed) {
    for (WorldObserver observer : observers) {
      observer.cloneMoveEnded(tick(), unit, resumed);
    }
  }

  void selectorStarted(AreaEffectEntity areaEffect, String action, int phase, List<Integer> due) {
    for (WorldObserver observer : observers) {
      observer.selectorStarted(tick(), areaEffect, action, phase, due);
    }
  }

  void selectorStepped(AreaEffectEntity areaEffect, String action, ShapeSelector.Step step) {
    for (WorldObserver observer : observers) {
      observer.selectorStepped(tick(), areaEffect, action, step);
    }
  }

  void airToGroundStarted(
      WorldEntity unit, String action, int phase, int phaseNow, int counter, int height) {
    for (WorldObserver observer : observers) {
      observer.airToGroundStarted(tick(), unit, action, phase, phaseNow, counter, height);
    }
  }

  void airToGroundStepped(
      WorldEntity unit,
      int phaseBefore,
      int phaseAfter,
      int counterBefore,
      int counterAfter,
      boolean done,
      List<Integer> pushes) {
    for (WorldObserver observer : observers) {
      observer.airToGroundStepped(
          tick(), unit, phaseBefore, phaseAfter, counterBefore, counterAfter, done, pushes);
    }
  }

  void airToGroundRetriggered(WorldEntity unit, String action, int phase, int counter) {
    for (WorldObserver observer : observers) {
      observer.airToGroundRetriggered(tick(), unit, action, phase, counter);
    }
  }

  void groundToAirStarted(WorldEntity unit, String action, int phase, int phaseNow, int counter) {
    for (WorldObserver observer : observers) {
      observer.groundToAirStarted(tick(), unit, action, phase, phaseNow, counter);
    }
  }

  void groundToAirStepped(
      WorldEntity unit,
      int phaseBefore,
      int phaseAfter,
      int counterBefore,
      int counterAfter,
      List<Integer> pushes) {
    for (WorldObserver observer : observers) {
      observer.groundToAirStepped(
          tick(), unit, phaseBefore, phaseAfter, counterBefore, counterAfter, pushes);
    }
  }

  void laserStarted(AreaEffectEntity areaEffect, String action, int phase, int timerMs) {
    for (WorldObserver observer : observers) {
      observer.laserStarted(tick(), areaEffect, action, phase, timerMs);
    }
  }

  void laserFired(
      AreaEffectEntity areaEffect,
      int count,
      int index,
      List<WorldEntity> targets,
      String action,
      int timerBefore,
      int timerAfter) {
    for (WorldObserver observer : observers) {
      observer.laserFired(
          tick(), areaEffect, count, index, targets, action, timerBefore, timerAfter);
    }
  }

  void lifeTimeEndScheduled(AreaEffectEntity areaEffect, String action) {
    for (WorldObserver observer : observers) {
      observer.lifeTimeEndScheduled(tick(), areaEffect, action);
    }
  }

  void onHitActionScheduled(AreaEffectEntity areaEffect, WorldEntity target, BattleAction action) {
    for (WorldObserver observer : observers) {
      observer.onHitActionScheduled(tick(), areaEffect, target, action);
    }
  }

  void buffHookScheduled(WorldEntity carrier, BuffInstance buff, String action, boolean start) {
    for (WorldObserver observer : observers) {
      observer.buffHookScheduled(tick(), carrier, buff, action, start);
    }
  }

  void hitCounted(
      WorldEntity attacker, WorldEntity target, int before, int after, String buff, int timeMs) {
    for (WorldObserver observer : observers) {
      observer.hitCounted(tick(), attacker, target, before, after, buff, timeMs);
    }
  }

  void buffApplied(WorldEntity target, BuffInstance buff) {
    for (WorldObserver observer : observers) {
      observer.buffApplied(tick(), target, buff);
    }
  }

  void buffHandedOver(
      WorldEntity parent,
      WorldEntity rider,
      BuffData buff,
      int time,
      int packedLevel,
      SpawnHost source,
      List<BuffInstance> instances,
      Set<String> heldBefore) {
    for (WorldObserver observer : observers) {
      observer.buffHandedOver(
          tick(), parent, rider, buff, time, packedLevel, source, instances, heldBefore);
    }
  }

  void buffRefreshed(WorldEntity target, BuffInstance buff, int before, SpawnHost source) {
    for (WorldObserver observer : observers) {
      observer.buffRefreshed(tick(), target, buff, before, source);
    }
  }

  void attackCountRead(WorldEntity context, int attackTimeMs, int count) {
    for (WorldObserver observer : observers) {
      observer.attackCountRead(tick(), context, attackTimeMs, count);
    }
  }

  void lifeConditionAsked(WorldEntity carrier, BuffInstance buff, int answer) {
    for (WorldObserver observer : observers) {
      observer.lifeConditionAsked(tick(), carrier, buff, answer);
    }
  }

  void buffRemoved(WorldEntity target, BuffInstance buff) {
    for (WorldObserver observer : observers) {
      observer.buffRemoved(tick(), target, buff);
    }
  }

  /** Tells the observers which units an area effect's hit pulled. */
  void areaPulled(AreaEffectEntity areaEffect, List<AreaEffectEntity.Pull> pulls) {
    for (WorldObserver observer : observers) {
      observer.areaPulled(tick(), areaEffect, pulls);
    }
  }

  /**
   * Tells the observers of an area effect's launch on a step whose hit count rose.
   *
   * @param areaEffect the area effect
   * @param hit the hits due by the end of the step
   * @param bound the hits due by its start
   * @param choice what its chooser saw, or null for a row that drops its projectile on its point
   * @param projectile the projectile launched, or null when the chooser found nobody
   */
  void areaEffectLaunched(
      AreaEffectEntity areaEffect,
      int hit,
      int bound,
      AreaEffectEntity.Choice choice,
      ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.areaEffectLaunched(tick(), areaEffect, hit, bound, choice, projectile);
    }
  }

  /** Tells the observers an area effect was admitted. */
  void areaEffectAdmitted(AreaEffectEntity areaEffect) {
    for (WorldObserver observer : observers) {
      observer.areaEffectAdmitted(tick(), areaEffect);
    }
  }

  /** Tells the observers an area effect updated. */
  void areaEffectUpdated(
      AreaEffectEntity areaEffect,
      int before,
      int after,
      int hits,
      int radius,
      List<Integer> damages) {
    for (WorldObserver observer : observers) {
      observer.areaEffectUpdated(tick(), areaEffect, before, after, hits, radius, damages);
    }
  }

  /** Tells the observers what one hit of an area effect did. */
  void areaEffectDamaged(
      AreaEffectEntity areaEffect, AreaDamage.Area area, AreaDamage.Outcome outcome) {
    for (WorldObserver observer : observers) {
      observer.areaEffectDamaged(tick(), areaEffect, area, outcome);
    }
  }

  /** Tells the observers the area one hit of an area effect's filter form lists in. */
  void areaEffectFilterHit(
      AreaEffectEntity areaEffect, int x, int y, int radius, int halfWidth, int halfHeight) {
    for (WorldObserver observer : observers) {
      observer.areaEffectFilterHit(tick(), areaEffect, x, y, radius, halfWidth, halfHeight);
    }
  }

  /** An action's spawn row launched a projectile from the entity it runs on. */
  void actionProjectileLaunched(
      WorldEntity owner, String action, int phase, ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.actionProjectileLaunched(tick(), owner, action, phase, projectile);
    }
  }

  /**
   * After every entity's post-hook and before the closing cleanup: every position and state of the
   * tick is final, an entity that died this tick is still present, and every projectile the tick
   * visited has taken its step.
   */
  void afterPostHooks(List<WorldEntity> present, List<ProjectileEntity> projectiles) {
    for (WorldObserver observer : observers) {
      observer.afterPostHooks(tick(), present, projectiles);
    }
  }

  /**
   * After the tick's pre-pass: everything the visits query is built, and nothing has been visited.
   */
  void afterPrePass(List<WorldEntity> present) {
    for (WorldObserver observer : observers) {
      observer.afterPrePass(tick(), present);
    }
  }

  /** An area effect's hit reached the characters its buff applies to, before each is applied. */
  void areaBuff(AreaEffectEntity areaEffect, BuffData buff, int time, List<WorldEntity> targets) {
    for (WorldObserver observer : observers) {
      observer.areaBuff(tick(), areaEffect, buff, time, targets);
    }
  }

  /** An area effect was created and handed to the holder, which admits it at the next cleanup. */
  void areaEffectCreated(AreaEffectEntity areaEffect, String how, String source) {
    for (WorldObserver observer : observers) {
      observer.areaEffectCreated(tick(), areaEffect, how, source);
    }
  }

  /** One victim's share of an area effect's hit, once it is dealt. */
  void areaEffectHit(
      AreaEffectEntity areaEffect, WorldEntity victim, int damage, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.areaEffectHit(tick(), areaEffect, victim, damage, result);
    }
  }

  /** An area effect left the battle, at the cleanup that removed it. */
  void areaEffectRemoved(AreaEffectEntity areaEffect) {
    for (WorldObserver observer : observers) {
      observer.areaEffectRemoved(tick(), areaEffect);
    }
  }

  /** An action's spawn row made an area effect, just after it was created. */
  void areaEffectSpawned(
      SpawnHost owner, String action, int phase, SpawnHost source, AreaEffectEntity areaEffect) {
    for (WorldObserver observer : observers) {
      observer.areaEffectSpawned(tick(), owner, action, phase, source, areaEffect);
    }
  }

  /** One victim of the area of an entity's hit took its share. */
  void areaHit(
      WorldEntity attacker, WorldEntity victim, int damage, int hitId, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.areaHit(tick(), attacker, victim, damage, hitId, result);
    }
  }

  /**
   * An area effect's spawner made a character, after its registration visit and its clone setter.
   */
  void areaSpawned(
      AreaEffectEntity areaEffect, CharacterEntity child, int retries, int stateAfter) {
    for (WorldObserver observer : observers) {
      observer.areaSpawned(tick(), areaEffect, child, retries, stateAfter);
    }
  }

  /** A strong hit of the axe on its way out asked for a push of its target from the axe's start. */
  void axePushed(ProjectileEntity axe, WorldEntity target, int x, int y, int distance, int hitId) {
    for (WorldObserver observer : observers) {
      observer.axePushed(tick(), axe, target, x, y, distance, hitId);
    }
  }

  /** A barrage's bomb was dropped onto the area effect that caused the drop. */
  void bombDropped(
      AreaEffectEntity area, String action, int phase, ProjectileEntity projectile, int lifetime) {
    for (WorldObserver observer : observers) {
      observer.bombDropped(tick(), area, action, phase, projectile, lifetime);
    }
  }

  /** A buff instance's damage over time landed on its entity. */
  void buffDamaged(
      WorldEntity target, BuffInstance buff, int damage, int hitPointsBefore, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.buffDamaged(tick(), target, buff, damage, hitPointsBefore, result);
    }
  }

  /**
   * A buff instance on a dying object made its death spawn's characters, after each was spawned.
   */
  void buffDeathSpawn(WorldEntity dying, BuffInstance buff, List<CharacterEntity> made) {
    for (WorldObserver observer : observers) {
      observer.buffDeathSpawn(tick(), dying, buff, made);
    }
  }

  /**
   * A buff instance's heal over time was given to its entity, whether or not it raised anything.
   */
  void buffHealed(WorldEntity target, BuffInstance buff, int amount, int hitPointsBefore) {
    for (WorldObserver observer : observers) {
      observer.buffHealed(tick(), target, buff, amount, hitPointsBefore);
    }
  }

  /** A buff-spawning action put its buff on its owner. */
  void buffSpawned(
      WorldEntity owner,
      String action,
      BuffData buff,
      int time,
      int packedLevel,
      SpawnHost source) {
    for (WorldObserver observer : observers) {
      observer.buffSpawned(tick(), owner, action, buff, time, packedLevel, source);
    }
  }

  /** A card-play listener heard a card play. */
  void cardPlayHeard(
      WorldEntity owner,
      String action,
      int side,
      String played,
      String deployed,
      int total,
      String scheduled) {
    for (WorldObserver observer : observers) {
      observer.cardPlayHeard(tick(), owner, action, side, played, deployed, total, scheduled);
    }
  }

  /** A spawn handed a champion it made to its side's champion controllers. */
  void championHandedOver(SpawnHost source, CharacterEntity child) {
    for (WorldObserver observer : observers) {
      observer.championHandedOver(tick(), source, child);
    }
  }

  /**
   * A champion slot gave its king back its last use's cost, its last live copy gone inside the
   * refund window.
   */
  void championRefunded(ChampionController slot, int mana, int elixirBefore, int elixirAfter) {
    for (WorldObserver observer : observers) {
      observer.championRefunded(tick(), slot, mana, elixirBefore, elixirAfter);
    }
  }

  /**
   * A spawn created a child and registered it, inside the pass that ran the spawn: its registration
   * visit is done and its first-tick immunity set.
   */
  void characterSpawned(SpawnHost source, CharacterEntity child, int createdX, int createdY) {
    for (WorldObserver observer : observers) {
      observer.characterSpawned(tick(), source, child, createdX, createdY);
    }
  }

  /** A fallen king's circle reached an entity of its side and killed it. */
  void circleKilled(WorldEntity target, int radius) {
    for (WorldObserver observer : observers) {
      observer.circleKilled(tick(), target, radius);
    }
  }

  /**
   * A tiebreaker's clearing's kill of a character landed, at the damage drain of the update the
   * clearing ran.
   */
  void clearingKilled(WorldEntity target) {
    for (WorldObserver observer : observers) {
      observer.clearingKilled(tick(), target);
    }
  }

  /** A clone was made and registered, set up in its clone state; its buffs are not copied yet. */
  void cloned(
      CharacterEntity original,
      CharacterEntity clone,
      SpawnHost instigator,
      List<Integer> registrationVisits) {
    for (WorldObserver observer : observers) {
      observer.cloned(tick(), original, clone, instigator, registrationVisits);
    }
  }

  /** The damage of one direct hit was dealt to an entity. */
  void damageDealt(WorldEntity target, int damage, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.damageDealt(tick(), target, damage, result);
    }
  }

  /** A unit's death paid the king of the side that killed it. */
  void deathElixirPaid(WorldEntity dying, int side, int amount) {
    for (WorldObserver observer : observers) {
      observer.deathElixirPaid(tick(), dying, side, amount);
    }
  }

  /**
   * A dying entity scheduled its death hooks on itself, as its death handler does: its row's death
   * action, then, for a kill by another entity, its killed action.
   */
  void deathHooksScheduled(
      WorldEntity dying,
      BattleEntity attacker,
      int side,
      List<String> hooks,
      boolean inPendingPass) {
    for (WorldObserver observer : observers) {
      observer.deathHooksScheduled(tick(), dying, attacker, side, hooks, inPendingPass);
    }
  }

  /** A unit's death slot paid its own side's king the row's ManaOnDeath. */
  void deathManaPaid(WorldEntity dying, int side, int amount) {
    for (WorldObserver observer : observers) {
      observer.deathManaPaid(tick(), dying, side, amount);
    }
  }

  /** A dying object's death slot launched one of its death projectiles, handed to the holder. */
  void deathProjectileLaunched(WorldEntity dying, ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.deathProjectileLaunched(tick(), dying, projectile);
    }
  }

  /** A character's lifetime decay took its last hit point, before its death slot runs. */
  void decayDied(WorldEntity entity, int hitPointsBefore) {
    for (WorldObserver observer : observers) {
      observer.decayDied(tick(), entity, hitPointsBefore);
    }
  }

  /**
   * A unit entering the deploying state pushed the enemies around it: what its query found, and
   * whom it asked to push, after the pushback of each was asked for.
   */
  void deployPushed(
      CharacterEntity unit,
      int radius,
      int distance,
      List<WorldEntity> found,
      List<WorldEntity> pushed) {
    for (WorldObserver observer : observers) {
      observer.deployPushed(tick(), unit, radius, distance, found, pushed);
    }
  }

  /** A run started or stopped listening for destroyed objects. */
  void destroyedListening(WorldEntity owner, String action, boolean listening) {
    for (WorldObserver observer : observers) {
      observer.destroyedListening(tick(), owner, action, listening);
    }
  }

  /** An object's death slot started with runs listening for destroyed objects, which hear of it. */
  void destroyedNoticed(WorldEntity dying, int listeners) {
    for (WorldObserver observer : observers) {
      observer.destroyedNoticed(tick(), dying, listeners);
    }
  }

  /**
   * An object without hit points died as its state visit removed it - a bomb as its deploy ended -
   * before its death slot runs.
   */
  void diedAtRemoval(WorldEntity entity) {
    for (WorldObserver observer : observers) {
      observer.diedAtRemoval(tick(), entity);
    }
  }

  /** A dragging projectile's hook or drag asked a unit's state setter for a state. */
  void dragStateSet(ProjectileEntity projectile, WorldEntity unit, int oldState, int newState) {
    for (WorldObserver observer : observers) {
      observer.dragStateSet(tick(), projectile, unit, oldState, newState);
    }
  }

  /** A tiebreaker's drain took a step off a tower. */
  void drained(WorldEntity target, int damage, int hitPoints, boolean died) {
    for (WorldObserver observer : observers) {
      observer.drained(tick(), target, damage, hitPoints, died);
    }
  }

  /** An arena entity left the battle. */
  void entityRemoved(WorldEntity removed) {
    for (WorldObserver observer : observers) {
      observer.entityRemoved(tick(), removed);
    }
  }

  /** An evolved Royal Ghost's run made an area effect: a summon area, or the damage area. */
  void ghostAreaMade(CharacterEntity ghost, AreaEffectEntity area, WorldEntity target) {
    for (WorldObserver observer : observers) {
      observer.ghostAreaMade(tick(), ghost, area, target);
    }
  }

  /** A summon area made its summon, before the summon takes its reference. */
  void ghostSummonMade(AreaEffectEntity area, CharacterEntity summon) {
    for (WorldObserver observer : observers) {
      observer.ghostSummonMade(tick(), area, summon);
    }
  }

  /** A summon area's summon took its reference, faced its point and ran its combat gate. */
  void ghostSummonSpawned(AreaEffectEntity area, CharacterEntity summon) {
    for (WorldObserver observer : observers) {
      observer.ghostSummonSpawned(tick(), area, summon);
    }
  }

  /**
   * A child left the battle and was unlinked from its source's group, in the cleanup that removed
   * it.
   */
  void groupUnlinked(CharacterEntity source, CharacterEntity child) {
    for (WorldObserver observer : observers) {
      observer.groupUnlinked(tick(), source, child);
    }
  }

  /**
   * A guard was made and registered, before its maker faced it and put it into its deploy: where
   * its registration visit left it, its state and its reference.
   */
  void guardRegistered(CharacterEntity guard) {
    for (WorldObserver observer : observers) {
      observer.guardRegistered(tick(), guard);
    }
  }

  /** A guard-spawning run started on an area effect and made its guard. */
  void guardStarted(
      AreaEffectEntity areaEffect,
      String action,
      int phase,
      CharacterEntity guard,
      int x,
      int y,
      int toX,
      int toY) {
    for (WorldObserver observer : observers) {
      observer.guardStarted(tick(), areaEffect, action, phase, guard, x, y, toX, toY);
    }
  }

  /**
   * One step of a Kamikaze unit's drain landed on it: queued in its state visit, dealt at the
   * damage drain of the same tick.
   */
  void kamikazeDrained(WorldEntity unit, int damage, int hitPointsBefore, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.kamikazeDrained(tick(), unit, damage, hitPointsBefore, result);
    }
  }

  /** A Kamikaze unit killed itself at the end of its hit. */
  void kamikazeKilled(WorldEntity unit, int damage, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.kamikazeKilled(tick(), unit, damage, result);
    }
  }

  /**
   * A kill scheduled its killer's killed-done action on the killer, with what it killed as the
   * cause.
   */
  void killedDoneScheduled(
      WorldEntity killer, WorldEntity killed, String action, boolean inPendingPass) {
    for (WorldObserver observer : observers) {
      observer.killedDoneScheduled(tick(), killer, killed, action, inPendingPass);
    }
  }

  /** A unit that surfaced was morphed into a new object, which is queued and deploying. */
  void morphed(CharacterEntity old, CharacterEntity made) {
    for (WorldObserver observer : observers) {
      observer.morphed(tick(), old, made);
    }
  }

  /** A rider's parent left the holder and let it go, before its death slot runs. */
  void parentLeft(CharacterEntity rider, CharacterEntity parent) {
    for (WorldObserver observer : observers) {
      observer.parentLeft(tick(), rider, parent);
    }
  }

  /** A projectile's impact made the area effect its row names, at the impact point. */
  void projectileAreaEffect(ProjectileEntity projectile, AreaEffectEntity areaEffect) {
    for (WorldObserver observer : observers) {
      observer.projectileAreaEffect(tick(), projectile, areaEffect);
    }
  }

  /**
   * A projectile's impact reached the characters its target buff applies to, before each is
   * applied.
   */
  void projectileBuff(
      ProjectileEntity projectile, BuffData buff, int time, List<WorldEntity> targets) {
    for (WorldObserver observer : observers) {
      observer.projectileBuff(tick(), projectile, buff, time, targets);
    }
  }

  /**
   * A deflecting area effect turned a projectile around, sending it back at its source, or at a
   * point.
   */
  void projectileDeflected(
      AreaEffectEntity deflector,
      ProjectileEntity projectile,
      WorldEntity parent,
      WorldEntity source) {
    for (WorldObserver observer : observers) {
      observer.projectileDeflected(tick(), deflector, projectile, parent, source);
    }
  }

  /** A projectile arrived and dealt its damage to its target. */
  void projectileImpacted(
      ProjectileEntity projectile, WorldEntity target, int damage, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.projectileImpacted(tick(), projectile, target, damage, result);
    }
  }

  /**
   * A projectile was launched and handed to the holder; it has its id and its start and aim, and
   * first flies on the next tick.
   */
  void projectileLaunched(ProjectileEntity projectile) {
    for (WorldObserver observer : observers) {
      observer.projectileLaunched(tick(), projectile);
    }
  }

  /**
   * Projectiles aimed at a unit lost it as their target as it went into a pathfinding state, or as
   * a warp reset its pending damage.
   */
  void projectilesDropped(CharacterEntity unit, List<String> projectiles) {
    for (WorldObserver observer : observers) {
      observer.projectilesDropped(tick(), unit, projectiles);
    }
  }

  /** The killer's hook reached a unit whose row passes over buffed targets, on a hit it landed. */
  void referenceDroppedOnHit(
      CharacterEntity unit,
      WorldEntity hit,
      ProjectileEntity via,
      boolean kills,
      boolean active,
      TargetView dropped) {
    for (WorldObserver observer : observers) {
      observer.referenceDroppedOnHit(tick(), unit, hit, via, kills, active, dropped);
    }
  }

  /**
   * A reflecting unit's reflect dealt its damage to the unit it struck, at the damage drain that
   * reached it, before the unit's death, if the damage killed it, runs.
   */
  void reflectedHit(WorldEntity reflector, WorldEntity struck, int damage, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.reflectedHit(tick(), reflector, struck, damage, result);
    }
  }

  /** A rider was attached to the parent whose spawner made it, after its registration visit. */
  void riderAttached(CharacterEntity parent, CharacterEntity rider, int index, int angle) {
    for (WorldObserver observer : observers) {
      observer.riderAttached(tick(), parent, rider, index, angle);
    }
  }

  /**
   * A spawn whose children take a fixed priority asked the lane of its source's point before
   * placing a child on its ring.
   */
  void ringLaneAsked(SpawnHost source, int x, int y, int lane) {
    for (WorldObserver observer : observers) {
      observer.ringLaneAsked(tick(), source, x, y, lane);
    }
  }

  /** A roll buffed what it found. */
  void rollBuffed(ProjectileEntity projectile, WorldEntity target, String buff, int timeMs) {
    for (WorldObserver observer : observers) {
      observer.rollBuffed(tick(), projectile, target, buff, timeMs);
    }
  }

  /** An entity's broken shield scheduled its row's action on the entity's own holder. */
  void shieldLostScheduled(
      WorldEntity unit, String action, SpawnHost cause, boolean inPendingPass) {
    for (WorldObserver observer : observers) {
      observer.shieldLostScheduled(tick(), unit, action, cause, inPendingPass);
    }
  }

  /** An area effect's spawner shuffled the order of its directions on its first update. */
  void spawnOrdered(AreaEffectEntity areaEffect, int[] order, int stateBefore, int stateAfter) {
    for (WorldObserver observer : observers) {
      observer.spawnOrdered(tick(), areaEffect, order, stateBefore, stateAfter);
    }
  }

  /** A tether scheduled one of its activation rows as it started. */
  void tetherActivated(AreaEffectEntity owner, BattleEntity target, String action) {
    for (WorldObserver observer : observers) {
      observer.tetherActivated(tick(), owner, target, action);
    }
  }

  /** A tether's damage pass hit an object. */
  void tetherHit(
      AreaEffectEntity owner,
      WorldEntity target,
      int damage,
      int directionX,
      int directionY,
      DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.tetherHit(tick(), owner, target, damage, directionX, directionY, result);
    }
  }

  /** A tether's damage pass scheduled its hit action on an object. */
  void tetherHitAction(AreaEffectEntity owner, WorldEntity target, String action) {
    for (WorldObserver observer : observers) {
      observer.tetherHitAction(tick(), owner, target, action);
    }
  }

  /** A typed hit was dealt to an entity by the drain. */
  void typedHitDealt(
      WorldEntity source, WorldEntity target, int amount, int damageId, DamageResult result) {
    for (WorldObserver observer : observers) {
      observer.typedHitDealt(tick(), source, target, amount, damageId, result);
    }
  }

  /**
   * A unit that carried the cost of an ability use not yet delivered left the battle, and its
   * side's king was given the cost back.
   */
  void undeliveredCostReturned(CharacterEntity unit, int mana, int elixirBefore, int elixirAfter) {
    for (WorldObserver observer : observers) {
      observer.undeliveredCostReturned(tick(), unit, mana, elixirBefore, elixirAfter);
    }
  }

  /** A warp moved a unit; its new position is on the unit. */
  void warped(
      CharacterEntity unit,
      String action,
      int phase,
      int fromX,
      int fromY,
      String referenceBefore) {
    for (WorldObserver observer : observers) {
      observer.warped(tick(), unit, action, phase, fromX, fromY, referenceBefore);
    }
  }
}
