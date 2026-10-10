/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.GoblinsteinAbility;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.index.SegmentTests;

/**
 * One run of Goblinstein's ability action on the area effect that follows the doctor.
 *
 * <p>Its first step connects: it asks the object the area effect follows for the first unit of its
 * group chain, and takes the one after that when the followed object is itself the first, so the
 * doctor connects to the monster made before it; an object in no chain connects to nothing. Every
 * later step waits for the followed object to cast its ability, which only the ability's request
 * leads to, and then for the cast to end, when the tether starts for its duration.
 *
 * <p>Each tether update schedules the activation rows on its first update, on the area effect and
 * on the connected object, the area effect their cause; runs a damage pass on its first update and
 * whenever the hit timer has reached the hit interval; and empties the list of objects its hit
 * action has reached whenever the hit-action timer has reached its interval. Both timers count up
 * by a step each update. Once the tether's time has run out the run waits for the next cast.
 *
 * <p>A damage pass runs along the segment from the area effect to the connected object, when there
 * is one. Each character the segment query answers that is touchable and has hit points takes the
 * tether's damage, or its crown tower damage on a crown tower, scaled at the area effect's level
 * and rarity, with the area effect as attacker; the hit action is scheduled on it at every pass
 * when the two intervals are equal, else once for each emptying of the list.
 *
 * <p>When the connected object leaves, the run forgets it and, the first time, makes the row's
 * death area at its point, for the area effect's side and at its level, the area effect its parent
 * and following nothing, and holds it as the connected object, so a later tether runs to it. When
 * the area effect itself leaves, before any notice of it, a death area it still holds ends: its
 * countdown goes to 0, not to the end's -1. As an area effect's life ends only below 0, the death
 * area has one more update and leaves in the cleanup of the next step.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the connection to the followed object's chain head, or the one"
            + " after it, the waits for the cast and its end, the tether's updates, activation"
            + " rows, damage passes, hits and hit actions, the death area made once at the"
            + " connected object's point as it leaves, held in its place, and ended as the area"
            + " effect leaves; held by the reference battles ability_goblinstein,"
            + " card_Goblinstein and random_battle16_s0009; the hit's hidden test lifted, which"
            + " no run reaches, is translated as the game's. The tags set on both ends each"
            + " tether update are not set, as no battle code reads them; the presentation of the"
            + " beam and its targets is not modelled.")
final class GoblinsteinRun extends ActionInstance {

  /** The first step, which connects. */
  private static final int CONNECTING = 0;

  /** Waiting for the followed object to cast. */
  private static final int WAITING = 1;

  /** Waiting for the cast to end. */
  private static final int CASTING = 2;

  /** The tether. */
  private static final int TETHERING = 3;

  /** One update's time, by which the tether's clocks run. */
  private static final int STEP_MS = 50;

  private final BattleWorld world;
  private final GoblinsteinAbility ability;
  private final AreaEffectEntity owner;

  private int state = CONNECTING;

  /** The unit it is connected to, then the death area it made; null for none. */
  private BattleEntity connected;

  /** True once the death area is made: it is made at most once. */
  private boolean deathAreaMade;

  /** What is left of the tether's time. */
  private int remainingMs;

  /** The time since the last damage pass; below 0 before a tether's first. */
  private int hitTimerMs = -1;

  /** The time since the hit-action list was last emptied; below 0 before a tether's first. */
  private int actionTimerMs = -1;

  /** The ids of the objects the hit action has reached since the list was last emptied. */
  private final List<Integer> actioned = new ArrayList<>();

  GoblinsteinRun(BattleWorld world, GoblinsteinAbility ability, AreaEffectEntity owner) {
    super(ability);
    this.world = world;
    this.ability = ability;
    this.owner = owner;
  }

  @Override
  protected void update(ActionHolder holder) {
    SpawnHost follow = owner.getFollow();
    checkState(follow != null, () -> ability.name() + " steps on an area effect that follows none");
    switch (state) {
      case CONNECTING -> {
        CharacterEntity head = follow instanceof CharacterEntity unit ? unit.chainHead() : null;
        connected = head == follow ? head.chainNext() : head;
        state = WAITING;
        world.goblinsteinConnected(owner, connected);
      }
      case WAITING -> {
        if (casting(follow)) {
          hitTimerMs = -1;
          actionTimerMs = -1;
          state = CASTING;
          world.goblinsteinStepped(owner, "cast_seen");
        }
      }
      case CASTING -> {
        if (!casting(follow)) {
          remainingMs = ability.getColumns().tetherDurationMs();
          state = TETHERING;
          world.goblinsteinStepped(owner, "tether_start " + remainingMs);
        }
      }
      default -> {
        tetherUpdate();
        if (remainingMs >= 1) {
          int before = remainingMs;
          remainingMs -= STEP_MS;
          if (before <= STEP_MS) {
            state = WAITING;
            world.goblinsteinStepped(owner, "tether_end");
          }
        }
      }
    }
  }

  /** Whether the followed object is casting its ability; only a character casts. */
  private boolean casting(SpawnHost follow) {
    if (!(follow instanceof CharacterEntity unit)) {
      throw new UnsupportedOperationException(
          ability.name() + " waits on " + follow + ", which is not a character, not modelled");
    }
    return unit.getView().getState() == GridEntityState.CASTING;
  }

  /** One update of the tether. */
  private void tetherUpdate() {
    GoblinsteinAbility.Columns columns = ability.getColumns();
    if (hitTimerMs < 0 && actionTimerMs < 0) {
      world.tetherActivation(owner, owner, columns.onTetherActivationAction());
      if (connected != null) {
        world.tetherActivation(owner, connected, columns.onTetherActivationActionOnConnectedUnit());
      }
    }
    int hit = hitTimerMs;
    if (hit < 0 || hit >= columns.tetherHitIntervalMs()) {
      damagePass(columns);
      hit = 0;
    }
    int act = actionTimerMs;
    if (act < 0 || act >= columns.tetherHitActionIntervalMs()) {
      act = 0;
      actioned.clear();
    }
    hitTimerMs = hit + STEP_MS;
    actionTimerMs = act + STEP_MS;
  }

  /** One damage pass along the segment from the area effect to the connected object. */
  private void damagePass(GoblinsteinAbility.Columns columns) {
    if (connected == null) {
      return;
    }
    int level = owner.packedLevel();
    int damage = scaled(columns.tetherDamage(), level);
    int crownDamage =
        scaled(
            columns.tetherCrownTowerDamage() != 0
                ? columns.tetherCrownTowerDamage()
                : columns.tetherDamage(),
            level);
    int ax = owner.getX();
    int ay = owner.getY();
    int bx;
    int by;
    if (connected instanceof AreaEffectEntity area) {
      bx = area.getX();
      by = area.getY();
    } else {
      WorldEntity unit = (WorldEntity) connected;
      bx = unit.getView().getX();
      by = unit.getView().getY();
    }
    List<WorldEntity> found =
        world.segmentQuery(
            owner, ax, ay, bx, by, columns.tetherWidth(), columns.tetherDamageTargets());
    world.tetherDamagePass(owner, ax, ay, bx, by, found);
    if (found == null) {
      return;
    }
    for (WorldEntity target : found) {
      if (target.untouchable(true) || target.getHitPoints() == null) {
        continue;
      }
      int amount = target.getTargetView().isCrownTowerTarget() ? crownDamage : damage;
      if (amount >= 1) {
        int ox = target.getView().getX();
        int oy = target.getView().getY();
        int[] nearest = SegmentTests.nearestPoint(ax, ay, bx, by, ox, oy);
        // The direction's second part takes the object's x as well, as the game's does.
        world.tetherHit(owner, target, amount, nearest[0] - ox, nearest[1] - ox);
      }
      String row = columns.tetherHitAction();
      if (row == null) {
        continue;
      }
      if (columns.tetherHitActionIntervalMs() != columns.tetherHitIntervalMs()) {
        if (actioned.contains(target.getId())) {
          continue;
        }
        actioned.add(target.getId());
      }
      world.tetherHitAction(owner, target, row);
    }
  }

  /** A tether damage scaled at the area effect's level and rarity. */
  private int scaled(int value, int level) {
    return LevelScaling.scale(
        ScalingGlobals.standard(), value, level, ScalingMode.CARD_DAMAGE, owner.getData().rarity());
  }

  @Override
  protected void objectLeft(int leftId) {
    if (connected == null || connected.getId() != leftId) {
      return;
    }
    BattleEntity left = connected;
    connected = null;
    String row = ability.getColumns().deathAreaEffect();
    if (deathAreaMade || row == null) {
      return;
    }
    WorldEntity unit = (WorldEntity) left;
    int x = unit.getView().getX();
    int y = unit.getView().getY();
    AreaEffectEntity deathArea = world.goblinsteinDeathArea(owner, row, x, y);
    connected = deathArea;
    deathAreaMade = true;
    world.goblinsteinDeathAreaMade(owner, unit, deathArea, x, y);
  }

  @Override
  protected void ownerLeaving(ActionHolder holder) {
    if (deathAreaMade && connected instanceof AreaEffectEntity deathArea) {
      // The countdown goes to 0, not to the end's -1.
      deathArea.zeroCountdown();
      connected = null;
      world.goblinsteinDeathAreaEnded(owner, deathArea);
    }
  }
}
