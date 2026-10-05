package org.crforge.core.battle.unit;

import java.util.List;
import org.crforge.core.battle.action.ActionContext;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.expression.BattleFunctions;
import org.crforge.core.battle.expression.ExpressionEnvironment;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.core.pathfinding.target.ReferenceValidator;
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
            + " held by three_musketeers_pekka and three_musketeers_air_building; attack_count as"
            + " the context's attack time over its row's hit speed toward zero, 0 for a hit speed"
            + " below 1, whether its targeting runs or not, held by little_prince_giant and"
            + " little_prince_retarget; is_clone as a character's clone byte, held by"
            + " buff_after_hits_ghost_evo; is_kamikazing as a character's byte its Kamikaze"
            + " hit's end sets, held by evo_skeletonballoon_vs_musketeer, where it stays 0;"
            + " is_moving as a character's movement component on and"
            + " its speed budget above 0, 0 for any other object, held by"
            + " building_evolutions_barbarians; is_active_or_secondary_champion and is_champion"
            + " alike as a character, no clone, that a champion slot of its side follows by its"
            + " row and play, held by hero_goblins; is_deploying as a character in the"
            + " deploying state, 0 for any other object, held by hero_mega_minion;"
            + " is_combat_enabled as the context's targeting component switched on, held by"
            + " ability_hero_mega_minion_vs_musketeer; ability_charges_left (a newer data"
            + " version) as the charges left of the slot that follows a character whose row's"
            + " ability has charges, -1 otherwise, read from the newer build's function;"
            + " is_valid_position (a newer data version) as a point on the map off water, read"
            + " from the newer build's function; self (a newer data version) as the context's"
            + " id, from the newer build's symbol map;"
            + " is_dodging_damage (a newer data version) as a character dashing under a row with"
            + " a dash immunity or with that immunity still counting, 0 for any other object;"
            + " has_crown_tower_in_range on a character as the other side's princess towers still"
            + " in the battle, then its king, any within the argument of the tower's edge and the"
            + " context's own, read alike in the newer build;"
            + " target_max_hp on the context's reference while its targeting runs, 0 without"
            + " one or with the reference's hit points off, with no argument its maximum and"
            + " with one its row's hit points at that many steps above the Common first level"
            + " re-based on its rarity, held by evo_pekka_vs_musketeer and"
            + " evo_pekka_kills_giant_knight_musketeer; as_int (a newer data version) as the"
            + " value under its key in the context of what the entity's holder is doing, else its"
            + " default, else -1, and a context key #name as the name's hash, held by"
            + " pekka-resurrect-v2. Supplied, not"
            + " settled: the battle's seed, 1 unless one is given; max_hp's growth percentage, the"
            + " usual 100; the"
            + " two co-op functions answer 0 in a battle of two players; a name the table does"
            + " not know naming one of the battle's variables, read from the context entity, 0"
            + " for one never written, and then one of its game tags, true when the context"
            + " entity carries every bit of it. Not modelled: the force-layer tags target_is_ground"
            + " would read first, refused; target_max_hp on a tower or on a reference that has"
            + " left the battle, refused; has_crown_tower_in_range on an object that is not a"
            + " character or of the neutral side, refused; the other 20 functions, which fail"
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
  private static final int ATTACK_COUNT = BattleFunctions.id("attack_count");
  private static final int IS_CLONE = BattleFunctions.id("is_clone");
  private static final int IS_MOVING = BattleFunctions.id("is_moving");
  private static final int IS_KAMIKAZING = BattleFunctions.id("is_kamikazing");
  private static final int IS_ACTIVE_OR_SECONDARY_CHAMPION =
      BattleFunctions.id("is_active_or_secondary_champion");
  private static final int IS_CHAMPION = BattleFunctions.id("is_champion");
  private static final int IS_DEPLOYING = BattleFunctions.id("is_deploying");
  private static final int IS_COMBAT_ENABLED = BattleFunctions.id("is_combat_enabled");
  private static final int ABILITY_CHARGES_LEFT = BattleFunctions.id("ability_charges_left");
  private static final int IS_VALID_POSITION = BattleFunctions.id("is_valid_position");
  private static final int IS_DODGING_DAMAGE = BattleFunctions.id("is_dodging_damage");
  private static final int HAS_CROWN_TOWER_IN_RANGE =
      BattleFunctions.id("has_crown_tower_in_range");

  /** What ability_charges_left answers for an object without counted charges to read. */
  private static final int NO_CHARGES = -1;

  /** The game tags that force an object onto a layer, which target_is_ground would read first. */
  private static final List<String> FORCE_LAYER_TAGS = List.of("FORCE_IS_GROUND", "FORCE_IS_AIR");

  private static final int HP = BattleFunctions.id("hp");
  private static final int MAX_HP = BattleFunctions.id("max_hp");
  private static final int TARGET_MAX_HP = BattleFunctions.id("target_max_hp");

  /** The component slot of a character's hit points, whose active bit target_max_hp tests. */
  private static final int HIT_POINTS_SLOT = 2;

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

  /** The name of a newer data version's function that reads a context key. */
  private static final String AS_INT_NAME = "as_int";

  /** A newer data version's symbol for the context object's own id. */
  private static final String SELF_NAME = "self";

  /** The call id of {@code self}, apart from every table's. */
  static final int SELF = 9_001;

  /** The id the environment calls as_int by, below every tag, variable and data row id. */
  static final int AS_INT = 9_000;

  /** What as_int answers for a key no board holds when the expression gives no default. */
  private static final int AS_INT_NO_DEFAULT = -1;

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
    // A newer data version's function, ahead of the table, whose ids are 14.593.1's.
    if (name.equalsIgnoreCase(AS_INT_NAME)) {
      return new Function(AS_INT, 1, 2);
    }
    BattleFunctions.Entry entry = BattleFunctions.byName(name);
    if (entry != null) {
      return new Function(entry.id(), entry.minArguments(), entry.maxArguments());
    }
    // The newer build's symbol map takes self, by its exact name, ahead of the variables.
    if (name.equals(SELF_NAME)) {
      return new Function(SELF, 0, 0);
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

  /** A context key, {@code #name}: the hash of the name after the sigil. */
  @Override
  public Integer constant(String name) {
    return name.startsWith("#") ? ActionContext.key(name.substring(1)) : null;
  }

  @Override
  public int call(int id, int[] arguments) {
    if (id == AS_INT) {
      return asInt(arguments);
    }
    if (id == SELF) {
      // The context object's id, as the newer build reads its symbol.
      return context.getId();
    }
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
    if (id == TARGET_MAX_HP) {
      return targetMaxHp(arguments);
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
    if (id == ATTACK_COUNT) {
      return attackCount();
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
    if (id == IS_CLONE) {
      // A character's clone byte; another object's answer is not established.
      if (!(context instanceof CharacterEntity character)) {
        throw new UnsupportedOperationException(
            "is_clone on " + context.name() + ", which is not a character, is not modelled");
      }
      return character.isClone() ? 1 : 0;
    }
    if (id == IS_COMBAT_ENABLED) {
      // The context's targeting component switched on: the low bit of its active word, read with
      // no class test.
      return context.isActive(0) ? 1 : 0;
    }
    if (id == IS_MOVING) {
      // A character's movement component on and its speed budget above 0; every other object's
      // budget slot answers 0.
      return context instanceof CharacterEntity character ? character.movingAnswer() : 0;
    }
    if (id == IS_KAMIKAZING) {
      // A character's byte its Kamikaze hit's end sets; another object's answer is not
      // established.
      if (!(context instanceof CharacterEntity character)) {
        throw new UnsupportedOperationException(
            "is_kamikazing on " + context.name() + ", which is not a character, is not modelled");
      }
      return character.isKamikazeHitEnded() ? 1 : 0;
    }
    if (id == IS_ACTIVE_OR_SECONDARY_CHAMPION || id == IS_CHAMPION) {
      // One answer for both names: a character that is no clone and that a champion slot of its
      // side follows - its row and its play - whether alive or not; any other object answers 0.
      return context instanceof CharacterEntity character && world.followedChampion(character)
          ? 1
          : 0;
    }
    if (id == IS_DEPLOYING) {
      // A character in the deploying state; any other object answers 0.
      return context instanceof CharacterEntity character
              && character.getView().getState() == GridEntityState.DEPLOYING
          ? 1
          : 0;
    }
    if (id == ABILITY_CHARGES_LEFT) {
      return abilityChargesLeft();
    }
    if (id == IS_VALID_POSITION) {
      return validPosition(arguments[0], arguments[1]);
    }
    if (id == IS_DODGING_DAMAGE) {
      // A character dashing under a row with a dash immunity, or with that immunity still
      // counting after its dash; any other object answers 0.
      return context instanceof CharacterEntity character && character.dodgingDamage() ? 1 : 0;
    }
    if (id == HAS_CROWN_TOWER_IN_RANGE) {
      return hasCrownTowerInRange(arguments[0]);
    }
    if (id == IS_NPC_BATTLE) {
      // A battle of two players is not played against the game's own opponent.
      return 0;
    }
    throw new UnsupportedOperationException(
        "the battle does not answer " + BattleFunctions.byId(id).name() + " yet");
  }

  /**
   * has_crown_tower_in_range(range): 1 when a crown tower of the other side is within the range of
   * the context, measured from the tower's edge and the context's own, as target_in_range measures
   * from its reference: the squared distance between the two centres at most the square of the
   * tower's collision radius plus the range plus the context's row's collision radius.
   *
   * <p>The towers are the other side's princess towers, in the order its list of map objects holds
   * them, then its king. Nothing tests that a tower is alive or awake: a destroyed princess tower
   * counts until the cleanup that removes it takes it off the list, and a sleeping king counts.
   * Only the context's own row's collision radius is added, and only for a character, which every
   * context of this function in the data is; another kind of object, and an object of the neutral
   * side, which has no other side, are refused.
   */
  private int hasCrownTowerInRange(int range) {
    if (context.getView().getType() != ReferenceValidator.TYPE_CHARACTER) {
      throw new UnsupportedOperationException(
          "has_crown_tower_in_range on "
              + context.name()
              + ", which is not a character, is not modelled");
    }
    int side = context.side();
    if (side == NEUTRAL_SIDE) {
      throw new UnsupportedOperationException(
          "has_crown_tower_in_range on "
              + context.name()
              + " of the neutral side, not established");
    }
    int otherSide = (side & 1) ^ 1;
    long reach = (long) range + context.getData().collisionRadius();
    for (TowerEntity tower : world.princessTowers(otherSide)) {
      if (withinReach(tower, reach)) {
        return 1;
      }
    }
    TowerEntity king = world.kingTower(otherSide);
    if (king == null) {
      throw new UnsupportedOperationException(
          "has_crown_tower_in_range on " + context.name() + " with no king on the other side");
    }
    return withinReach(king, reach) ? 1 : 0;
  }

  /** Whether a tower's centre lies within its collision radius plus a reach of the context's. */
  private boolean withinReach(TowerEntity tower, long reach) {
    long dx = tower.getView().getX() - context.getView().getX();
    long dy = tower.getView().getY() - context.getView().getY();
    long limit = tower.getView().getCollisionRadius() + reach;
    return dx * dx + dy * dy <= limit * limit;
  }

  /**
   * A newer data version's is_valid_position: 1 for a point on the map that is not water, 0 for one
   * off the map (either coordinate below 0, or at or past the map's width or height) or on a water
   * cell. It reads no object.
   */
  private int validPosition(int x, int y) {
    TileMap map = world.getTileMap();
    if ((x | y) < 0
        || x >= map.width() * TileMap.CELL_UNITS
        || y >= map.height() * TileMap.CELL_UNITS) {
      return 0;
    }
    return map.isWater(x / TileMap.CELL_UNITS, y / TileMap.CELL_UNITS) ? 0 : 1;
  }

  /**
   * The charges left of the champion slot that follows the context, as a newer data version's
   * ability_charges_left reads them: the slot's count, -1 when it does not count them. -1 for an
   * object that is not a character, one whose row has no ability or an ability of no charges, and
   * one no slot of its side's king follows (a clone among them).
   */
  private int abilityChargesLeft() {
    if (!(context instanceof CharacterEntity character)) {
      return NO_CHARGES;
    }
    AbilityData ability = character.getData().ability();
    if (ability == null || ability.maxCharges() < 1) {
      return NO_CHARGES;
    }
    ChampionController slot = character.followingSlot();
    return slot == null ? NO_CHARGES : slot.getCharges();
  }

  /**
   * The hit count of the context's current attack: its attack time over its row's hit speed, toward
   * zero, whether its targeting component runs or not; 0 for a row whose hit speed is below 1. The
   * row's own hit speed, so under a buff that speeds its hits the count still rises once a hit.
   */
  private int attackCount() {
    int attackTime = context.getTargeting().getAttackTimerMs();
    int hitSpeed = context.getData().hitSpeedMs();
    int count = hitSpeed < 1 ? 0 : attackTime / hitSpeed;
    world.attackCountRead(context, attackTime, count);
    return count;
  }

  /**
   * The maximum hit points of the context's reference: 0 while its targeting component is off, with
   * no reference, or with the reference's hit points absent or switched off. Without an argument
   * the reference's maximum as it stands, times the usual growth percentage of 100. With one, not
   * the reference's own level: the argument's low byte as steps above the Common rarity's first
   * level, re-based on the reference's own rarity, and the reference's row's hit points at that
   * level, so target_max_hp(10) is a row's hit points at card level 11 whatever level it was played
   * at. Nothing tests that the reference is alive.
   *
   * <p>Refused rather than guessed: a reference that has left the battle, and a tower, whose hit
   * points are scaled by tables of their own.
   */
  private int targetMaxHp(int[] arguments) {
    TargetView target = context.getTargeting().getReference();
    if (!context.isActive(0) || target == null) {
      return 0;
    }
    WorldEntity entity = world.entityOf(target.getEntity());
    if (entity == null) {
      throw new UnsupportedOperationException(
          "target_max_hp on " + context.name() + "'s reference, which has left the battle");
    }
    if (!(entity instanceof CharacterEntity)) {
      throw new UnsupportedOperationException(
          "target_max_hp on " + entity.name() + ", which is not a character, is not modelled");
    }
    HitPoints hitPoints = entity.getHitPoints();
    if (hitPoints == null || !entity.isActive(HIT_POINTS_SLOT)) {
      return 0;
    }
    if (arguments.length == 0) {
      return hitPoints.getMaximum() * GROWTH_PERCENT / 100;
    }
    UnitData row = entity.getData();
    int packed =
        PackedLevel.pack(
            (RarityTable.COMMON.relativeLevel() << 8) | (arguments[0] & 0xff), row.rarity());
    return LevelScaling.hitpoints(
        ScalingGlobals.standard(), row.hitpoints(), packed, row.rarity(), false, false);
  }

  /**
   * as_int(key, default), a newer data version's function: the value under the key in the context
   * of what the entity's holder is doing now (or, with none, of what the holder running the action
   * is doing, for an action of the entity's tree run on another entity), the main board first and
   * then the scratch board, else the default, -1 without one. The game reads the context unchecked,
   * so it never evaluates as_int without one; here that is refused.
   */
  private int asInt(int[] arguments) {
    ActionContext actionContext = context.actionHolder().currentContext();
    if (actionContext == null) {
      // An action of the entity's tree running on another entity's holder reads the context that
      // holder was handed.
      actionContext = ActionHolder.activeContext();
    }
    if (actionContext == null) {
      throw new UnsupportedOperationException(
          "as_int on " + context.name() + " with no action context, which is not established");
    }
    Integer value = actionContext.read(arguments[0]);
    if (value != null) {
      return value;
    }
    return arguments.length == 2 ? arguments[1] : AS_INT_NO_DEFAULT;
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
