/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Knockback;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;

/**
 * One run of a knock on a character: its counter, from the row's duration, and the height it last
 * pushed.
 *
 * <p>As it starts, a unit with a movement component in a jump, or dashing under a row with a jump
 * height (a jumping Mega Knight), is put down as a hook or a capture's completed drag puts it down:
 * on the ground, asked to stand, which ends its jump or dash, and its route emptied; the knock then
 * lifts it as any other. A unit dashing under a row with no jump height (a Golden Knight's chain
 * dash) is not put down: the knock runs on it as it dashes.
 *
 * <p>In the game neither the start nor the update tests a unit following a removed building (a
 * Fisherman a hook on a building drags): a knock started on him runs as on any unit, and so does
 * one already running when his hook lands on a building. The hook drags him on, on its own step,
 * while the knock lifts him; a stun given with the knock (the hero Giant's slap) switches his
 * targeting off, and the hook then lets him go where he is, so he stands under the rest of the
 * knock. An update that finds him still dragged with his targeting on, the hook going on with him
 * (a knock with no stun), is refused: no recording holds a drag of several steps under a knock.
 *
 * <p>Each update raises DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS, and FORCE_IS_AIR while more
 * than 149 ms are left. A unit with a movement component that tracks a charge has its charge reset,
 * as the state setter resets it: the progress to 0 for a unit with a charge range of its own or
 * from a buff, else to no charge, and the targeting component's charged strike dropped. So a
 * charging unit loses its charge, and with it the charge's speed and its charged hit, for as long
 * as it is in the air. A unit that has begun switching lanes since the last update - its ability's
 * cast going on through the knock, the activation in the air - ends the run there: its pushback in
 * flight stopped and the run finished, with no height pushed, no landing and the counter left as it
 * is. For a unit with a movement component and a height it pushes the arc's next height, with that
 * height as the push's floor: half the duration rising to the top and half falling. Once the
 * counter is at 0 or below the unit lands - its route reset and, on the ground, the row's landing
 * action scheduled on it - and the run finishes, the counter still taken down by 50 ms.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's tags and counter, each update's tags, the arc's"
            + " height in 32-bit arithmetic with its two divisions by 100, the landing's route"
            + " reset and the finish; held by the reference battles"
            + " cg_megaknight_evo_uppercut_giant and evo_megaknight_vs_musketeer. The landing"
            + " action, with the cause passed on, held by ability_hero_giant_slap; with the cause"
            + " dead or gone, held by a recorded witness outside the locked references. A knock on a"
            + " hero whose ability is idle held by random_battle16_s0019 and tv_replay_015; the"
            + " ability postponed while the run is listed, a request in the air cast once it has"
            + " left, held by a recorded witness outside the locked references. The charge reset"
            + " on each update, held by a recorded witness outside the locked references. A cast"
            + " or an ability's follow-up state going on through the knock, the activation in the"
            + " air included, held by recorded witnesses outside the locked references. A lane"
            + " switch in the air ending the run on the next update, the pushback in flight"
            + " stopped, held by recorded witnesses outside the locked references. The start's"
            + " put-down of a unit in a jump or dashing under a row with a jump height, held by"
            + " recorded witnesses outside the locked references. A unit dashing under a row with"
            + " no jump height knocked as it dashes, its dash going on, held by recorded witnesses"
            + " outside the locked references. A Fisherman a hook on a building drags knocked as"
            + " any unit, the start and the update testing no such state: a slap started as the"
            + " hook drags him, and a slap running when the hook lands, the hook letting him go once"
            + " the slap's stun switches his targeting off, held by recorded witnesses outside the"
            + " locked references. Refused: an update finding him still dragged with his targeting"
            + " on (a knock with no stun, an uppercut running when the hook lands), a drag of"
            + " several steps under a knock no recording holds. A carrier knocked as any unit,"
            + " its riders put back around it on each movement visit, held by recorded battles"
            + " outside the locked references. Refused: a unit already"
            + " switching lanes as the run starts, which the standard game finishes at once but"
            + " no recorded battle reaches; a rider. A clone knocked as any unit, the start,"
            + " put-down and update asking nothing of it, held by the reference battle"
            + " graveyard_clone_slap_push (the hero Giant's slap); an uppercut's push reaching a"
            + " clone is refused, which no recording holds.")
final class KnockbackRun extends ActionInstance {

  /** Milliseconds one update takes off the counter. */
  private static final int STEP_MS = 50;

  /** FORCE_IS_AIR is raised while the counter is above this. */
  private static final int AIR_UNTIL = 149;

  private final Knockback row;
  private final CharacterEntity unit;

  /** What is left of the knock, in milliseconds. */
  private int counter;

  /** The height the last update pushed. */
  private int height;

  /** What caused the knock, kept for the landing action when the row passes it on; else null. */
  private final WorldEntity instigator;

  /**
   * The start: a unit in a jump or a dash with a height put down, the ability postponed while the
   * run is listed, the counter from the duration, and FORCE_IS_AIR with
   * DISABLE_PHYSICAL_INTERACTIONS_WITH_OBJECTS raised.
   *
   * @param row the knock
   * @param unit the unit it runs on
   * @param phase the phase of the pending pass that ran it
   * @param instigator what caused it, or null
   */
  KnockbackRun(Knockback row, CharacterEntity unit, int phase, WorldEntity instigator) {
    super(row);
    this.row = row;
    this.unit = unit;
    int state = unit.getView().getState();
    // A unit in a jump or a dash with a height but no movement component, which the put-down below
    // would leave in the air, is held by no reference. A unit following a removed building (a
    // Fisherman a hook on a building drags) is knocked as any other: the knock's own code does not
    // test that state; the update refuses him while the hook goes on dragging him with his
    // targeting on. A unit dashing under a row with no jump height (a Golden Knight's or a Bandit's
    // dash) is knocked as it is: the put-down does nothing for it, the knock's own code tests no
    // dash, and its dash goes on under the knock's tags and arc.
    boolean inTheAir = BattleWorld.inTheAir(unit);
    if (inTheAir && !unit.hasMovementComponent()) {
      throw new UnsupportedOperationException(
          row.name() + " knocks " + unit.name() + " in state " + state + ", not modelled");
    }
    // The cause is kept from the start, for the landing action that takes it.
    this.instigator = row.isPassInstigator() ? instigator : null;
    if (row.isPassInstigator() && instigator == null) {
      throw new UnsupportedOperationException(
          row.name() + " passes its cause on to its landing action but has none, not modelled");
    }
    // A unit already switching lanes, routing across in the in-game pathfinding state, is not
    // knocked in the standard game: the run finishes as it starts, with no tag raised, no counter
    // and the ability not postponed. No recorded battle reaches it - the uppercut does not push a
    // unit in that state, so it schedules no knock on it - so it is refused rather than taken on
    // the trace alone.
    if (state == GridEntityState.INGAME_PATHFIND) {
      throw new UnsupportedOperationException(
          row.name()
              + " knocks "
              + unit.name()
              + " as it switches lanes, which no reference holds");
    }
    // The put-down a hook and a capture's completed drag give, before the run's tags: a unit in a
    // jump, or dashing under a row with a jump height (a jumping Mega Knight), is set on the
    // ground, asked to stand, which ends its jump or dash, and its route emptied. A unit dashing
    // under a row with no jump height is left dashing.
    if (inTheAir) {
      unit.getView().setZ(0);
      unit.requestState(GridEntityState.STANDING);
      unit.resetRoute();
    }
    addTags(unit.getView().getFlagBits().abilityPostponed());
    counter = row.getDurationMs();
    unit.startLayering();
    unit.raiseWatched(unit.world().forceIsAir() | unit.getView().getFlagBits().disablePhysical());
    unit.world().getEvents().knockbackStarted(unit, row.name(), phase, instigator, counter);
  }

  @Override
  protected void update(ActionHolder holder) {
    int before = counter;
    long tags = unit.getView().getFlagBits().disablePhysical();
    if (counter > AIR_UNTIL) {
      tags |= unit.world().forceIsAir();
    }
    unit.raiseWatched(tags);
    boolean moving = unit.hasMovementComponent();
    // A unit that tracks a charge has it reset on every update, the landing one too: the progress
    // back to 0 (or to no charge) and the charged strike dropped, before the arc's height.
    if (moving && unit.getUnit().movement().getChargeProgress() != MovementState.CHARGE_INACTIVE) {
      unit.resetCharge();
    }
    // A unit that has begun switching lanes in the air, its ability's cast going on through the
    // knock, ends the run here: its pushback in flight stopped, as the lane switch's own entry
    // stops it, and the run finished with no height pushed, no landing and its counter left as it
    // is.
    if (unit.getView().getState() == GridEntityState.INGAME_PATHFIND) {
      if (moving) {
        unit.getUnit().movement().stopPushback();
      }
      finish();
      unit.world().getEvents().knockbackStepped(unit, before, counter, height, tags, true);
      return;
    }
    // A unit a hook on a building drags (a Fisherman) is knocked as any other while the hook lets
    // it go: the hero Giant's slap stuns him with its knock, his targeting is off by the knock's
    // next update, and the hook ends on its next step. A hook that goes on dragging him, his
    // targeting still on (a knock with no stun, the evolved Mega Knight's uppercut running when the
    // hook lands), is held by no recording.
    if (unit.getView().getState() == GridEntityState.FOLLOWING_REMOVED_BUILDING
        && unit.isActive(CharacterEntity.TARGETING_SLOT)) {
      throw new UnsupportedOperationException(
          row.name()
              + " knocks "
              + unit.name()
              + " while a hook on a building drags it on, its targeting on, which no recording"
              + " holds");
    }
    if (moving && row.getHeight() >= 1) {
      height = arc(row.getDurationMs(), row.getHeight(), counter, height);
      unit.pushHeight(height, height);
    }
    if (counter <= 0) {
      if (moving) {
        unit.resetRoute();
      }
      // On the ground, the landing action runs on the unit: the kept cause as its cause, or the
      // unit itself.
      if (row.getLandingAction() != null && unit.layerGround()) {
        land();
      }
      finish();
    }
    counter -= STEP_MS;
    unit.world().getEvents().knockbackStepped(unit, before, counter, height, tags, isFinished());
  }

  /**
   * Schedules the landing action on the unit, built for it, with its cause: the knock's cause when
   * the row passes it on - kept as it was when it has died or left the battle, its side with it, so
   * an area effect the landing spawns is still the cause's - else the unit itself.
   */
  private void land() {
    WorldEntity cause = row.isPassInstigator() ? instigator : unit;
    BattleAction action =
        unit.world().getActions().build(row.getLandingAction(), unit.world().binding(unit));
    unit.actionHolder().schedule(action, ActionHolder.OWN_DELAY, false, cause.actionHolder());
  }

  /**
   * The height an update pushes: the duration's half, at least 1, in steps of 50 ms; the height
   * over those steps a step's share; while more than the half is left the rise adds (190 - twice
   * the percent of the half elapsed) of a share, in hundredths, and from there the fall takes off
   * (twice the percent of the half gone plus 10). Both divisions by 100 are the multiply-and-shift
   * the code does, rounding toward zero, and the share an unsigned division.
   *
   * @param duration the knock's duration
   * @param top the arc's top
   * @param counter what is left of the knock
   * @param previous the height the last update pushed
   */
  static int arc(int duration, int top, int counter, int previous) {
    int half = duration / 2;
    int den = half > 1 ? half : 1;
    int steps = (int) ((Integer.toUnsignedLong(den) * 0x51eb851fL) >>> 36);
    int per = steps == 0 ? 0 : (int) (Integer.toUnsignedLong(top) / Integer.toUnsignedLong(steps));
    int step;
    if (den >= counter) {
      int q = (den - counter) * 100 / den;
      step = divideBy100((q * 2 + 10) * per, 0xae147ae1);
    } else {
      int q = (duration - counter) * 100 / den;
      step = divideBy100((190 - q * 2) * per, 0x51eb851f);
    }
    return step + previous;
  }

  /**
   * A division by 100, or by -100 with the negative magic number, as a signed multiply keeping the
   * high bits and the sign's carry.
   */
  private static int divideBy100(int x, int magic) {
    long product = (long) x * magic;
    return (int) ((product >> 37) + (product >>> 63));
  }
}
