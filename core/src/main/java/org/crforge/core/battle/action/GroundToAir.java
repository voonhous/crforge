package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts on its owner and lifts it off the ground for a time, as the hero Wizard's
 * ability does: a ground unit climbs to the row's flying height over the transition, raising
 * FORCE_IS_AIR and the row's climbing tags, is held there for the whole less two transitions,
 * raising FORCE_IS_AIR and the row's held tags, and then comes back down. Its height changes are
 * pushed to the owner, which folds them into its live height at its next pre-hook, as an
 * air-to-ground run's are. As it starts the owner's flying height override takes the row's flying
 * height, which an air-to-ground run starting on it later reads in place of its row's.
 *
 * <p>When the climb reaches the height the owner's path is reset when the row asks for it, and the
 * row's action at the height is scheduled on the owner with the owner as its cause. When the hold
 * ends the descent starts, scheduling the row's action at the start of the descent the same way.
 *
 * <p>Refused as it starts: an owner that is not a character, a clone, a hovering unit, one that
 * rides another or carries riders, and an owner already in the air, whose start reads its live
 * height against the row's. As the hold ends: the descent, whose height, landing relocation and
 * action on the ground no reference holds.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start on a ground owner (the override, the row height kept,"
            + " the climb's counter), the climb's tags, target heights and pushes, the turn at the"
            + " height with the path reset and the scheduled action, and the hold's tags and"
            + " pushes; held by ability_hero_wizard, which the hero's death ends inside the hold."
            + " Refused: a start in the air, the descent, and a clone, hovering, riding or carrying"
            + " owner.")
public final class GroundToAir extends RowAction {

  /** The height the owner climbs to. */
  @Getter private final int flyingHeight;

  /** How long the climb and the descent each take, in milliseconds. */
  @Getter private final int transitionDurationMs;

  /** How long the whole run takes, both transitions included, in milliseconds. */
  @Getter private final int totalDurationMs;

  /** True when the owner's path is reset as the climb reaches the height. */
  @Getter private final boolean resetPathInAir;

  /** True when the owner's path is reset as the run ends on the ground again. */
  @Getter private final boolean resetPathWhenBackToGround;

  /** The tags raised on the owner on each step of the climb. */
  @Getter private final long toAirTags;

  /** The tags raised on the owner on each step of the hold. */
  @Getter private final long onAirTags;

  /** The tags raised on the owner on each step of the descent. */
  @Getter private final long toGroundTags;

  /** The action scheduled on the owner as the climb reaches the height, or null for none. */
  @Getter private final BattleAction onFlyHeightReached;

  /** The action scheduled on the owner as the descent starts, or null for none. */
  @Getter private final BattleAction onStartDescending;

  /** The action scheduled on the owner as the descent ends, or null for none. */
  @Getter private final BattleAction onGround;

  /**
   * @param row the row's shared columns
   * @param flyingHeight the height the owner climbs to
   * @param transitionDurationMs how long the climb and the descent each take
   * @param totalDurationMs how long the whole run takes
   * @param resetPathInAir true when the path is reset as the climb reaches the height
   * @param resetPathWhenBackToGround true when the path is reset as the run ends
   * @param toAirTags the tags of each climbing step
   * @param onAirTags the tags of each held step
   * @param toGroundTags the tags of each descending step
   * @param onFlyHeightReached the action at the height, or null
   * @param onStartDescending the action at the start of the descent, or null
   * @param onGround the action at the end of the descent, or null
   */
  public GroundToAir(
      ActionRow row,
      int flyingHeight,
      int transitionDurationMs,
      int totalDurationMs,
      boolean resetPathInAir,
      boolean resetPathWhenBackToGround,
      long toAirTags,
      long onAirTags,
      long toGroundTags,
      BattleAction onFlyHeightReached,
      BattleAction onStartDescending,
      BattleAction onGround) {
    super(row);
    this.flyingHeight = flyingHeight;
    this.transitionDurationMs = transitionDurationMs;
    this.totalDurationMs = totalDurationMs;
    this.resetPathInAir = resetPathInAir;
    this.resetPathWhenBackToGround = resetPathWhenBackToGround;
    this.toAirTags = toAirTags;
    this.onAirTags = onAirTags;
    this.toGroundTags = toGroundTags;
    this.onFlyHeightReached = onFlyHeightReached;
    this.onStartDescending = onStartDescending;
    this.onGround = onGround;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().groundToAir(this, holder.passPhase());
  }
}
