/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.DashingAttackChain;
import org.crforge.core.battle.action.RunOnResolvedObjects;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.ReferenceSetter;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * One run of a dashing attack chain on a character: its phase, how many dashes it has landed, the
 * ids of the objects it has hit and whether the character was dashing on its last step.
 *
 * <p>The phase is 0 while the chain waits for an object and 1 once a dash has begun. The run's
 * targeting component is the character's while it is switched on; the reference given up at the end
 * is the character's whatever its switch. See {@link DashingAttackChain} for the steps.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Read line for line: the start, the pick, the begin, the step and the landing of a"
            + " one-dash chain. Held by ability_golden_knight, tv_replay_019 and GoldenKnightChargeTest.")
final class DashingAttackChainRun extends ActionInstance {

  /** The phase while the chain waits for an object. */
  private static final int WAITING = 0;

  /** The phase once a dash has begun. */
  private static final int DASHING = 1;

  private final DashingAttackChain chain;
  private final DashingAttackChain.Columns columns;
  private final CharacterEntity unit;

  private int phase = WAITING;

  /** How many dashes the chain has landed. */
  private int links;

  /** The ids of the objects the chain has hit, in order. */
  private final List<Integer> hit = new ArrayList<>();

  /** Whether the character was dashing as the run last looked. */
  private boolean wasDashing;

  DashingAttackChainRun(DashingAttackChain chain, CharacterEntity unit) {
    super(chain);
    this.chain = chain;
    this.columns = chain.getColumns();
    this.unit = unit;
  }

  /**
   * The start: the first object picked and the dash begun on it, the character counted as dashing;
   * with nothing to pick the run waits.
   */
  void start() {
    WorldEntity first = pick();
    if (first != null) {
      begin(first);
      wasDashing = true;
    }
  }

  @Override
  protected void update(ActionHolder running) {
    if (isFinished()) {
      return;
    }
    boolean dashing = unit.getView().getState() == GridEntityState.DASHING;
    if (phase == WAITING) {
      WorldEntity next = pick();
      if (next != null) {
        begin(next);
      }
      wasDashing = dashing;
      return;
    }
    if (dashing) {
      // A reference that died while the character dashes at it is counted as hit.
      TargetingState t = activeTargeting();
      TargetView reference = t == null ? null : t.getReference();
      if (reference != null && !reference.alive() && !hit.contains(reference.id())) {
        hit.add(reference.id());
      }
      wasDashing = true;
      return;
    }
    if (wasDashing) {
      landed();
    }
    wasDashing = false;
  }

  /**
   * The ranked objects of the resolver around the character, at most {@link
   * DashingAttackChain#RANKED}: the first not hit before that is alive and, with the targeting
   * switched on, that the targeting would take; null for none.
   */
  private WorldEntity pick() {
    RunOnResolvedObjects.Host host = unit.resolvedObjectsHost(chain);
    List<SetIndicatorOnTarget.Candidate> pool =
        columns.cone() == null
            ? host.candidates(columns.filter())
            : host.candidates(columns.filter(), columns.cone());
    List<SetIndicatorOnTarget.Candidate> ranked =
        RunOnResolvedObjects.ranked(
            host,
            pool,
            columns.strategies(),
            DashingAttackChain.RANKED,
            chain.name(),
            columns.resolver());
    boolean validates = activeTargeting() != null;
    for (SetIndicatorOnTarget.Candidate candidate : ranked) {
      if (hit.contains(candidate.id())) {
        continue;
      }
      BattleEntity live = unit.world().liveObject(candidate.id());
      if (!(live instanceof WorldEntity object) || !object.getTargetView().alive()) {
        continue;
      }
      if (validates
          && !unit.getSelection().validate(object.getTargetView(), ReferenceValidator.MODE_TAKE)) {
        continue;
      }
      return object;
    }
    return null;
  }

  /**
   * A dash begins on an object, with the targeting switched on: the character's own dash onto it,
   * then the phase.
   */
  private void begin(WorldEntity onto) {
    if (activeTargeting() == null) {
      return;
    }
    unit.dashOnto(onto);
    phase = DASHING;
  }

  /**
   * The dash landed: the reference, if any, added to the hit list, the dash counted and, the count
   * reached, the reference given up and the run finished. With the targeting switched off only the
   * reference is given up and the run finished.
   */
  private void landed() {
    TargetingState t = activeTargeting();
    if (t != null) {
      TargetView reference = t.getReference();
      if (reference != null) {
        hit.add(reference.id());
      }
      // The row makes one dash (its build refuses more), so the first landing completes it.
      links++;
    }
    ReferenceSetter.setReference(
        unit.getTargeting(),
        null,
        false,
        false,
        false,
        unit.getSelection(),
        unit.getSelection().getOutcome());
    finish();
  }

  /** The character's targeting while it is switched on, else null. */
  private TargetingState activeTargeting() {
    return unit.isActive(CharacterEntity.TARGETING_SLOT) ? unit.getTargeting() : null;
  }
}
