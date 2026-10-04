package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that forces its owner's target onto the parent of the area effect that caused it, for a
 * time, with a buff on the owner for as long. Only an area effect with a parent taunts, and only a
 * character is taunted: any other cause or owner does nothing. The owner's run is made and armed at
 * once.
 *
 * <p>The arming picks one duration and buff. A crown tower that would take the forced object as its
 * target gets the crown tower duration and buff. Otherwise an owner that can attack the forced
 * object gets the valid duration and buff, and one that cannot gets the invalid ones, only when the
 * row names an invalid buff. A run left with no duration finishes at once. A building's reference
 * is forced only while the forced object is within its reach: its sight range out from the object's
 * edge, and no nearer than its minimum range. Each step takes 50 ms off the duration; a building
 * owner has its reach tested each step, its reference forced again while the row allows building
 * retargeting. As the duration runs out, the falloff runs down and, spent, gives the reference up
 * unless the owner is attacking. Every finish removes the valid and invalid buffs from the owner
 * while the row removes its buff on death; the crown tower buff is never removed by name.
 *
 * <p>Refused rather than guessed, as the row is built: the end by a stun and the visual effect. As
 * it starts: a taunted building that is not a crown tower, a taunted unit that rides on another or
 * carries riders, or that is in a pathfinding state, and a forced object that flies. As it steps: a
 * unit's taunt past its first step.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the perform's gates, the forced object the area effect's parent,"
            + " the arming's valid branch, the buff with the forced object as its source, the one"
            + " step that ends it and the buff removed as it finishes; held by"
            + " goblin_demolisher_knight, where the Goblin Demolisher's cancelling area effect"
            + " taunts it onto itself for one tick. The crown tower branch, the building's reach"
            + " test, its steps with the building retargeting, and the expiry with the reference"
            + " kept by an attacking tower and given up by a standing one, held by"
            + " ability_hero_knight, where the hero Knight's ability taunts both towers before it."
            + " The arming's invalid branch, the falloff on a lost reach, the re-arm and the run"
            + " ending as its forced object leaves are translated but held by no run. Refused: the"
            + " end by a stun, the visual effect, a taunted building other than a crown tower, a"
            + " unit's taunt past its first step, a taunted rider, carrier or pathfinding unit,"
            + " and a flying forced object.")
@Getter
public final class Taunt extends RowAction {

  /** Whether the run ends when the owner loses its reach on the forced object; on by default. */
  private final boolean resetsOnDistance;

  /** Whether the falloff runs down as the duration runs out; on by default. */
  private final boolean resetOnExpiration;

  /** Whether a building owner's reference is forced again each step it reaches the object. */
  private final boolean allowBuildingRetargeting;

  /** How long the run outlasts a lost reach, in milliseconds. */
  private final int falloffDelayMs;

  /** How long the owner is taunted when it can attack the forced object, in milliseconds. */
  private final int validDurationMs;

  /** The buff put on the owner when it can attack the forced object, or null for none. */
  private final String validTargetBuff;

  /** How long the owner is taunted when it cannot attack the forced object, in milliseconds. */
  private final int invalidDurationMs;

  /** The buff put on the owner when it cannot attack the forced object, or null for none. */
  private final String invalidTargetBuff;

  /** How long a crown tower is taunted, in milliseconds. */
  private final int crownTowerDurationMs;

  /** The buff put on a crown tower, or null for none. */
  private final String crownTowerBuff;

  /** Whether the valid and invalid buffs leave the owner as the run finishes; on by default. */
  private final boolean removeBuffOnDeath;

  /**
   * @param row the row's shared columns
   * @param resetsOnDistance whether the run ends when the owner loses its reach
   * @param resetOnExpiration whether the falloff runs down as the duration runs out
   * @param allowBuildingRetargeting whether a building's reference is forced again each step
   * @param falloffDelayMs how long the run outlasts a lost reach
   * @param validDurationMs how long the owner is taunted when it can attack the forced object
   * @param validTargetBuff the buff put on the owner then, or null for none
   * @param invalidDurationMs how long the owner is taunted when it cannot attack it
   * @param invalidTargetBuff the buff put on the owner then, or null for none
   * @param crownTowerDurationMs how long a crown tower is taunted
   * @param crownTowerBuff the buff put on a crown tower, or null for none
   * @param removeBuffOnDeath whether the valid and invalid buffs leave as the run finishes
   */
  @Builder
  public Taunt(
      ActionRow row,
      boolean resetsOnDistance,
      boolean resetOnExpiration,
      boolean allowBuildingRetargeting,
      int falloffDelayMs,
      int validDurationMs,
      String validTargetBuff,
      int invalidDurationMs,
      String invalidTargetBuff,
      int crownTowerDurationMs,
      String crownTowerBuff,
      boolean removeBuffOnDeath) {
    super(row);
    this.resetsOnDistance = resetsOnDistance;
    this.resetOnExpiration = resetOnExpiration;
    this.allowBuildingRetargeting = allowBuildingRetargeting;
    this.falloffDelayMs = falloffDelayMs;
    this.validDurationMs = validDurationMs;
    this.validTargetBuff = validTargetBuff;
    this.invalidDurationMs = invalidDurationMs;
    this.invalidTargetBuff = invalidTargetBuff;
    this.crownTowerDurationMs = crownTowerDurationMs;
    this.crownTowerBuff = crownTowerBuff;
    this.removeBuffOnDeath = removeBuffOnDeath;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return start(holder, null);
  }

  @Override
  public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
    ActionOwner cause = instigator == null ? null : instigator.getOwner();
    ActionOwner forced = cause == null ? null : cause.areaEffectParent();
    if (forced == null || holder.getOwner() == null) {
      return null;
    }
    return holder.getOwner().taunt(this, cause, forced, holder.passPhase());
  }
}
