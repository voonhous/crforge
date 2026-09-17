package org.crforge.desktop.render;

import com.badlogic.gdx.graphics.Color;
import org.crforge.core.util.GameUnits;

/** Centralized constants for the debug renderer: colors, dimensions, and thresholds. */
public final class RenderConstants {

  private RenderConstants() {}

  // ---- Dimensions ----

  /** Scale: 1 tile = TILE_PIXELS pixels. Use for tile indices and tile counts. */
  public static final float TILE_PIXELS = 24f;

  /**
   * Scale for simulation values in integer game units (positions, radii, ranges, offsets): 1 game
   * unit = PIXELS_PER_UNIT pixels, i.e. TILE_PIXELS per {@link GameUnits#UNITS_PER_TILE} units.
   */
  public static final float PIXELS_PER_UNIT = TILE_PIXELS / GameUnits.UNITS_PER_TILE;

  /** Height of the top UI panel (red player HUD). */
  public static final float TOP_UI_HEIGHT = 140f;

  /** Height of the bottom UI panel (blue player HUD). */
  public static final float BOTTOM_UI_HEIGHT = 140f;

  /**
   * Converts a simulation distance or coordinate in game units to pixels. Add {@link
   * #BOTTOM_UI_HEIGHT} separately for arena-space Y coordinates.
   */
  public static float unitsToPixels(float units) {
    return units * PIXELS_PER_UNIT;
  }

  // Card layout
  public static final float CARD_WIDTH = 60f;
  public static final float CARD_HEIGHT = 80f;
  public static final float CARD_SPACING = 10f;
  public static final int HAND_SIZE = 4;

  // Entity rendering
  public static final int CIRCLE_SEGMENTS = 32;
  public static final float PROJECTILE_RADIUS = 4f;

  // Health bar
  public static final float HEALTH_BAR_HEIGHT = 12f;
  public static final float HEALTH_BAR_BORDER = 1f;
  public static final Color COLOR_HP_TEXT = new Color(0.1f, 0.1f, 0.1f, 1f);
  public static final float HEALTH_BAR_MIN_WIDTH = 20f;
  public static final float HEALTH_BAR_Y_OFFSET = 4f;
  public static final float HEALTH_THRESHOLD_HIGH = 0.6f;
  public static final float HEALTH_THRESHOLD_LOW = 0.3f;

  // Elixir bar
  public static final float ELIXIR_BAR_WIDTH = 250f;
  public static final float ELIXIR_BAR_HEIGHT = 15f;
  public static final float MAX_ELIXIR = 10f;

  /** Darken factor for alternating checkerboard tiles (multiplied against RGB). */
  public static final float CHECKER_DARKEN = 0.92f;

  // ---- Tile colors ----

  public static final Color COLOR_BLUE_ZONE = new Color(0.2f, 0.3f, 0.6f, 1f);
  public static final Color COLOR_RED_ZONE = new Color(0.6f, 0.2f, 0.2f, 1f);
  public static final Color COLOR_RIVER = new Color(0.2f, 0.5f, 0.8f, 1f);
  public static final Color COLOR_BRIDGE = new Color(0.5f, 0.4f, 0.3f, 1f);
  public static final Color COLOR_GROUND = new Color(0.3f, 0.5f, 0.3f, 1f);
  public static final Color COLOR_BANNED = new Color(0.1f, 0.1f, 0.1f, 1f);
  public static final Color COLOR_TOWER_TILE = new Color(1.0f, 1.0f, 0.0f, 0.5f);

  public static final Color COLOR_GRID = new Color(0f, 0f, 0f, 0.2f);
  public static final Color COLOR_HOVER_INVALID = new Color(1f, 0.3f, 0.3f, 0.3f);

  // ---- Entity colors ----

  public static final Color COLOR_BLUE_ENTITY = new Color(0.3f, 0.5f, 1f, 1f);
  public static final Color COLOR_RED_ENTITY = new Color(1f, 0.3f, 0.3f, 1f);
  public static final Color COLOR_TOWER_BOUNDARY = new Color(0.5f, 0.5f, 0.5f, 1f);
  public static final Color COLOR_PROJECTILE = new Color(1f, 1f, 0f, 1f);
  public static final Color COLOR_AIR_UNIT = new Color(0.7f, 0.9f, 1f, 0.8f);

  // Crown tower colors (blue / red)
  public static final Color COLOR_BLUE_CROWN_TOWER = new Color(0.4f, 0.5f, 0.9f, 1f);
  public static final Color COLOR_RED_CROWN_TOWER = new Color(0.9f, 0.4f, 0.4f, 1f);
  public static final Color COLOR_BLUE_PRINCESS_TOWER = new Color(0.5f, 0.6f, 0.8f, 1f);
  public static final Color COLOR_RED_PRINCESS_TOWER = new Color(0.8f, 0.5f, 0.5f, 1f);

  // ---- Debug overlay colors ----

  public static final Color COLOR_COLLISION_CIRCLE = new Color(1f, 1f, 0f, 0.5f);
  public static final Color COLOR_BLUE_GHOST = new Color(0.3f, 0.5f, 1f, 0.5f);
  public static final Color COLOR_RED_GHOST = new Color(1f, 0.3f, 0.3f, 0.5f);
  public static final Color COLOR_SPELL_RADIUS = new Color(1f, 1f, 1f, 0.3f);
  public static final Color COLOR_ATTACK_RANGE = new Color(1f, 1f, 1f, 0.12f);
  public static final Color COLOR_MINIMUM_RANGE = new Color(1f, 0.4f, 0.2f, 0.2f);
  public static final Color COLOR_AGGRO_RANGE = new Color(0.2f, 0.9f, 0.4f, 0.25f);
  public static final Color COLOR_HOVER_ATTACK_RANGE = new Color(1f, 1f, 1f, 0.3f);
  public static final Color COLOR_PATH = new Color(0f, 1f, 1f, 0.7f);

  // ---- Health bar colors ----

  public static final Color COLOR_HEALTH_BG = new Color(0.2f, 0.2f, 0.2f, 0.8f);
  public static final Color COLOR_HEALTH_GREEN = new Color(0.2f, 0.8f, 0.2f, 1f);
  public static final Color COLOR_HEALTH_YELLOW = new Color(0.9f, 0.9f, 0.2f, 1f);
  public static final Color COLOR_HEALTH_RED = new Color(0.9f, 0.2f, 0.2f, 1f);

  // ---- UI colors ----

  public static final Color COLOR_UI_BG = new Color(0.15f, 0.15f, 0.15f, 1f);
  public static final Color COLOR_CARD_BG = new Color(0.3f, 0.3f, 0.3f, 1f);
  public static final Color COLOR_CARD_BORDER = new Color(0.1f, 0.1f, 0.1f, 1f);
  public static final Color COLOR_CARD_SELECTED = new Color(1f, 1f, 0f, 1f);
  public static final Color COLOR_ELIXIR = new Color(0.9f, 0.2f, 0.9f, 1f);
  public static final Color COLOR_ELIXIR_BG = new Color(0.3f, 0.1f, 0.3f, 0.8f);

  // ---- Status effect colors (priority order: highest first) ----

  public static final Color COLOR_EFFECT_FREEZE = new Color(0.5f, 0.9f, 1f, 0.9f);
  public static final Color COLOR_EFFECT_STUN = new Color(1f, 1f, 0.2f, 0.9f);
  public static final Color COLOR_EFFECT_KNOCKBACK = new Color(0.9f, 0.7f, 0.2f, 0.9f);
  public static final Color COLOR_EFFECT_SLOW = new Color(0.3f, 0.7f, 1f, 0.9f);
  public static final Color COLOR_EFFECT_POISON = new Color(0.4f, 0.8f, 0.2f, 0.9f);
  public static final Color COLOR_EFFECT_BURN = new Color(1f, 0.5f, 0.1f, 0.9f);
  public static final Color COLOR_EFFECT_EARTHQUAKE = new Color(0.6f, 0.4f, 0.2f, 0.9f);
  public static final Color COLOR_EFFECT_TORNADO = new Color(0.7f, 0.7f, 0.9f, 0.9f);
  public static final Color COLOR_EFFECT_VULNERABILITY = new Color(0.8f, 0.2f, 0.6f, 0.9f);
  public static final Color COLOR_EFFECT_CURSE = new Color(0.5f, 0.1f, 0.5f, 0.9f);
  public static final Color COLOR_EFFECT_RAGE = new Color(1f, 0.4f, 0.1f, 0.9f);

  // ---- New feature colors ----

  /** Electric blue color for chain lightning lines. */
  public static final Color COLOR_CHAIN_LIGHTNING = new Color(0.4f, 0.8f, 1f, 1f);

  /** Golden color for shield bar segments. */
  public static final Color COLOR_SHIELD = new Color(1f, 0.85f, 0.2f, 1f);

  /** Semi-transparent landing zone indicators for position-targeted AOE projectiles. */
  public static final Color COLOR_BLUE_LANDING_ZONE = new Color(0.3f, 0.5f, 1f, 0.2f);

  public static final Color COLOR_RED_LANDING_ZONE = new Color(1f, 0.3f, 0.3f, 0.2f);

  /** Base color for area effect zone overlays. */
  public static final Color COLOR_AREA_EFFECT = new Color(0.8f, 0.8f, 0.2f, 0.4f);

  /** Semi-transparent light blue for deploy timer radial overlay. */
  public static final Color COLOR_DEPLOY_TIMER = new Color(0.4f, 0.75f, 1f, 0.6f);

  /** Earthy brown at low alpha for hidden (underground) buildings like Tesla. */
  public static final Color COLOR_HIDDEN_BUILDING = new Color(0.4f, 0.3f, 0.2f, 0.25f);

  /** Purple/magenta color for laser ball (DarkMagic/Void) AOE overlays. */
  public static final Color COLOR_LASER_BALL = new Color(0.7f, 0.2f, 1f, 1f);

  // Ability indicator colors
  public static final Color COLOR_CHARGE_BAR = new Color(1f, 0.6f, 0.1f, 0.9f);
  public static final Color COLOR_CHARGE_READY = new Color(1f, 1f, 1f, 0.9f);
  public static final Color COLOR_VARIABLE_DAMAGE_DOT = new Color(1f, 0.2f, 0.2f, 0.9f);
  public static final Color COLOR_DASH_LINE = new Color(0.2f, 1f, 0.5f, 0.6f);
  public static final Color COLOR_HOOK_LINE = new Color(0.8f, 0.8f, 0.8f, 0.8f);
  public static final Color COLOR_REFLECT_AURA = new Color(1f, 0.3f, 1f, 0.5f);
  public static final Color COLOR_CLONE_AURA = new Color(0.9f, 0.4f, 0.9f, 0.7f);

  // ---- Grid pathfinding overlay ----

  /** Side of one routing cell in pixels: 500 game units at the arena's scale. */
  public static final float CELL_PIXELS = TILE_PIXELS / 2f;

  /** Cell carrying a road, which the route search charges less for than plain ground. */
  public static final Color COLOR_CELL_ROAD = new Color(0.95f, 0.85f, 0.3f, 0.3f);

  /** Plain ground cell with no road and nothing stamped over it. */
  public static final Color COLOR_CELL_DEFAULT = new Color(0.4f, 0.85f, 0.45f, 0.22f);

  /** Water cell, which a unit with no water permission is charged the blocked weight for. */
  public static final Color COLOR_CELL_WATER = new Color(0.2f, 0.45f, 1f, 0.45f);

  /** Cell flagged blocked by the arena's cell map. */
  public static final Color COLOR_CELL_BLOCKED = new Color(0.12f, 0.12f, 0.12f, 0.55f);

  /** Cell covered by a building's footprint in the routing overlay. */
  public static final Color COLOR_CELL_BUILDING = new Color(1f, 0.35f, 0.2f, 0.45f);

  /** Cell outside the arena, which the cost rule rejects outright. */
  public static final Color COLOR_CELL_OUT_OF_ARENA = new Color(0.7f, 0.1f, 0.7f, 0.5f);

  /** Outline drawn around the cell the mouse is over. */
  public static final Color COLOR_CELL_HOVER = new Color(1f, 1f, 1f, 0.9f);

  /** Polyline through the cells still left on a troop's route. */
  public static final Color COLOR_ROUTE_LINE = new Color(0.2f, 1f, 0.9f, 0.85f);

  /** Marker on each cell centre a troop's route passes through. */
  public static final Color COLOR_ROUTE_NODE = new Color(0.2f, 1f, 0.9f, 0.55f);

  /** Marker on the position a troop currently holds as its reference. */
  public static final Color COLOR_ROUTE_REFERENCE = new Color(1f, 0.85f, 0.2f, 0.9f);

  /** Ghost polyline of a golden scenario's whole reference trajectory. */
  public static final Color COLOR_GOLDEN_PATH = new Color(0.85f, 0.85f, 0.85f, 0.45f);

  /** Hollow marker on the reference position for the tick currently being simulated. */
  public static final Color COLOR_GOLDEN_MARKER = new Color(1f, 1f, 1f, 0.9f);

  /** Marker on the first tick where the live unit left the reference trajectory. */
  public static final Color COLOR_GOLDEN_DEVIATION = new Color(1f, 0.2f, 0.2f, 0.95f);

  // ---- Damage number colors ----

  /** Red color for floating HP damage numbers. */
  public static final Color COLOR_DAMAGE_NUMBER = new Color(1f, 0.2f, 0.2f, 1f);

  /** Gold color for floating shield damage numbers. */
  public static final Color COLOR_SHIELD_DAMAGE_NUMBER = new Color(1f, 0.85f, 0.2f, 1f);
}
