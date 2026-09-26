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
            + " players; a name the table does not know naming one of the battle's variables, read"
            + " from the context entity, 0 for one never written, and then one of its game tags,"
            + " true when the context entity carries every bit of it. Not modelled: the other 43"
            + " functions, which fail when called, and names of data rows.")
final class BattleExpressionEnvironment implements ExpressionEnvironment {

  private static final int KING_TOWER_DAMAGED = BattleFunctions.id("king_tower_damaged");
  private static final int COOP_KING_TOWER_DAMAGED = BattleFunctions.id("coop_king_tower_damaged");
  private static final int TOWER_DESTROYED = BattleFunctions.id("tower_destroyed");
  private static final int COOP_TOWER_DESTROYED = BattleFunctions.id("coop_tower_destroyed");

  /** The id a variable's key is added to: every function and tag id lies below it. */
  static final int VARIABLE_BASE = 20_000;

  /** The id a game tag's index is added to: every function id lies below it. */
  static final int GAME_TAG_BASE = 10_000;

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
    if (entry != null) {
      return new Function(entry.id(), entry.minArguments(), entry.maxArguments());
    }
    // A name the function table does not know may be one of the battle's variables, and then one
    // of its game tags.
    Integer key = world.variableKey(name);
    if (key != null) {
      return new Function(VARIABLE_BASE + key, 0, 0);
    }
    Integer tag = world.gameTagIndex(name);
    return tag == null ? null : new Function(GAME_TAG_BASE + tag, 0, 0);
  }

  @Override
  public int call(int id, int[] arguments) {
    if (id >= VARIABLE_BASE) {
      return context.variable(id - VARIABLE_BASE);
    }
    if (id >= GAME_TAG_BASE) {
      // True when the context entity carries every bit of the tag.
      long mask = world.gameTagMask(id - GAME_TAG_BASE);
      return (mask & ~context.getView().getFlags()) == 0 ? 1 : 0;
    }
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
