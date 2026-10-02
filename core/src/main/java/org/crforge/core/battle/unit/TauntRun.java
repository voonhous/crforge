package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.Taunt;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * One run of a taunt on a unit: the arming as it starts, one step from the holder's run pass, and
 * the finish that removes the taunt's buff from the unit. Each arming and step is told to the
 * battle's observers with the calls it made, in order.
 *
 * <p>The taunt's other columns keep the loader's defaults, which the row refuses to change: the
 * reach is tested by distance, which a unit that is neither a building nor a rider always passes;
 * the run ends as its duration runs out, with no falloff after it; the stun does not end it; and
 * the buff goes as the run finishes.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the arming's valid and invalid branches, the step that ends a"
            + " one-step taunt and the finish that removes its buff; held by"
            + " goblin_demolisher_knight. The end as its forced object leaves is translated but"
            + " held by no run. A taunt that outlasts one step, and with it the mark and the"
            + " re-check of the reference, is refused by its row.")
final class TauntRun extends ActionInstance {

  /** Milliseconds one step takes off the duration. */
  private static final int STEP_MS = 50;

  private final Taunt taunt;
  private final CharacterEntity unit;

  /** The object the unit is forced onto; null once it has left. */
  private WorldEntity forced;

  /** What is left of the taunt, in milliseconds. */
  private int durationMs;

  /** The falloff after the duration runs out; the row keeps it at 0. */
  private int falloffMs;

  TauntRun(Taunt taunt, CharacterEntity unit, WorldEntity forced) {
    super(taunt);
    this.taunt = taunt;
    this.unit = unit;
    this.forced = forced;
  }

  /**
   * The start: the falloff, then the arming. The unit can attack the forced object when it reaches
   * the object's layer - the air with an air attack, the ground with a ground attack or with no air
   * attack - and the object is a building it may attack or a troop it may attack. Then the unit is
   * forced onto the object, unless it is dashing or winding up a dash, and the buff is put on it.
   * When it cannot, the run has no duration and finishes.
   */
  void arm() {
    List<String> calls = new ArrayList<>();
    UnitData row = unit.getData();
    boolean building = forced.getTargetView().building();
    boolean able =
        forced.getTargetView().air() ? row.attacksAir() : row.attacksGround() || !row.attacksAir();
    boolean valid = able && !(building ? row.targetOnlyTroops() : row.targetOnlyBuildings());
    if (valid) {
      durationMs = taunt.getValidDurationMs();
      if (unit.getView().getState() != GridEntityState.DASHING
          && unit.getTargeting().getDashWindupMs() <= 0) {
        unit.tauntReference(forced);
        calls.add("set_target " + forced.name() + " 0 0 1");
        unit.raiseLockTarget();
        calls.add("raise LOCK_TARGET");
        unit.getTargeting().setRetargetCooldownMs(Math.max(durationMs, 0));
        calls.add("remaining " + durationMs);
      }
      if (taunt.getValidTargetBuff() != null) {
        unit.tauntBuff(taunt.getValidTargetBuff(), durationMs, forced);
        calls.add(
            "apply_buff %s %d level %d source %s side %d"
                .formatted(
                    taunt.getValidTargetBuff(),
                    durationMs,
                    forced.packedLevel(),
                    forced.name(),
                    forced.side()));
      }
    }
    if (durationMs == 0) {
      end(calls);
    }
    unit.tauntStepped(forced, durationMs, falloffMs, calls);
  }

  /**
   * One step: the duration loses 50 ms; once it has run out the falloff loses as much, and with
   * that gone too the re-selection wait is cleared and, unless the unit is attacking, its reference
   * is given up, keeping its wind-up; then the run finishes.
   */
  @Override
  protected void update(ActionHolder holder) {
    int state = unit.getView().getState();
    if (state == GridEntityState.SPAWN_PATHFIND || state == GridEntityState.INGAME_PATHFIND) {
      throw new UnsupportedOperationException(
          taunt.name() + " steps on " + unit.name() + " while it pathfinds, which is not modelled");
    }
    List<String> calls = new ArrayList<>();
    durationMs -= STEP_MS;
    if (durationMs > 0) {
      throw new UnsupportedOperationException(
          taunt.name() + " lasts past its first step, which is not modelled");
    }
    falloffMs -= STEP_MS;
    if (falloffMs <= 0) {
      unit.getTargeting().setRetargetCooldownMs(0);
      calls.add("remaining 0");
      if (state != GridEntityState.ATTACKING) {
        unit.getTargeting().setRetargetCooldownMs(0);
        calls.add("remaining 0");
        unit.tauntDrop(true);
        calls.add("set_target null 0 1 0");
      }
    }
    end(calls);
    unit.tauntStepped(forced, durationMs, falloffMs, calls);
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
    unit.getTargeting().setRetargetCooldownMs(0);
    calls.add("remaining 0");
    unit.tauntDrop(false);
    calls.add("set_target null 0 0 0");
    end(calls);
    unit.tauntStepped(null, durationMs, falloffMs, calls);
  }

  /** The finish, once: the run marked finished and the buff removed from the unit. */
  private void end(List<String> calls) {
    if (isFinished()) {
      return;
    }
    finish();
    calls.add("finish");
    if (taunt.getValidTargetBuff() != null) {
      unit.getBuffs().removeRow(taunt.getValidTargetBuff());
      calls.add("remove_buff " + taunt.getValidTargetBuff());
    }
  }
}
