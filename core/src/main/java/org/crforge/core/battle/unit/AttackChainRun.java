package org.crforge.core.battle.unit;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.AttackChain;
import org.crforge.core.battle.action.RunOnResolvedObjects;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.target.AttackRange;
import org.crforge.core.pathfinding.target.RangeTest;
import org.crforge.core.pathfinding.target.ReferenceSetter;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;

/**
 * One run of an attack chain on a character: its phase, how many links it has made, the ids of the
 * objects it has reached, its time and whether its phase buff is on.
 *
 * <p>The phase is 0 while the chain waits for an object and 1 while it moves toward one. The run's
 * targeting component is the character's while it is switched on; the reach test and the end take
 * it whatever its switch. See {@link AttackChain} for the steps.
 *
 * <p>The character leaving while the chain runs neither ends nor finishes it: the run goes with the
 * character's holder, and everything the end would undo - the phase buff, the re-selection wait,
 * the reference - is the leaving character's own.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Read line for line: the start, the step, the reach, the next link, the wait, the end and"
            + " the finish. Held by hero_valkyrie and ValkyrieHeroAbilityTest; a character that"
            + " leaves while its chain runs, held by tv_replay_015 up to its later divergence.")
final class AttackChainRun extends ActionInstance {

  /** Milliseconds one step adds to the run's time. */
  private static final int STEP_MS = 50;

  /** How long the begin's re-selection wait and phase buff last, in milliseconds. */
  private static final int HELD_MS = 99999;

  /** The phase while the chain waits for an object. */
  private static final int WAITING = 0;

  /** The phase while the chain moves toward an object. */
  private static final int MOVING = 1;

  private final AttackChain chain;
  private final AttackChain.Columns columns;
  private final CharacterEntity unit;
  private final ActionHolder holder;

  private int phase = WAITING;

  /** How many links the chain has made. */
  private int links;

  /** The ids of the objects reached, in order. */
  private final List<Integer> reached = new ArrayList<>();

  /** The run's time, in milliseconds. */
  private int elapsedMs;

  /** Whether the phase buff is on the character. */
  private boolean buffOn;

  AttackChainRun(AttackChain chain, CharacterEntity unit, ActionHolder holder) {
    super(chain);
    this.chain = chain;
    this.columns = chain.getColumns();
    this.unit = unit;
    this.holder = holder;
  }

  /**
   * The start: with a resolver and a link to make, the first object picked and the chain begun on
   * it; with nothing to pick the run waits. Without a link to make the chain ends at once.
   */
  void start() {
    if (columns.resolver() == null || columns.chainCount() <= 0) {
      end();
      return;
    }
    WorldEntity first = pick();
    if (first != null) {
      begin(first);
    }
  }

  @Override
  protected void update(ActionHolder running) {
    if (isFinished()) {
      return;
    }
    TargetingState t = activeTargeting();
    TargetView current = t == null ? null : t.getReference();
    elapsedMs += STEP_MS;
    if (columns.chainCompleteIf() != null && columns.chainCompleteIf().getAsInt() != 0) {
      schedule(columns.onChainComplete());
      end();
      return;
    }
    if (columns.maxDurationMs() >= 1 && elapsedMs >= columns.maxDurationMs()) {
      end();
      return;
    }
    if (columns.pauseIfAttackSpeedZero() && unit.getBuffs().hitSpeed(100) == 0) {
      return;
    }
    if (phase == WAITING) {
      WorldEntity next = pick();
      if (next != null) {
        begin(next);
        return;
      }
      if (columns.stopMovementWhenAtTarget() && reaches(current)) {
        unit.getView()
            .setPendingFlags(
                unit.getView().getPendingFlags()
                    | unit.getView().getFlagBits().noMoveAllowAttract());
      }
      return;
    }
    if (current == null) {
      nextLink();
      return;
    }
    if (current.alive()) {
      if (reaches(current)) {
        reach();
      }
      return;
    }
    reached.add(current.id());
    schedule(columns.onTargetDied());
    nextLink();
  }

  /**
   * The run's finish, the step's or the end's: the first one schedules the finishing action on the
   * character, the character its own cause.
   */
  @Override
  protected void finish() {
    if (isFinished()) {
      return;
    }
    super.finish();
    schedule(columns.onFinishedAction());
  }

  /**
   * The ranked objects of the resolver around the character, at most {@link AttackChain#RANKED}:
   * the first not reached before that is alive and, with the targeting switched on, that the
   * targeting would take; null for none.
   */
  private WorldEntity pick() {
    RunOnResolvedObjects.Host host = unit.resolvedObjectsHost(chain);
    List<SetIndicatorOnTarget.Candidate> pool =
        columns.cone() == null
            ? host.candidates(columns.filter())
            : host.candidates(columns.filter(), columns.cone());
    List<SetIndicatorOnTarget.Candidate> ranked =
        RunOnResolvedObjects.ranked(
            host, pool, columns.strategies(), AttackChain.RANKED, chain.name(), columns.resolver());
    boolean validates = activeTargeting() != null;
    for (SetIndicatorOnTarget.Candidate candidate : ranked) {
      if (reached.contains(candidate.id())) {
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
   * A link begins on an object, with the targeting switched on: the movement switched on, the
   * re-selection wait held, the reference set onto it through the setter's re-check, the phase buff
   * put on once, and the begin action scheduled before the first link is made.
   */
  private void begin(WorldEntity onto) {
    TargetingState t = activeTargeting();
    if (t == null) {
      return;
    }
    unit.switchComponent(CharacterEntity.MOVEMENT_SLOT, true);
    t.setRetargetCooldownMs(HELD_MS);
    setReference(onto.getTargetView(), false);
    phase = MOVING;
    if (!buffOn && columns.chainPhaseBuff() != null) {
      unit.getBuffs()
          .apply(
              unit.world().buffData(columns.chainPhaseBuff()),
              HELD_MS,
              unit.packedLevel(),
              unit,
              unit.side(),
              unit);
      buffOn = true;
    }
    if (links == 0) {
      schedule(columns.onChainBegan());
    }
  }

  /**
   * The reference reached: counted once, the reach action scheduled while it is alive, and the next
   * link begun.
   */
  private void reach() {
    TargetingState t = activeTargeting();
    if (t == null || t.getReference() == null) {
      return;
    }
    TargetView reference = t.getReference();
    if (reached.contains(reference.id())) {
      return;
    }
    reached.add(reference.id());
    if (reference.alive()) {
      schedule(columns.onReachTarget());
    }
    nextLink();
  }

  /**
   * The next link: counted, the reference given up when the row resets it after a reach, then the
   * chain completed once the count is reached, the reference set onto the next object picked, or,
   * with none, the chain left waiting.
   */
  private void nextLink() {
    TargetingState t = activeTargeting();
    phase = MOVING;
    links++;
    if (t != null && columns.resetTargetAfterReach()) {
      setReference(null, false);
    }
    if (links >= columns.chainCount()) {
      schedule(columns.onChainComplete());
      end();
      return;
    }
    WorldEntity next = pick();
    if (next != null) {
      if (t != null) {
        setReference(next.getTargetView(), true);
      }
      unit.switchComponent(CharacterEntity.MOVEMENT_SLOT, true);
      return;
    }
    phase = WAITING;
    removeBuff();
    if (t != null) {
      setReference(null, false);
      t.setRetargetCooldownMs(0);
    }
  }

  /**
   * The chain's end: the phase buff removed, the re-selection wait cleared and, when the row resets
   * the target after a reach, the reference given up; then the run's finish.
   */
  private void end() {
    removeBuff();
    TargetingState t = unit.getTargeting();
    t.setRetargetCooldownMs(0);
    if (columns.resetTargetAfterReach()) {
      setReference(null, false);
    }
    finish();
  }

  /** Removes the phase buff when it is on. */
  private void removeBuff() {
    if (buffOn && columns.chainPhaseBuff() != null) {
      unit.getBuffs().removeRow(columns.chainPhaseBuff());
      buffOn = false;
    }
  }

  /**
   * Whether the character reaches an object: within its attack range from the object's edge, and no
   * nearer than its minimum range; never for no object.
   */
  private boolean reaches(TargetView object) {
    if (object == null) {
      return false;
    }
    TargetingState t = unit.getTargeting();
    return RangeTest.rangeTest(
        object,
        unit.getView().getX(),
        unit.getView().getY(),
        AttackRange.attackRange(t),
        AttackRange.minRange(t),
        false);
  }

  /** The character's targeting while it is switched on, else null. */
  private TargetingState activeTargeting() {
    return unit.isActive(CharacterEntity.TARGETING_SLOT) ? unit.getTargeting() : null;
  }

  /**
   * Sets the character's reference through the setter, with its re-check and the wind-up reload.
   *
   * @param reference the object, or null to give the reference up
   * @param refresh true to refresh a reference already on the object
   */
  private void setReference(TargetView reference, boolean refresh) {
    ReferenceSetter.setReference(
        unit.getTargeting(),
        reference,
        refresh,
        false,
        false,
        unit.getSelection(),
        unit.getSelection().getOutcome());
  }

  /** Schedules a row on the character, the character its own cause, with no context. */
  private void schedule(String row) {
    if (row == null) {
      return;
    }
    holder.schedule(
        unit.world().getActions().build(row, unit.world().binding(unit)),
        ActionHolder.OWN_DELAY,
        false,
        holder);
  }
}
