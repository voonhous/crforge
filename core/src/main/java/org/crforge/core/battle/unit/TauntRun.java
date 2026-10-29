/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.Taunt;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.PathfindingGlobals;
import org.crforge.core.pathfinding.target.AttackRange;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.SightRange;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * One run of a taunt on a character, a unit or a building (a crown tower or any other, as a
 * Cannon): the arming as it starts, one step from the holder's run pass, and the finish that
 * removes the taunt's buffs from it. Each arming and step is told to the battle's observers with
 * the calls it made, in order.
 *
 * <p>The reach is tested only for a building owner or a rider, an owner that rides on another, as
 * the Spear Goblins on a Goblin Giant: the forced object must lie within the owner's sight range,
 * its collision radius added, out from the object's edge, and no nearer than the owner's minimum
 * range in from it. A rider takes a building owner's steps and, onto a unit, the reach test in
 * front of the forced reference at the arming.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the arming's crown tower, valid and invalid branches, the reach"
            + " test, the step that ends a one-step taunt, a building's steps with the building"
            + " retargeting and the expiry with and without an attacking owner, and the finish"
            + " that removes its buffs; held by the reference battles card_GoblinDemolisher and"
            + " ability_hero_knight. The falloff on a lost reach, the re-arm and the end as its"
            + " forced object leaves are translated but held by no run. A unit's steps: the"
            + " reference kept while it stays on the forced object, or marked in the targeting"
            + " queue and forced back onto it, nothing while the targeting component is off. A"
            + " building other than a crown tower takes the building steps: held by the hero"
            + " Knight's taunt on a Cannon and an Inferno Tower within and beyond their reach. A"
            + " rider takes a building's reach and steps, and a carrier's riders are taunted with"
            + " it: held by recorded battles of the hero Knight's taunt on Goblin Giants and hero"
            + " Ice Wizards. The arming's air rule, an object in the air judged by the owner's"
            + " air attack unless FORCE_IS_AIR holds it there, is settled line for line; recorded"
            + " battles of the Goblin Demolisher taunted onto itself as an uppercut lifts it play"
            + " on with it, though they do not tell it from the ground rule. The step that lets"
            + " an underground owner go is settled line for line and held by recorded battles of"
            + " a taunted Mighty Miner switching lanes; a unit routing across visible is refused."
            + " A building's step that reaches a captured object gives it up and does not force it"
            + " again: settled line for line, held by tower_taunt_snowball_captured (a crown"
            + " tower) and cannon_taunt_captured_knight (a Cannon), a hero Knight the evolved"
            + " Snowball's roll carries.")
final class TauntRun extends ActionInstance {

  /** Milliseconds one step takes off the duration. */
  private static final int STEP_MS = 50;

  private final Taunt taunt;
  private final WorldEntity owner;

  /** The object the owner is forced onto; null once it has left. */
  private WorldEntity forced;

  /** What is left of the taunt, in milliseconds. */
  private int durationMs;

  /** What is left of the grip once the reach or the duration is gone, in milliseconds. */
  private int falloffMs;

  TauntRun(Taunt taunt, WorldEntity owner, WorldEntity forced) {
    super(taunt);
    this.taunt = taunt;
    this.owner = owner;
    this.forced = forced;
  }

  /** The start: the falloff loaded from the row, then the arming. */
  void start() {
    falloffMs = taunt.getFalloffDelayMs();
    arm();
  }

  /**
   * The arming. A row that tests the reach by distance finishes at once when the owner does not
   * reach the forced object. Then one duration and buff is picked:
   *
   * <ul>
   *   <li>a crown tower that would take the forced object as its target: the crown tower duration
   *       and buff, its reference forced onto the object while it reaches it;
   *   <li>otherwise an owner that can attack the object - the air with an air attack, the ground
   *       with a ground attack or with no air attack, and a building it may attack or a troop it
   *       may attack: the valid duration and buff, its reference forced while it reaches a building
   *       object, unless it is dashing or winding up a dash. An object in the air that carries
   *       FORCE_IS_AIR, a ground unit lifted by an uppercut or a knock, counts as on the ground
   *       here;
   *   <li>otherwise, only when the row names an invalid buff: the invalid duration and buff, its
   *       reference forced while it reaches a building object.
   * </ul>
   *
   * Forcing the reference locks the owner's selector from its next pre-hook and makes its
   * re-selection wait for the duration; the buff has the forced object as its source. A run left
   * with no duration finishes.
   */
  void arm() {
    List<String> calls = new ArrayList<>();
    if (taunt.isResetsOnDistance() && !reach()) {
      end(calls);
      report(calls);
      return;
    }
    UnitData row = owner.getData();
    TargetView onto = forced.getTargetView();
    boolean building = onto.building();
    // The forced object's layer is asked now, from its tag word and its height; one in the air
    // only because FORCE_IS_AIR holds it there, as a ground unit an uppercut or a knock lifted, is
    // judged by the ground rule.
    boolean flies =
        forced.layerAir() && (forced.getView().getFlags() & forced.world().forceIsAir()) == 0;
    boolean able = flies ? row.attacksAir() : row.attacksGround() || !row.attacksAir();
    boolean valid = able && !(building ? row.targetOnlyTroops() : row.targetOnlyBuildings());
    // A building object puts the reach test in front of the forced reference; so does a rider
    // owner.
    boolean recheck = building || rider();
    boolean crown = owner.getTargetView().crownTower();
    if (crown && owner.getSelection().validate(onto, ReferenceValidator.MODE_TAKE)) {
      durationMs = taunt.getCrownTowerDurationMs();
      if (reach()) {
        force(false, calls);
      }
      buff(taunt.getCrownTowerBuff(), calls);
    } else if (valid) {
      durationMs = taunt.getValidDurationMs();
      if ((!recheck || reach())
          && owner.getView().getState() != GridEntityState.DASHING
          && owner.getTargeting().getDashWindupMs() <= 0) {
        force(true, calls);
      }
      buff(taunt.getValidTargetBuff(), calls);
    } else if (taunt.getInvalidTargetBuff() != null) {
      durationMs = taunt.getInvalidDurationMs();
      if (!recheck || reach()) {
        force(true, calls);
      }
      buff(taunt.getInvalidTargetBuff(), calls);
    }
    if (durationMs == 0) {
      end(calls);
    }
    report(calls);
  }

  /**
   * One step. An owner underground is let go at once: in its spawn pathfinding state, or in its
   * in-game pathfinding state (routing across to where its ability sent it, as a Mighty Miner
   * switching lanes) while its row does not keep it visible there. A unit routing across visible is
   * refused: its step is settled by the same reading, the unit's own steps below, but no recording
   * holds it. Otherwise the duration loses 50 ms; once it has run out, the falloff loses as much
   * when the row ends the run as the duration expires, and with that gone too the re-selection wait
   * is cleared and, unless the owner is attacking, its reference is given up, keeping its wind-up;
   * then the run finishes. While the duration lasts: a forced object no longer alive lets the owner
   * go; a captured one stops being its reference; and a building owner that reaches the object has
   * its reference forced again when the row allows building retargeting and the object is not
   * captured, and its falloff reloaded when the row tests the reach by distance, while one that
   * does not reach it loses falloff, let go once that is spent, and gives up the reference under
   * building retargeting. A unit's step is {@link #stepUnit}. A reference on the forced object
   * locks the selector again.
   */
  @Override
  protected void update(ActionHolder holder) {
    int state = owner.getView().getState();
    boolean unit = !owner.getTargetView().building();
    List<String> calls = new ArrayList<>();
    // The owner's own underground test lets it go before anything else: a unit tunnelling to its
    // placement, or routing across to where its ability sent it (a Mighty Miner switching lanes)
    // unless its row keeps it visible there.
    if (owner.underground() || state == GridEntityState.SPAWN_PATHFIND) {
      drop(calls);
      report(calls);
      return;
    }
    if (unit && state == GridEntityState.INGAME_PATHFIND) {
      throw new UnsupportedOperationException(
          taunt.name()
              + " steps on "
              + owner.name()
              + " while it routes across visible, which is not modelled");
    }
    durationMs -= STEP_MS;
    if (durationMs <= 0) {
      if (taunt.isResetOnExpiration()) {
        falloffMs -= STEP_MS;
        if (falloffMs <= 0) {
          remaining(0, calls);
          if (state != GridEntityState.ATTACKING) {
            remaining(0, calls);
            owner.tauntDrop(true);
            calls.add("set_target null 0 1 0");
          }
        }
      }
      end(calls);
      report(calls);
      return;
    }
    if (forced == null || !forced.getTargetView().alive()) {
      drop(calls);
      report(calls);
      return;
    }
    if (unit && !rider()) {
      stepUnit(calls);
      closeStep(calls);
      return;
    }
    if (captured() && referenced()) {
      remaining(0, calls);
      owner.tauntDrop(false);
      calls.add("set_target null 0 0 0");
    }
    if (reach()) {
      // A captured object is not forced again: the step above gave it up, and it stays given up
      // while the capture holds it.
      if (taunt.isAllowBuildingRetargeting() && !captured()) {
        owner.tauntReference(forced, true);
        calls.add("set_target " + forced.name() + " 0 0 1");
        owner.raiseLockTarget();
        calls.add("raise LOCK_TARGET");
      }
      if (taunt.isResetsOnDistance()) {
        falloffMs = taunt.getFalloffDelayMs();
      }
    } else {
      if (taunt.isResetsOnDistance()) {
        falloffMs -= STEP_MS;
        if (falloffMs <= 0) {
          drop(calls);
        }
      }
      if (taunt.isAllowBuildingRetargeting() && referenced()) {
        remaining(0, calls);
        owner.tauntDrop(false);
        calls.add("set_target null 0 0 0");
      }
    }
    closeStep(calls);
  }

  /**
   * A unit's step while the duration lasts. With its targeting component off (a stun or a freeze)
   * nothing is done. A reference still on the forced object is given up, the wind-up kept, only
   * when the object has been captured. A reference on anything else, or none, is forced back onto
   * the object - marked in the unit's targeting queue at priority 1, set without the setter's
   * re-check, the selector locked and the re-selection wait set to what is left of the duration -
   * unless the object is captured or the unit is dashing, winding up a dash or carries the dashing
   * tag.
   */
  private void stepUnit(List<String> calls) {
    if (!owner.isActive(CharacterEntity.TARGETING_SLOT)) {
      return;
    }
    if (referenced()) {
      if (captured()) {
        owner.tauntRelease();
        calls.add("set_target null 0 1 1");
        remaining(0, calls);
      }
      return;
    }
    if (captured()
        || owner.getView().getState() == GridEntityState.DASHING
        || owner.getTargeting().getDashWindupMs() > 0
        || (owner.getView().getFlags() & owner.getView().getFlagBits().dashing()) != 0) {
      return;
    }
    ((CharacterEntity) owner).markTarget(forced, 1);
    calls.add("mark " + forced.name() + " 1");
    force(true, calls);
  }

  /** The step's close: a reference on the forced object locks the selector again. */
  private void closeStep(List<String> calls) {
    if (referenced()) {
      owner.raiseLockTarget();
      calls.add("raise LOCK_TARGET");
    }
    report(calls);
  }

  /**
   * A second start of the row caused by an area effect whose parent is not the forced object: that
   * parent becomes the forced object, the falloff is reloaded and the run is armed again.
   */
  @Override
  protected void retrigger(ActionHolder holder, ActionHolder instigator) {
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    ActionOwner parent = cause == null ? null : cause.areaEffectParent();
    if (parent == null || parent == forced) {
      return;
    }
    if (!(parent instanceof WorldEntity onto)) {
      throw new UnsupportedOperationException(
          taunt.name()
              + " re-taunts "
              + owner.name()
              + " onto an object not in the battle, which is not modelled");
    }
    forced = onto;
    falloffMs = taunt.getFalloffDelayMs();
    arm();
  }

  /**
   * The forced object leaving the battle: the re-selection wait cleared, the reference given up,
   * and the run finished.
   */
  @Override
  protected void objectLeft(int leftId) {
    if (forced == null || forced.getId() != leftId) {
      return;
    }
    forced = null;
    List<String> calls = new ArrayList<>();
    remaining(0, calls);
    owner.tauntDrop(false);
    calls.add("set_target null 0 0 0");
    end(calls);
    report(calls);
  }

  /**
   * Whether the owner reaches the forced object: always, but for a building owner, which must have
   * the object's centre within its sight reach out from the object's edge and no nearer than its
   * minimum range in from it, compared on 32-bit squares without a sign.
   */
  private boolean reach() {
    if (!owner.getTargetView().building() && !rider()) {
      return true;
    }
    TargetingState t = owner.getTargeting();
    TargetingConfig cfg = t.getConfig();
    int sight = SightRange.sightRange(t);
    if (PathfindingGlobals.ADD_CHARACTER_RANGE_TO_RADIUS) {
      sight += cfg.collisionRadius();
    }
    TargetView onto = forced.getTargetView();
    TargetView self = owner.getTargetView();
    int radius = onto.radius();
    int outer = radius + sight;
    int dx = onto.x() - self.x();
    int dy = onto.y() - self.y();
    int squared = dy * dy + dx * dx;
    if (Integer.compareUnsigned(outer * outer, squared) < 0) {
      return false;
    }
    int inner = Math.max(AttackRange.minRange(t) - radius, 0);
    return Integer.compareUnsigned(inner * inner, squared) <= 0;
  }

  /** Whether the owner rides on another unit, which takes a building owner's reach and steps. */
  private boolean rider() {
    return owner instanceof CharacterEntity unit && unit.getParent() != null;
  }

  /** Whether the owner's reference is the forced object now. */
  private boolean referenced() {
    return forced != null && owner.getTargeting().getReference() == forced.getTargetView();
  }

  /** Whether the forced object carries the captured tag. */
  private boolean captured() {
    return (forced.getView().getFlags() & forced.getView().getFlagBits().captured()) != 0;
  }

  /**
   * Forces the owner's reference onto the object, locks its selector and makes its re-selection
   * wait for the duration.
   *
   * @param skipRecheck true to set the reference without the setter's re-check
   */
  private void force(boolean skipRecheck, List<String> calls) {
    owner.tauntReference(forced, skipRecheck);
    calls.add("set_target " + forced.name() + " 0 0 " + (skipRecheck ? 1 : 0));
    owner.raiseLockTarget();
    calls.add("raise LOCK_TARGET");
    remaining(durationMs, calls);
  }

  /** Puts a buff on the owner for the duration, the forced object its source; none for null. */
  private void buff(String buff, List<String> calls) {
    if (buff == null) {
      return;
    }
    owner.tauntBuff(buff, durationMs, forced);
    calls.add(
        "apply_buff %s %d level %d source %s side %d"
            .formatted(buff, durationMs, forced.packedLevel(), forced.name(), forced.side()));
  }

  /** Sets what is left of the owner's re-selection wait, never below 0. */
  private void remaining(int ms, List<String> calls) {
    owner.getTargeting().setRetargetCooldownMs(Math.max(ms, 0));
    calls.add("remaining " + ms);
  }

  /** Lets the owner go: no re-selection wait, no reference, and the run finished. */
  private void drop(List<String> calls) {
    remaining(0, calls);
    owner.tauntDrop(false);
    calls.add("set_target null 0 0 0");
    end(calls);
  }

  /**
   * The finish, once: the run marked finished and, while the row removes its buff on death, the
   * valid and invalid buffs removed from the owner. The crown tower buff is not removed here.
   */
  private void end(List<String> calls) {
    if (isFinished()) {
      return;
    }
    finish();
    calls.add("finish");
    if (!taunt.isRemoveBuffOnDeath()) {
      return;
    }
    for (String buff : new String[] {taunt.getValidTargetBuff(), taunt.getInvalidTargetBuff()}) {
      if (buff != null) {
        owner.getBuffs().removeRow(buff);
        calls.add("remove_buff " + buff);
      }
    }
  }

  /** Tells the observers what the arming or the step did. */
  private void report(List<String> calls) {
    owner.tauntStepped(forced, durationMs, falloffMs, calls);
  }
}
