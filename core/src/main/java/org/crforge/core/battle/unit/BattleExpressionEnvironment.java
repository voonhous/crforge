package org.crforge.core.battle.unit;

import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.ExpressionEnvironment;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The battle as an expression sees it, from one entity: the context every function starts from.
 *
 * <p>Every name of the battle's function table resolves, so every expression of the data compiles.
 * The battle answers the functions its milestones have ported; a call to any other fails loudly
 * rather than answering a guess.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: king_tower_damaged as the context side's king tower below its maximum hit"
            + " points, and tower_destroyed as that side down to fewer than two princess towers."
            + " Supplied, not settled: the two co-op functions answer 0 in a battle of two"
            + " players. Not modelled: the other 43 functions, which fail when called.")
final class BattleExpressionEnvironment implements ExpressionEnvironment {

  private static final int KING_TOWER_DAMAGED = BattleFunctions.id("king_tower_damaged");
  private static final int COOP_KING_TOWER_DAMAGED = BattleFunctions.id("coop_king_tower_damaged");
  private static final int TOWER_DESTROYED = BattleFunctions.id("tower_destroyed");
  private static final int COOP_TOWER_DESTROYED = BattleFunctions.id("coop_tower_destroyed");

  /** Princess towers a side must keep for tower_destroyed to answer false. */
  private static final int PRINCESS_TOWERS_KEPT = 2;

  private final WorldEntity context;
  private final BattleWorld world;

  /**
   * @param context the entity every function starts from
   * @param world the battle it belongs to
   */
  BattleExpressionEnvironment(WorldEntity context, BattleWorld world) {
    this.context = context;
    this.world = world;
  }

  @Override
  public Function resolve(String name) {
    BattleFunctions.Entry entry = BattleFunctions.byName(name);
    return entry == null
        ? null
        : new Function(entry.id(), entry.minArguments(), entry.maxArguments());
  }

  @Override
  public int call(int id, int[] arguments) {
    if (id == KING_TOWER_DAMAGED) {
      TowerEntity king = world.kingTower(context.side());
      return king != null
              && king.getHitPoints() != null
              && king.getHitPoints().getHitPoints() < king.getHitPoints().getMaximum()
          ? 1
          : 0;
    }
    if (id == TOWER_DESTROYED) {
      return world.princessTowerCount(context.side()) < PRINCESS_TOWERS_KEPT ? 1 : 0;
    }
    if (id == COOP_KING_TOWER_DAMAGED || id == COOP_TOWER_DESTROYED) {
      // A battle of two players has no co-op side.
      return 0;
    }
    throw new UnsupportedOperationException(
        "the battle does not answer " + BattleFunctions.byId(id).name() + " yet");
  }
}
