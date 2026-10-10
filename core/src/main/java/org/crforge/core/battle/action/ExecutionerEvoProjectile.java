/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Executioner's axe controller: a run on the axe that keeps its level and rarity and
 * listens to its hits. Each hit along the axe's way takes, in place of the axe's own damage, the
 * strong damage when the target's distance from the axe's start, less its radius, is below the
 * strong range, else the plain damage, both at the axe's level. A strong hit then schedules its hit
 * action on the target, the target its own cause, and, while the axe is still on its way out, asks
 * for a push of the target away from the axe's start.
 *
 * <p>Refused as the row is built: the action for a plain hit, which the class reads under another
 * name than the row writes, a push below 1, and the shared columns its run does not read.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the level and rarity at the start, the damage by the distance"
            + " from the start copy less the radius, both amounts at the level, the strong hit's"
            + " action on the target and its push on the way out; held by the reference battle"
            + " evo_axeman_vs_musketeer. Refused: the plain hit's action, a push below 1 and the"
            + " shared columns.")
public final class ExecutionerEvoProjectile extends RowAction {

  /** The plain damage, at the first level. */
  @Getter private final int damage;

  /** The strong damage, at the first level. */
  @Getter private final int strongDamage;

  /** The distance from the axe's start, less the target's radius, below which a hit is strong. */
  @Getter private final int strongDamageRange;

  /** How far a strong hit on the way out pushes its target. */
  @Getter private final int firstStrongHitPushback;

  /** The action a strong hit schedules on its target, or null for none. */
  @Getter private final BattleAction strongHitAction;

  /**
   * @param row the row's shared columns
   * @param damage the plain damage, at the first level
   * @param strongDamage the strong damage, at the first level
   * @param strongDamageRange the distance below which a hit is strong
   * @param firstStrongHitPushback how far a strong hit on the way out pushes its target
   * @param strongHitAction the action a strong hit schedules on its target, or null
   */
  public ExecutionerEvoProjectile(
      ActionRow row,
      int damage,
      int strongDamage,
      int strongDamageRange,
      int firstStrongHitPushback,
      BattleAction strongHitAction) {
    super(row);
    this.damage = damage;
    this.strongDamage = strongDamage;
    this.strongDamageRange = strongDamageRange;
    this.firstStrongHitPushback = firstStrongHitPushback;
    this.strongHitAction = strongHitAction;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().executionerController(this, holder.passPhase());
  }
}
