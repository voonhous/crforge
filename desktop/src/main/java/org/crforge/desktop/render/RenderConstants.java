package org.crforge.desktop.render;

import com.badlogic.gdx.graphics.Color;

/** Centralized constants for the debug renderer: colors, dimensions, and thresholds. */
public final class RenderConstants {

  private RenderConstants() {}

  // ---- Dimensions ----

  /** Scale: 1 tile = TILE_PIXELS pixels. */
  public static final float TILE_PIXELS = 24f;

  /** Height of the top UI panel (red player HUD). */
  public static final float TOP_UI_HEIGHT = 140f;

  /** Height of the bottom UI panel (blue player HUD). */
  public static final float BOTTOM_UI_HEIGHT = 140f;

  // Card layout
  public static final float CARD_WIDTH = 60f;
  public static final float CARD_HEIGHT = 80f;
  public static final float CARD_SPACING = 10f;
  public static final int HAND_SIZE = 4;

  // Entity rendering
  public static final int CIRCLE_SEGMENTS = 32;
  public static final float PROJECTILE_RADIUS = 4f;
  public static final float ENTITY_NAME_FONT_SCALE = 0.7f;

  // Health bar
  public static final float HEALTH_BAR_HEIGHT = 4f;
  public static final float HEALTH_BAR_MIN_WIDTH = 20f;
  public static final float HEALTH_BAR_Y_OFFSET = 4f;
  public static final float HEALTH_THRESHOLD_HIGH = 0.6f;
  public static final float HEALTH_THRESHOLD_LOW = 0.3f;

  // Elixir bar
  public static final float ELIXIR_BAR_WIDTH = 250f;
  public static final float ELIXIR_BAR_HEIGHT = 15f;
  public static final float MAX_ELIXIR = 10f;

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

  // Ability indicator colors
  public static final Color COLOR_CHARGE_BAR = new Color(1f, 0.6f, 0.1f, 0.9f);
  public static final Color COLOR_CHARGE_READY = new Color(1f, 1f, 1f, 0.9f);
  public static final Color COLOR_VARIABLE_DAMAGE_DOT = new Color(1f, 0.2f, 0.2f, 0.9f);
  public static final Color COLOR_DASH_LINE = new Color(0.2f, 1f, 0.5f, 0.6f);
  public static final Color COLOR_HOOK_LINE = new Color(0.8f, 0.8f, 0.8f, 0.8f);
  public static final Color COLOR_REFLECT_AURA = new Color(1f, 0.3f, 1f, 0.5f);

  // ---- Damage number colors ----

  /** Red color for floating HP damage numbers. */
  public static final Color COLOR_DAMAGE_NUMBER = new Color(1f, 0.2f, 0.2f, 1f);

  /** Gold color for floating shield damage numbers. */
  public static final Color COLOR_SHIELD_DAMAGE_NUMBER = new Color(1f, 0.85f, 0.2f, 1f);
}
