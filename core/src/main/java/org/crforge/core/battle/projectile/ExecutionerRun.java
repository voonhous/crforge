package org.crforge.core.battle.projectile;

import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ExecutionerEvoProjectile;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * One run of the evolved Executioner's axe controller on its axe: the axe's level and its row's
 * rarity as it starts, and the hit id of the last damage it was asked for. Its update does nothing;
 * it acts through the axe's two hooks, the damage of a hit and the hit itself, which the axe asks
 * of every listed run from the last down.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the level and rarity at the start, the damage hook's stored hit id"
            + " and its amount by the edge distance from the start copy, at the level and rarity"
            + " in the card mode whatever the crown-tower mode, the hit hook's match on that hit"
            + " id, the strong hit's action on the target with the target as its cause and its"
            + " push from the start copy while the sweep is in its first half and the target"
            + " moves; held by ice_axe_barbarians and axe_man_ev1_barbarians. Held by no run: the"
            + " hit hook on a hit other than the last one asked for, which every hit path asks"
            + " first, a strong hit on the way out on a target without a movement component, and"
            + " a hook on an axe whose context left.")
final class ExecutionerRun extends ActionInstance {

  private final ExecutionerEvoProjectile row;
  private final ProjectileEntity axe;
  private final int packedLevel;
  private final RarityTable rarity;

  /** The hit id of the last damage asked for, -1 before the first. */
  private int lastHitId = -1;

  /**
   * @param row the controller
   * @param axe the axe it runs on
   */
  ExecutionerRun(ExecutionerEvoProjectile row, ProjectileEntity axe) {
    super(row);
    this.row = row;
    this.axe = axe;
    this.packedLevel = axe.getPackedLevel();
    this.rarity = axe.getData().rarity();
  }

  @Override
  protected void update(ActionHolder holder) {
    // Its update is a bare return: it acts through the axe's hooks.
  }

  /**
   * The damage hook: the hit id is kept first; with a target, the strong or the plain amount at the
   * run's level by the target's edge distance from the axe's start, in place of the damage handed
   * in.
   *
   * @param damage the damage so far
   * @param hitId the hit's id
   * @param target what the hit lands on, or null
   * @return the damage the hit carries on
   */
  int damage(int damage, int hitId, WorldEntity target) {
    lastHitId = hitId;
    if (target == null) {
      axe.world().axeDamage(axe, null, hitId, damage, damage, null, false);
      return damage;
    }
    int edge = edgeDistance(target);
    boolean strong = edge < row.getStrongDamageRange();
    int amount =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            strong ? row.getStrongDamage() : row.getDamage(),
            packedLevel,
            ScalingMode.CARD_DAMAGE,
            rarity);
    axe.world().axeDamage(axe, target, hitId, damage, amount, edge, strong);
    return amount;
  }

  /**
   * The hit hook, before the hit's damage is taken off: for the hit the damage was last asked for,
   * a strong hit schedules its action on the target, the target its own cause, and, while the axe
   * is in the first half of its sweep, asks for a push of the target away from the axe's start.
   *
   * @param hitId the hit's id
   * @param target what the hit lands on
   */
  void hit(int hitId, WorldEntity target) {
    if (lastHitId != hitId || target == null) {
      return;
    }
    boolean strong = edgeDistance(target) < row.getStrongDamageRange();
    if (!strong) {
      return;
    }
    if (row.getStrongHitAction() != null) {
      axe.world().axeHitAction(axe, target, row.getStrongHitAction().name(), hitId);
      target.actionHolder().schedule(row.getStrongHitAction(), 0, false, target.actionHolder());
    }
    int half = axe.getData().pingpongVisualTimeMs();
    half = (half + (half < 0 ? 1 : 0)) >> 1;
    if (axe.getPingpongTimeMs() < half) {
      axe.world()
          .axePush(
              axe,
              target,
              axe.getStartX(),
              axe.getStartY(),
              row.getFirstStrongHitPushback(),
              hitId);
    }
  }

  /** The target's distance from the axe's start, less the target's collision radius. */
  private int edgeDistance(WorldEntity target) {
    GridEntity at = target.getView();
    int squared =
        FixedMath.guardedSumOfSquares(at.getX() - axe.getStartX(), at.getY() - axe.getStartY());
    return FixedMath.isqrt(squared) - target.getTargetView().radius();
  }
}
