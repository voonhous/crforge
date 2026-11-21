/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * An action that lasts on its owner and rolls it forward again inside a barrel, as the hero
 * Barbarian Barrel's ability does: the barbarian backs off toward its own side for the spawn delay,
 * then becomes a rolling projectile it follows step for step, and stands up again where the
 * projectile ends.
 *
 * <p>As it starts on a character the run asks for a target lock of the character on itself (channel
 * 0, priority 1000), unless it holds one already, takes the spawn delay as its counter and faces
 * the character forward for its side. Each step, after the character is found dead (which finishes
 * the run), faces it forward again and does nothing more while it is CAPTURED. The counter then
 * loses 50 ms, stopping at 0; while it is not out the character steps back by the offset's share of
 * a step, the offset times 50 over the spawn delay, signed for its side. The step that finds it
 * out, the first time, schedules the start action on the character, the character its cause, and
 * launches the reroll projectile from where the character stands, at its height, with the character
 * as launcher and owner, aimed at the projectile's range straight forward for its side; the
 * projectile waits for the next cleanup to be admitted. While the projectile is in the battle each
 * step puts the character on its point and raises the rolling tags for one step. The first step
 * after the projectile has left finishes the run, which releases the lock and relocates the
 * character off water and inside the arena.
 *
 * <p>As the projectile leaves the battle the run forgets it and ends the roll: the end action is
 * scheduled on the character, the character its cause, the character deploys for the deploy
 * duration and faces forward. The deploy animation, the health bar hiding and the target indicator
 * columns are read only by the views.
 *
 * <p>An enemy's deflection may turn the projectile back. The first step of the run that finds the
 * projectile's count of deflections changed since its last step, after it has put the character on
 * the projectile's point, takes the character over to the other side: the character takes the side
 * of the summoner facing its own (that side's king), that summoner's cast and no play, so no
 * champion slot follows it from then on; the run keeps the new count, so a count that went up by
 * two in one step turns it over once. Nothing else changes: it rolls on with the projectile, and
 * its row, runs and lock stay. As a projectile turned an odd number of times leaves, the deflected
 * action is scheduled on the character after the end action, its cause the summoner facing the
 * character's side as it is then; the deploy and the facing follow, for that side.
 *
 * <p>Refused as the row is built: tags while the spawn delay runs, which the run does not raise. As
 * it starts: an owner other than a character, and a clone. As it runs: a turn over to a side whose
 * champion slot follows the character's ability, where which copy the slot follows would rest on
 * the play the character carries over, the summoner's, which no recording holds.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the lock request, the counter and the facing at the start, the"
            + " alive and CAPTURED gates, the backing off by the offset's share, the start action"
            + " and the projectile's launch at its range, the follow and the rolling tags, the end"
            + " on the projectile's leaving (end action, deploy duration, facing) and the finish"
            + " with the release and the relocation; held by ability_hero_barb_log. A turned"
            + " roll: the character taken over to the facing summoner's side and the deflected"
            + " action as it ends, held by reroll_deflected_t316 and reroll_deflected_t327."
            + " Refused: a turn over to a side whose champion slot follows the same ability, a"
            + " clone, and tags while the spawn delay runs.")
public final class BarbBarrelHeroReRoll extends RowAction {

  /**
   * The row's own columns.
   *
   * @param offsetY how far the owner backs off over the spawn delay, signed for side 0
   * @param deployDurationMs how long the owner deploys once the roll ends
   * @param spawnDelayMs how long the owner backs off before the roll starts
   * @param reRollProjectile the projectile the owner rolls in
   * @param rollingTags the tags raised on the owner on each step of the roll
   * @param onReRollStartAction the action scheduled on the owner as the roll starts, or null
   * @param onReRollEndAction the action scheduled on the owner as the roll ends, or null
   * @param onDeflectedAction the action scheduled as a deflected roll ends, or null
   */
  @Builder
  public record Columns(
      int offsetY,
      int deployDurationMs,
      int spawnDelayMs,
      String reRollProjectile,
      long rollingTags,
      BattleAction onReRollStartAction,
      BattleAction onReRollEndAction,
      BattleAction onDeflectedAction) {}

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public BarbBarrelHeroReRoll(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().barbBarrelReRoll(this, holder.passPhase());
  }
}
