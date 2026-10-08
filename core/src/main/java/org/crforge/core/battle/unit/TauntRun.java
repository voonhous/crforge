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
 * One run of a taunt on a character, a unit or a crown tower: the arming as it starts, one step
 * from the holder's run pass, and the finish that removes the taunt's buffs from it. Each arming
 * and step is told to the battle's observers with the calls it made, in order.
 *
 * <p>The reach is tested only for a building owner (a taunted rider, the other owner the test
 * applies to, is refused as the taunt starts): the forced object must lie within the owner's sight
 * range, its collision radius added, out from the object's edge, and no nearer than the owner's
 * minimum range in from it.
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
            + " queue and forced back onto it, nothing while the targeting component is off.")
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
   *       object, unless it is dashing or winding up a dash;
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
    boolean able = onto.air() ? row.attacksAir() : row.attacksGround() || !row.attacksAir();
    boolean valid = able && !(building ? row.targetOnlyTroops() : row.targetOnlyBuildings());
    // A building object puts the reach test in front of the forced reference; so would a rider
    // owner, which the taunt refuses as it starts.
    boolean recheck = building;
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
   * One step. An owner in its spawn pathfinding state is let go at once. Otherwise the duration
   * loses 50 ms; once it has run out, the falloff loses as much when the row ends the run as the
   * duration expires, and with that gone too the re-selection wait is cleared and, unless the owner
   * is attacking, its reference is given up, keeping its wind-up; then the run finishes. While the
   * duration lasts: a forced object no longer alive lets the owner go; a captured one stops being
   * its reference; and a building owner that reaches the object has its reference forced again when
   * the row allows building retargeting and its falloff reloaded when the row tests the reach by
   * distance, while one that does not reach it loses falloff, let go once that is spent, and gives
   * up the reference under building retargeting. A unit's step is {@link #stepUnit}. A reference on
   * the forced object locks the selector again.
   */
  @Override
  protected void update(ActionHolder holder) {
    int state = owner.getView().getState();
    boolean unit = !owner.getTargetView().building();
    if (unit
        && (state == GridEntityState.SPAWN_PATHFIND || state == GridEntityState.INGAME_PATHFIND)) {
      throw new UnsupportedOperationException(
          taunt.name()
              + " steps on "
              + owner.name()
              + " while it pathfinds, which is not modelled");
    }
    List<String> calls = new ArrayList<>();
    if (state == GridEntityState.SPAWN_PATHFIND) {
      drop(calls);
      report(calls);
      return;
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
    if (unit) {
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
      if (taunt.isAllowBuildingRetargeting()) {
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
    if (!(parent instanceof WorldEntity onto) || onto.getTargetView().air()) {
      throw new UnsupportedOperationException(
          taunt.name()
              + " re-taunts "
              + owner.name()
              + " onto a flying object or one not in the battle, which is not modelled");
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
    if (!owner.getTargetView().building()) {
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
