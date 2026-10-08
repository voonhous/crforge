package org.crforge.core.battle.action;

import java.util.List;

/**
 * What a guard's run asks of the battle about the guard it runs on and what stands around it.
 * Objects are named by their ids, as the run keeps them.
 */
public interface GuardHost {

  /** The guard's entity state. */
  int state();

  /** What is left of the guard's deploy countdown, in milliseconds. */
  int deployCountdownMs();

  /** Cuts the guard's deploy short: the countdown to 0, then the standing state asked for. */
  void cutDeploy();

  /** The guard's side's direction along the arena's length: +1 for side 0, -1 for side 1. */
  int teamSign();

  /**
   * Makes an area effect at the guard's point for its side and at its level, the guard its source
   * and parent and, for a row that follows its parent, the object it follows; handed to the holder,
   * so it first updates on the next tick.
   *
   * @param action the guard spawn row's name
   * @param row the area effect's row
   * @param phase the pending pass the run steps in
   * @return its id
   */
  int spawnArea(String action, String row, int phase);

  /**
   * Ends an area effect the run made, as its finish does: its countdown below 0, so the next
   * cleanup after its update removes it.
   *
   * @param id the area effect
   */
  void endArea(int id);

  /** True when the guard has a targeting component. */
  boolean hasTargeting();

  /**
   * A point clamped into the arena: each axis to the cells' extent less one, a coordinate at or
   * below 0 giving 0.
   *
   * @return the clamped point, x then y
   */
  int[] clamp(int x, int y);

  /**
   * Starts the guard's charge toward a point: a dash with no reference, stopping short by the
   * guard's collision radius alone and never on a reference coming into range.
   */
  void charge(int x, int y);

  /**
   * One step of the run, told to the battle's observers.
   *
   * @param charging whether the run is charging after the step
   * @param tags the tags the run sets after the step
   * @param done whether the step finished the run
   * @param calls what the step did, in order
   */
  void stepped(boolean charging, long tags, boolean done, List<String> calls);
}
