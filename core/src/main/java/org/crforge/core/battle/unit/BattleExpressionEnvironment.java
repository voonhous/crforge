package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.ExpressionEnvironment;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.target.TargetView;

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
        "Settled: x and y as the context entity's position as it stands when the expression"
            + " is evaluated; king_tower_damaged as the context side's king tower below its"
            + " maximum hit points, and tower_destroyed as that side down to fewer than two"
            + " princess towers; team_index as the side's low bit, 2 for the neutral side;"
            + " team_y_direction as -1 for 0 and 1 for anything else; map_width and map_height"
            + " as the arena's cells times 500; a character or building row's name, after the"
            + " functions, variables and tags, as its global id, 0 for a negative one, and"
            + " has_data as the context's own row having that id; rand as one draw from the"
            + " battle's random source, taken as the expression is evaluated; hp as the context's"
            + " hit points and max_hp without a level as its maximum; target_in_range on the"
            + " context's reference, its edge and the context's; get_radius as the context's"
            + " row's collision radius, held by giant_buffer_knights; target_is_ground as the"
            + " context's reference's row's flying height 0, none while its targeting is off,"
            + " held by three_musketeers_pekka and three_musketeers_air_building. Supplied, not"
            + " settled: the battle's seed, 1 unless one is given; max_hp's growth percentage, the"
            + " usual 100; the"
            + " two co-op functions answer 0 in a battle of two players; a name the table does"
            + " not know naming one of the battle's variables, read from the context entity, 0"
            + " for one never written, and then one of its game tags, true when the context"
            + " entity carries every bit of it. Not modelled: the force-layer tags target_is_ground"
            + " would read first, refused; the other 30 functions, which fail"
            + " when called, and a row whose negative id would fall among the other calls' ids.")
final class BattleExpressionEnvironment implements ExpressionEnvironment {

  private static final int KING_TOWER_DAMAGED = BattleFunctions.id("king_tower_damaged");
  private static final int COOP_KING_TOWER_DAMAGED = BattleFunctions.id("coop_king_tower_damaged");
  private static final int TOWER_DESTROYED = BattleFunctions.id("tower_destroyed");
  private static final int COOP_TOWER_DESTROYED = BattleFunctions.id("coop_tower_destroyed");
  private static final int IS_NPC_BATTLE = BattleFunctions.id("is_npc_battle");
  private static final int X = BattleFunctions.id("x");
  private static final int Y = BattleFunctions.id("y");
  private static final int TEAM_INDEX = BattleFunctions.id("team_index");
  private static final int TEAM_Y_DIRECTION = BattleFunctions.id("team_y_direction");
  private static final int MAP_WIDTH = BattleFunctions.id("map_width");
  private static final int MAP_HEIGHT = BattleFunctions.id("map_height");
  private static final int HAS_DATA = BattleFunctions.id("has_data");
  private static final int RAND = BattleFunctions.id("rand");
  private static final int GET_RADIUS = BattleFunctions.id("get_radius");
  private static final int TARGET_IN_RANGE = BattleFunctions.id("target_in_range");
  private static final int TARGET_IS_GROUND = BattleFunctions.id("target_is_ground");

  /** The game tags that force an object onto a layer, which target_is_ground would read first. */
  private static final List<String> FORCE_LAYER_TAGS = List.of("FORCE_IS_GROUND", "FORCE_IS_AIR");

  private static final int HP = BattleFunctions.id("hp");
  private static final int MAX_HP = BattleFunctions.id("max_hp");

  /**
   * The growth percentage a unit's maximum hit points are read at: no unit that grows is modelled.
   */
  private static final int GROWTH_PERCENT = 100;

  /** The side that belongs to neither player, whose team is 2. */
  private static final int NEUTRAL_SIDE = 100;

  /** The team of the neutral side. */
  private static final int NEUTRAL_TEAM = 2;

  /**
   * What the game adds to a data row's global id to make the id its call carries. A row whose call
   * id comes out negative answers 0; one that would land below it, among the other calls, is not
   * established.
   */
  static final int DATA_CALL_BASE = 100_000;

  /** The id a named data row's place in the battle's list is added to: above every variable. */
  static final int DATA_ROW_BASE = 30_000;

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
    if (tag != null) {
      return new Function(GAME_TAG_BASE + tag, 0, 0);
    }
    // Then the name of a character or building row, which stands for its global id.
    Integer row = world.dataRow(name);
    if (row == null) {
      return null;
    }
    int callId = DATA_CALL_BASE + world.dataRowId(row);
    if (callId >= 0 && callId < DATA_CALL_BASE) {
      throw new UnsupportedOperationException(
          "the data row "
              + name
              + " has a global id whose call id falls among the other calls; what it answers is"
              + " not established");
    }
    return new Function(DATA_ROW_BASE + row, 0, 0);
  }

  @Override
  public int call(int id, int[] arguments) {
    if (id >= DATA_ROW_BASE) {
      // The call id is the global id plus the base, in 32 bits; a negative one answers 0.
      int callId = DATA_CALL_BASE + world.dataRowId(id - DATA_ROW_BASE);
      return callId < 0 ? 0 : callId - DATA_CALL_BASE;
    }
    if (id >= VARIABLE_BASE) {
      return context.variable(id - VARIABLE_BASE);
    }
    if (id >= GAME_TAG_BASE) {
      // True when the context entity carries every bit of the tag.
      long mask = world.gameTagMask(id - GAME_TAG_BASE);
      return (mask & ~context.getView().getFlags()) == 0 ? 1 : 0;
    }
    if (id == X) {
      // The context entity's position as it stands, so a spawn row's position expression reads
      // where its owner is when the spawn runs.
      return context.getView().getX();
    }
    if (id == Y) {
      return context.getView().getY();
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
    if (id == TEAM_INDEX) {
      int side = context.side();
      return side == NEUTRAL_SIDE ? NEUTRAL_TEAM : side & 1;
    }
    if (id == TEAM_Y_DIRECTION) {
      // It reads only its argument: -1 for team 0, 1 for any other.
      return arguments[0] == 0 ? -1 : 1;
    }
    if (id == MAP_WIDTH) {
      return world.getTileMap().width() * TileMap.CELL_UNITS;
    }
    if (id == MAP_HEIGHT) {
      return world.getTileMap().height() * TileMap.CELL_UNITS;
    }
    if (id == HP || id == MAX_HP) {
      HitPoints hitPoints = context.getHitPoints();
      if (hitPoints == null) {
        throw new UnsupportedOperationException(
            BattleFunctions.byId(id).name()
                + " on "
                + context.name()
                + ", which has no hit points");
      }
      if (id == HP) {
        return hitPoints.getHitPoints();
      }
      if (arguments.length != 0) {
        throw new UnsupportedOperationException(
            "max_hp with a level, a lookup of the row at that level, is not modelled");
      }
      // The maximum times the growth percentage, the usual 100: no unit that grows is modelled.
      return hitPoints.getMaximum() * GROWTH_PERCENT / 100;
    }
    if (id == TARGET_IN_RANGE) {
      // The context's own reference, whether its targeting component runs or not: within the
      // argument of it, measured from the target's edge and the context's own.
      TargetView target = context.getTargeting().getReference();
      if (target == null) {
        return 0;
      }
      long dx = target.getEntity().getX() - context.getView().getX();
      long dy = target.getEntity().getY() - context.getView().getY();
      long reach =
          (long) target.getEntity().getCollisionRadius()
              + arguments[0]
              + context.getView().getCollisionRadius();
      return dx * dx + dy * dy <= reach * reach ? 1 : 0;
    }
    if (id == TARGET_IS_GROUND) {
      return targetIsGround();
    }
    if (id == RAND) {
      // One draw from the battle's source, taken as the expression is evaluated.
      return world.getRandom().next(arguments[0]);
    }
    if (id == HAS_DATA) {
      // The context's own row, the exact one: a relative row with an id of its own is not it.
      return arguments[0] == context.getData().globalId() ? 1 : 0;
    }
    if (id == GET_RADIUS) {
      // A character's or a tower's row's collision radius, with no level scaling.
      return context.getData().collisionRadius();
    }
    if (id == IS_NPC_BATTLE) {
      // A battle of two players is not played against the game's own opponent.
      return 0;
    }
    throw new UnsupportedOperationException(
        "the battle does not answer " + BattleFunctions.byId(id).name() + " yet");
  }

  /**
   * Whether the context's reference stands on the ground: none while its targeting component is off
   * or it has no reference, else its row's flying height is 0, so every building and crown tower is
   * ground and every flying row is not, whatever its height. A reference carrying a game tag that
   * forces its layer is refused, as the force tags are not modelled.
   */
  private int targetIsGround() {
    TargetView target = context.getTargeting().getReference();
    if (!context.isActive(0) || target == null) {
      return 0;
    }
    WorldEntity entity = world.entityOf(target.getEntity());
    if (entity == null) {
      throw new UnsupportedOperationException(
          "target_is_ground on " + context.name() + "'s reference, which has left the battle");
    }
    for (String tag : FORCE_LAYER_TAGS) {
      Integer index = world.gameTagIndex(tag);
      if (index != null && (entity.getView().getFlags() & world.gameTagMask(index)) != 0) {
        throw new UnsupportedOperationException(
            "target_is_ground on " + entity.name() + ", which carries " + tag + ", not modelled");
      }
    }
    return entity.getData().flyingHeight() == 0 ? 1 : 0;
  }
}
