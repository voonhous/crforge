package org.crforge.core.pathfinding.target;

import java.util.List;

/**
 * The game-mode answers the default target selection asks before it ranks anything.
 *
 * <p>Each of them switches a whole branch of the selection on or off, and the standard 1v1 mode
 * answers all of them the same way every tick. {@link #standard1v1()} spells those answers out with
 * a note on what each one turns off, so a different mode can be added later by supplying its own.
 *
 * @param alternateSeedActive true when the mode offers a second seed the selection may switch to
 * @param alternateSeed that second seed; null in a mode that does not offer one
 * @param suppressTowerSeed true when a seed that answers the tower flag is dropped rather than used
 * @param alternateGoalMode true when the mode has its own goal, which ends the selection at once
 * @param specialObjectsActive true when the mode ranks a separate list of objects instead of the
 *     side's towers
 * @param specialObjects that separate list; empty in a mode that does not use one
 * @param registeredObjectCount number of registered map objects across both sides; half of it is
 *     the candidate count a side is expected to have
 * @param pluginOverride true when the mode's plugin object overrides the distance threshold
 * @param unitSide side of the unit doing the selecting, used only by the special-object branch
 * @param excludedConfigKey configuration row no candidate may be taken through
 */
public record DefaultSelectionQueries(
    boolean alternateSeedActive,
    TargetView alternateSeed,
    boolean suppressTowerSeed,
    boolean alternateGoalMode,
    boolean specialObjectsActive,
    List<TargetView> specialObjects,
    int registeredObjectCount,
    boolean pluginOverride,
    int unitSide,
    String excludedConfigKey) {

  /**
   * The answers of the standard 1v1 mode.
   *
   * <ul>
   *   <li>there is no alternate seed, so the seed stays the opposing side's king tower;
   *   <li>the tower seed is not suppressed;
   *   <li>the mode has no goal of its own, so the selection always runs to the end;
   *   <li>there is no special object list, so the side's towers are what gets ranked;
   *   <li>four registered map objects, giving an expected candidate count of two;
   *   <li>no plugin override, so the seed's own distance is the threshold to beat.
   * </ul>
   *
   * <p>The unit's side is only read by the special-object branch, which is off here, so it is left
   * at -1.
   */
  public static DefaultSelectionQueries standard1v1() {
    return new DefaultSelectionQueries(
        false, null, false, false, false, List.of(), 4, false, -1, "HeistStorage3");
  }

  /** Half the registered object count: how many candidates one side is expected to contribute. */
  public int expectedCandidateCount() {
    return registeredObjectCount / 2;
  }
}
