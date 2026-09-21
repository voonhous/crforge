package org.crforge.core.pathfinding.grid;

/**
 * Balance and pathfinding constants of the standard game, community-decoded game data.
 *
 * <p>Each constant keeps the published key as its name, so a value can be traced back to the data
 * it came from by name alone. Costs are the per-cell weights the route search multiplies by its
 * step factor; distances are game units; times are milliseconds.
 */
public final class PathfindingGlobals {

  private PathfindingGlobals() {
    // Constants holder
  }

  // -------------------------------------------------------------------------------------------
  // Route search: per-cell costs
  // -------------------------------------------------------------------------------------------

  /** Cost of an ordinary cell that carries no road. */
  public static final int PATHFINDING_DEFAULT_COST = 8;

  /** Cost of a cell carrying a road the unit is not assigned to. */
  public static final int PATHFINDING_ROAD_COST = 5;

  /** Cost of a cell carrying the road the unit is assigned to. */
  public static final int PATHFINDING_MATCHINGROAD_COST = 5;

  /** Cost of a water cell for a unit that is allowed to enter water. */
  public static final int PATHFINDING_WATER_COST = 7;

  /** Cost of a cell the unit cannot cross at all. */
  public static final int PATHFINDING_BLOCKED_COST = 50;

  /** Cost stamped over the cells a building occupies. */
  public static final int PATHFINDING_BUILDING_COST = 50;

  // -------------------------------------------------------------------------------------------
  // Route search: heuristic and node handling
  // -------------------------------------------------------------------------------------------

  /** Which heuristic formula the search uses. */
  public static final int PATHFINDING_HEURISTIC_METHOD = 1;

  /** Weight the heuristic is multiplied by before it is added to the accumulated cost. */
  public static final int PATHFINDING_DEFAULTHEURISTIC_COST = 5;

  /** Whether a cheaper path to an already open node updates that node. */
  public static final boolean PATHFINDING_REFRESH_OPENNODES = true;

  /** Whether a cheaper path to an already closed node reopens that node. */
  public static final boolean PATHFINDING_REOPEN_CLOSEDNODES = false;

  /** Tolerance, in cells, below which a freshly searched route is treated as the existing one. */
  public static final int PATHFINDING_SAMEPATH_EPSILON = 3;

  /** Whether the newer route search is in use. */
  public static final boolean NEW_PATHFINDING_CODE = true;

  /** Whether route cell ids carry a lane id. */
  public static final boolean DISABLE_PATH_LINEID = true;

  // -------------------------------------------------------------------------------------------
  // Dynamic occlusions (the per-match overlay stamped over building footprints)
  // -------------------------------------------------------------------------------------------

  /** Whether buildings stamp their footprints into the cost overlay at all. */
  public static final boolean PATHFINDING_DYNAMIC_OCCLUSIONS = true;

  /** Whether only friendly buildings occlude cells for a given unit. */
  public static final boolean PATHFINDING_FRIENDLYONLY_OCCLUSIONS = true;

  // -------------------------------------------------------------------------------------------
  // Endpoint selection near the target
  // -------------------------------------------------------------------------------------------

  /** Whether a flying unit's stopping cell avoids water. */
  public static final boolean KS_POS_TO_TARGET_FLYING_NO_WATER = true;

  /** Whether a ground unit's stopping cell avoids occupied building cells. */
  public static final boolean KS_POS_TO_TARGET_GROUND_AVOID_BUILDINGS = true;

  // -------------------------------------------------------------------------------------------
  // Sight and range
  // -------------------------------------------------------------------------------------------

  /** Whether a unit's own collision radius is added to its attack and sight radii. */
  public static final boolean ADD_CHARACTER_RANGE_TO_RADIUS = true;

  /** Extra sight range, in game units, toward buildings. */
  public static final int EXTRA_SIGHT_RANGE_TO_BUILDING = 0;

  /** Extra sight range, in game units, toward crown towers. */
  public static final int EXTRA_SIGHT_RANGE_TO_CROWN_TOWERS = 2000;

  /** Extra range, in game units, allowed before a unit gives up a target it already has. */
  public static final int LOGIC_RANGE_EXTENSION_TO_KEEP_TARGET = 25;

  /** How much closer, in game units, a continuous-damage attacker moves toward its target. */
  public static final int LOGIC_CHARACTER_CONTINUOUS_DAMAGE_ATTACK_CLOSER = 500;

  /**
   * How long, in milliseconds, an entity counts as finishing an attack. The entity state visit adds
   * one tick per visit and clears the latch on the first visit that carries the total past this, so
   * the latch survives five ticks and clears on the sixth.
   */
  public static final int ATTACK_FINISH_TIME_MS = 250;

  // -------------------------------------------------------------------------------------------
  // Movement and target selection rules
  // -------------------------------------------------------------------------------------------

  /** Whether a spawn-pathfinding unit's arrival radius is derived from its speed. */
  public static final boolean LOGIC_SPAWN_PATHFIND_REACHED_RADIUS_FROM_SPEED = true;

  /** Whether a unit defending a touchdown lane is restricted to that side of the arena. */
  public static final boolean LOGIC_TOUCHDOWN_RESTRICTED_SIDE_MOVEMENT = true;

  /** Whether a unit routing backwards first tries to keep the target it already has. */
  public static final boolean LOGIC_PATHFIND_BACKWARDS_TRY_KEEP_TARGET = true;

  /** Whether default tower targeting compares x positions rather than lanes. */
  public static final boolean LOGIC_XPOS_BASED_TOWER_TARGETING = true;

  /** Whether default target selection scores candidates by matching lane id. */
  public static final boolean LOGIC_DEFAULT_TARGET_USE_LANE_ID = false;

  /**
   * Whether princess towers are always preferred as the default target. The published key for this
   * value contains a literal space ("ALWAYS_AS DEFAULT_TARGET"); the name here closes the gap
   * because a Java identifier cannot carry one.
   */
  public static final boolean LOGIC_PRINCESS_TOWERS_ALWAYS_AS_DEFAULT_TARGET = true;
}
