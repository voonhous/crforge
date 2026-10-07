package org.crforge.desktop.render;

import com.badlogic.gdx.Input;

/** Semantic workspace commands shared by toolbar controls and keyboard bindings. */
public enum WorkspaceAction {
  PAUSE,
  STEP,
  TOGGLE_INSPECT,
  RESTART,
  HEADINGS,
  RANGES,
  DAMAGE,
  AREA_HITS,
  HP,
  LEGACY_PATHFINDING,
  CELL_COSTS,
  ROUTES,
  FLIP,
  SIDEBAR,
  SCENARIO,
  EXPORT,
  NEXT_VERSION,
  FASTER,
  SLOWER,
  CARD_1,
  CARD_2,
  CARD_3,
  CARD_4,
  CARD_5,
  CARD_6,
  CARD_7,
  CARD_8,
  DEPLOY,
  INSPECT,
  REPLAYS,
  PREVIOUS_REPLAY,
  NEXT_REPLAY;

  public static WorkspaceAction fromKey(int key) {
    return switch (key) {
      case Input.Keys.SPACE -> PAUSE;
      case Input.Keys.PERIOD -> STEP;
      case Input.Keys.I -> TOGGLE_INSPECT;
      case Input.Keys.R -> RESTART;
      case Input.Keys.P -> HEADINGS;
      case Input.Keys.O -> RANGES;
      case Input.Keys.D -> DAMAGE;
      case Input.Keys.A -> AREA_HITS;
      case Input.Keys.H -> HP;
      case Input.Keys.M -> LEGACY_PATHFINDING;
      case Input.Keys.G -> CELL_COSTS;
      case Input.Keys.N -> ROUTES;
      case Input.Keys.F -> FLIP;
      case Input.Keys.T -> SIDEBAR;
      case Input.Keys.S -> SCENARIO;
      case Input.Keys.E -> EXPORT;
      case Input.Keys.V -> NEXT_VERSION;
      case Input.Keys.PLUS, Input.Keys.EQUALS -> FASTER;
      case Input.Keys.MINUS -> SLOWER;
      case Input.Keys.NUM_1 -> CARD_1;
      case Input.Keys.NUM_2 -> CARD_2;
      case Input.Keys.NUM_3 -> CARD_3;
      case Input.Keys.NUM_4 -> CARD_4;
      case Input.Keys.NUM_5 -> CARD_5;
      case Input.Keys.NUM_6 -> CARD_6;
      case Input.Keys.NUM_7 -> CARD_7;
      case Input.Keys.NUM_8 -> CARD_8;
      case Input.Keys.L -> REPLAYS;
      case Input.Keys.LEFT_BRACKET -> PREVIOUS_REPLAY;
      case Input.Keys.RIGHT_BRACKET -> NEXT_REPLAY;
      default -> null;
    };
  }
}
