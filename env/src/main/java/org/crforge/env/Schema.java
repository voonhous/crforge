/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.BuffInstance;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * The flat layout an environment writes a seat's observation, its legality mask and its action in:
 * version {@link #VERSION}. Everything is a 32-bit integer in the machine's byte order, so the
 * Python side reads the buffers as numpy arrays without a copy; nothing here is a float.
 *
 * <p>A seat is one player's view of a battle, in the game's own view of that player's side: the
 * player's king at the bottom. The battle's coordinates have y grow from side 0 towards side 1. The
 * game draws side 1's view with the positions as they are along the width and mirrored along the
 * length, and side 0's view as that view turned half a turn, so mirrored along the width and kept
 * along the length ({@link #toSeatX(int, int)}, {@link #toSeatY(int, int)}). In both, x grows to
 * the right of the player's screen and y from the player's own back line towards the enemy's.
 *
 * <p>A seat's observation is {@link #SEAT_INTS} integers: a header of {@link #HEADER_INTS}, then
 * {@link #MAX_ENTITIES} entity rows of {@link #ENTITY_INTS} each, the first {@link #H_ENTITY_COUNT}
 * of them written and the rest zero. The header's fields are named {@code H_*} and listed by {@link
 * #headerFields()}; an entity row's fields {@code E_*}, by {@link #entityFields()}. The legality
 * mask is {@link #ACTIONS} bytes, one per action, 1 for an action the seat may take now.
 *
 * <p>An action is one integer: {@link #WAIT}, a play of a hand slot's card dropped at a tile of the
 * seat's frame ({@link #play(int, int, int)}), or the tap on one of the king's champion slots'
 * ability buttons ({@link #ability(int)}), after every play. The arena is {@link #COLS} tiles
 * across and {@link #ROWS} along, a tile being {@link #TILE_UNITS} game units, two of the routing
 * grid's cells; a play is dropped at the tile's centre, and the player's commands send it on the
 * tile the placement search finds from there.
 *
 * <p>What a seat is shown of the other side is what its player sees on the screen: the entities on
 * the field but enemy characters that are hidden, invisible or underground ({@link SeatView}, the
 * one place that rule lives), the enemy's crowns and towers, the enemy's cards in the order their
 * plays ran, its last plays with where and when they ran, and its elixir as a player counts it from
 * those plays and the elixir rules. The enemy's hand, cycle, elixir and commands not yet run are
 * not shown. Nothing here is held to a recording: it is the environment's own contract, kept stable
 * by its version and its tests.
 */
public final class Schema {

  private Schema() {
    // Constants
  }

  /** The version of this layout; a change to any size, field order or code moves it. */
  public static final int VERSION = 3;

  /**
   * Mask rule 1: every tile of a hand slot whose card the client's try passes is open, as a drop
   * anywhere is moved to the tile the placement search finds.
   */
  public static final int MASK_ALL_TILES = 1;

  /**
   * Mask rule 2: of a hand slot whose card the client's try passes, only the tiles the card lands
   * on without being moved are open: those whose centre the placement search, run on the battle as
   * the seat sees it, does not move off the tile.
   */
  public static final int MASK_LANDING_TILES = 2;

  /**
   * The mask rule an environment writes by default, the latest. The rule is versioned apart from
   * the layout: the mask is not part of the observation, and both rules use the same actions, so a
   * change of rule leaves {@link #VERSION} where it is.
   */
  public static final int MASK_VERSION = MASK_LANDING_TILES;

  /**
   * Id rule 1: an entity's id ({@link #E_ID}, {@link #E_TARGET}, a champion slot's unit) is the
   * battle's own id, global and in creation order, the same for both seats. The rule of schema
   * version 3 before 2026-10-18; a gap between two ids told a seat that entities it was not shown
   * exist.
   */
  public static final int ID_BATTLE = 1;

  /**
   * Id rule 2: each seat numbers the entities 0, 1, 2, ... in the order it is first shown them, an
   * id stable through the entity's life and never given to another within the episode, the two
   * seats apart ({@link SeatIds}).
   */
  public static final int ID_SEAT_FIRST_SIGHT = 2;

  /**
   * The id rule an environment writes, versioned apart from the layout: both rules write the same
   * fields with the same meaning (an id names one entity, a row's target names its row), only their
   * values differ, so a change of rule leaves {@link #VERSION} where it is.
   */
  public static final int ID_VERSION = ID_SEAT_FIRST_SIGHT;

  /** Game units in one tile of the action grid: two routing cells. */
  public static final int TILE_UNITS = 2 * TileMap.CELL_UNITS;

  /** Tiles across the standard arena. */
  public static final int COLS = TileMap.standard1v1().width() / 2;

  /** Tiles along the standard arena. */
  public static final int ROWS = TileMap.standard1v1().height() / 2;

  /** Tiles in one hand slot's block of play actions. */
  public static final int TILES = COLS * ROWS;

  /** The arena's width in game units. */
  public static final int WIDTH_UNITS = TileMap.standard1v1().widthUnits();

  /** The arena's length in game units. */
  public static final int HEIGHT_UNITS = TileMap.standard1v1().heightUnits();

  /** Hand slots a play may name. */
  public static final int HAND_SLOTS = Hand.SLOTS;

  /** Cards in a deck. */
  public static final int DECK_SIZE = 8;

  /** The wait action: no play this decision. */
  public static final int WAIT = 0;

  /** The king's champion slots, one ability action each. */
  public static final int ABILITY_SLOTS = 2;

  /** The first ability action, after every play: the ability of the king's first champion slot. */
  public static final int FIRST_ABILITY = 1 + HAND_SLOTS * TILES;

  /**
   * Actions a seat chooses from: the wait, a play of each slot at each tile, and the ability of
   * each champion slot.
   */
  public static final int ACTIONS = FIRST_ABILITY + ABILITY_SLOTS;

  /** Ticks between a command's stamp and the step it runs in, the battle core's own. */
  public static final int PLAY_DELAY_TICKS = Standard1v1Battle.PLAY_DELAY_TICKS;

  /**
   * The waiting command rows of the header: one per hand slot, as a hand slot's card has at most
   * one play waiting.
   */
  public static final int PENDING_ROWS = HAND_SLOTS;

  /** Fields of a waiting command row: card, x, y, ticks until its run. */
  public static final int PENDING_INTS = 4;

  /** The enemy play rows of the header: the enemy's last plays, newest first. */
  public static final int ENEMY_PLAY_ROWS = 8;

  /** Fields of an enemy play row: card, the row it was cast as, form, ticks since, x, y. */
  public static final int ENEMY_PLAY_INTS = 6;

  /** The cards of the seat's own queue the header lists: the whole queue behind the hand. */
  public static final int QUEUE_SLOTS = 4;

  // The header, by field index.

  /** The battle's tick counter: completed steps. */
  public static final int H_TICK = 0;

  /** The seat's result: {@link #RUNNING}, {@link #WON}, {@link #LOST} or {@link #DRAW}. */
  public static final int H_RESULT = 1;

  /** The seat's elixir, in ten-thousandths, as the king holds it. */
  public static final int H_ELIXIR = 2;

  /**
   * The elixir the seat's client holds back for the commands it gave, in ten-thousandths: each
   * waiting command's cost and each play refused at its run whose promise is still kept.
   */
  public static final int H_ELIXIR_RESERVED = 3;

  /** The seat's crowns. */
  public static final int H_CROWNS = 4;

  /** The enemy's crowns. */
  public static final int H_CROWNS_ENEMY = 5;

  /** The hand's refill cooldown in milliseconds. */
  public static final int H_HAND_REFILL_MS = 6;

  /** The vocabulary id of the card in each hand slot, -1 for an empty slot; 4 fields. */
  public static final int H_HAND_CARD = 7;

  /** Each hand slot's card's listed cost in whole elixir, 0 for an empty slot; 4 fields. */
  public static final int H_HAND_COST = 11;

  /** 1 for a hand slot whose card has a play given and not yet run; 4 fields. */
  public static final int H_HAND_PENDING = 15;

  /** The vocabulary id of the card that refills the hand next, -1 for none. */
  public static final int H_NEXT_CARD = 19;

  /** The seat's deck, by vocabulary id; 8 fields. */
  public static final int H_DECK = 20;

  /**
   * The enemy's cards as their plays ran, first play first, -1 beyond; 8 fields. A Mirror's play
   * lists the Mirror and the card it repeated.
   */
  public static final int H_ENEMY_SEEN = 28;

  /**
   * The towers' hit points and maximum, 12 fields: the seat's king, its left and its right princess
   * tower, then the enemy's king, left and right, each as hit points then maximum. A fallen tower
   * reads 0 hit points. Left and right are as the seat's player sees them, in the seat's frame.
   */
  public static final int H_TOWERS = 36;

  /** Entity rows written. */
  public static final int H_ENTITY_COUNT = 48;

  /** Entities left out because the rows were full. */
  public static final int H_ENTITY_OVERFLOW = 49;

  /** Ticks one decision advances the battle. */
  public static final int H_DECISION_TICKS = 50;

  /** Ticks between a command's stamp and its run, {@link #PLAY_DELAY_TICKS}. */
  public static final int H_PLAY_DELAY_TICKS = 51;

  /** The game side the seat plays, 0 or 1. */
  public static final int H_SEAT = 52;

  /** The seat's commands given and not yet run. */
  public static final int H_PENDING_COUNT = 53;

  /** Decisions taken in the episode so far. */
  public static final int H_EPISODE_STEP = 54;

  /** The battle time on the match's timeline, in milliseconds. */
  public static final int H_TIME_MS = 55;

  /** The timeline's current section, counted from 0. */
  public static final int H_SECTION = 56;

  /** The current section's type, as the game mode's battle timeline lists it. */
  public static final int H_SECTION_TYPE = 57;

  /** The time left in the current section, in milliseconds: the battle clock. */
  public static final int H_SECTION_LEFT_MS = 58;

  /** The time the current elixir rate fills one bar in, in milliseconds. */
  public static final int H_FULL_BAR_MS = 59;

  /** 1 in an overtime section. */
  public static final int H_OVERTIME = 60;

  /** Ticks until the match takes a card play, the start cooldown; 0 once it does. */
  public static final int H_PLAY_OPEN_IN_TICKS = 61;

  /**
   * Why the seat's action of the last decision was refused, a refusal code ({@link
   * #refusalNames()}), or 0 ({@link #REFUSAL_NONE}) for a wait or a play given.
   */
  public static final int H_LAST_REFUSAL = 62;

  /**
   * The code of the seat's play refused at its run in the last decision: the match gate's code, or
   * {@link #RUN_NOT_PLACED} for a play the placement refused; 0 for none.
   */
  public static final int H_LAST_RUN_REFUSAL = 63;

  /** The seat's actions refused as illegal in the episode so far. */
  public static final int H_ILLEGAL = 64;

  /**
   * The seat's waiting commands, oldest first, {@link #PENDING_ROWS} rows of {@link #PENDING_INTS}:
   * the card's vocabulary id, the point the command carries in the seat's frame (x, y in game
   * units) and the ticks until it runs; rows beyond {@link #H_PENDING_COUNT} are -1, 0, 0, 0.
   */
  public static final int H_PENDING = 65;

  /**
   * Each deck card's slot flags, by deck index: bit 0 ({@link #SLOT_EVOLUTION}) for the deck's
   * evolution slot, bit 1 ({@link #SLOT_HERO}) for its hero slot; 8 fields.
   */
  public static final int H_DECK_SLOTS = H_PENDING + PENDING_ROWS * PENDING_INTS;

  /**
   * Each deck card's evolution count, by deck index: the plays of an evolution slot's card since
   * its last evolved play, as the king counts them; -1 where the count is not established (a play
   * of the card refused at its placement); 8 fields.
   */
  public static final int H_DECK_EVOLUTION = H_DECK_SLOTS + DECK_SIZE;

  /**
   * The form a play of each hand slot's card would take now: 0 plain, {@link #FORM_EVOLVED} or
   * {@link #FORM_HERO}; 0 for an empty slot; 4 fields.
   */
  public static final int H_HAND_FORM = H_DECK_EVOLUTION + DECK_SIZE;

  /**
   * The king's champion slots, {@link #ABILITY_SLOTS} rows of {@link #ABILITY_INTS}, the slot of
   * ability action {@code FIRST_ABILITY + k} in row {@code k}: the vocabulary id of the champion
   * row it follows (-1 for none), the seat's id ({@link #E_ID}) of the unit its action names while
   * that unit lives (-1 otherwise), the button state as the battle core works it out, the cooldown
   * left in milliseconds, the charges left (-1 for uncounted), and the ticks until the seat's
   * latest ability command for the slot runs (0 for none waiting).
   */
  public static final int H_ABILITY = H_HAND_FORM + HAND_SLOTS;

  /** Fields of a champion slot row: champion, unit, state, cooldown, charges, command ticks. */
  public static final int ABILITY_INTS = 6;

  /**
   * The code of the seat's ability command refused at its run in the last decision, the ability
   * gate's code; 0 for none.
   */
  public static final int H_LAST_ABILITY_RUN_REFUSAL = H_ABILITY + ABILITY_SLOTS * ABILITY_INTS;

  /**
   * The enemy's plays placed so far in the episode, as the seat's player saw them run: each a card
   * of the enemy's deck placed or cast on the field.
   */
  public static final int H_ENEMY_PLAY_COUNT = H_LAST_ABILITY_RUN_REFUSAL + 1;

  /**
   * The enemy's elixir as the seat's player counts it, in ten-thousandths: the match's starting
   * elixir, the regeneration the clock's elixir rate gives each tick up to the maximum, less the
   * cost of each enemy play placed and of each enemy champion ability that ran, with a played row's
   * production stop. Counted from nothing the player does not see; what the enemy's own elixir
   * gains beyond the rate (a collector, an enemy's death that pays elixir) is not counted.
   */
  public static final int H_ENEMY_ELIXIR = H_ENEMY_PLAY_COUNT + 1;

  /**
   * The enemy's last plays placed, newest first, {@link #ENEMY_PLAY_ROWS} rows of {@link
   * #ENEMY_PLAY_INTS}: the vocabulary id of the deck card played, the vocabulary id of the row it
   * was cast as (its evolved or hero row, a variant's option, the card a Mirror repeated; the card
   * itself otherwise), its form (0 plain, {@link #FORM_EVOLVED} or {@link #FORM_HERO}), the ticks
   * since it ran, and the point it was placed at in the seat's frame (x, y in game units); rows
   * beyond the plays are -1, -1, 0, 0, 0, 0.
   */
  public static final int H_ENEMY_PLAYS = H_ENEMY_ELIXIR + 1;

  /**
   * The seat's own queue, the cards behind the hand in the order they come back, by vocabulary id,
   * the first being {@link #H_NEXT_CARD}; -1 beyond; {@link #QUEUE_SLOTS} fields.
   */
  public static final int H_QUEUE = H_ENEMY_PLAYS + ENEMY_PLAY_ROWS * ENEMY_PLAY_INTS;

  /**
   * The level, counted from 1, each of the seat's deck cards is played at, by deck index; 8 fields.
   */
  public static final int H_DECK_LEVEL = H_QUEUE + QUEUE_SLOTS;

  /** The header's size; fields after {@link #H_DECK_LEVEL}'s are reserved and zero. */
  public static final int HEADER_INTS = 192;

  // The slot flags and the forms of a play.

  /** The slot flag of a card in the deck's evolution slot. */
  public static final int SLOT_EVOLUTION = MatchSide.EVOLUTION_SLOT;

  /** The slot flag of a card in the deck's hero slot. */
  public static final int SLOT_HERO = MatchSide.HERO_SLOT;

  /** The form of an evolved play. */
  public static final int FORM_EVOLVED = EvolutionItem.EVOLVED;

  /** The form of a play in its hero form. */
  public static final int FORM_HERO = EvolutionItem.HERO;

  // The results.

  public static final int RUNNING = 0;
  public static final int WON = 1;
  public static final int LOST = 2;
  public static final int DRAW = 3;

  // The refusal codes beyond the player's commands' own.

  /** No refusal. */
  public static final int REFUSAL_NONE = 0;

  /** An action the seat's mask closed, or out of range: never given. */
  public static final int REFUSAL_MASKED = 100;

  /** A play the battle core refuses to give as not modelled: never given, not a fault. */
  public static final int REFUSAL_NOT_MODELLED = 101;

  /** The run refusal of a play whose placement found no place: not placed. */
  public static final int RUN_NOT_PLACED = 1000;

  // An entity row, by field index.

  /**
   * The seat's id of the entity, under id rule {@link #ID_VERSION}: the seat's own number of it, in
   * the order the seat was first shown its entities, stable through its life ({@link SeatIds}).
   */
  public static final int E_ID = 0;

  /**
   * {@link #KIND_CHARACTER}, {@link #KIND_TOWER}, {@link #KIND_PROJECTILE} or {@link #KIND_AREA}.
   */
  public static final int E_KIND = 1;

  /** 0 for the seat's own, 1 for the enemy's, 2 for neither side's. */
  public static final int E_SIDE = 2;

  /** The vocabulary id of the entity's row. */
  public static final int E_ROW = 3;

  /** The position in game units, in the seat's frame; 2 fields. */
  public static final int E_X = 4;

  public static final int E_Y = 5;

  /** Hit points, maximum and shield; 0 for an entity without hit points. */
  public static final int E_HP = 6;

  public static final int E_MAX_HP = 7;

  public static final int E_SHIELD = 8;

  /**
   * A character's or tower's state, a category ({@link #stateNames()}), not an amount; {@link
   * #NO_STATE} for a projectile or an area effect.
   */
  public static final int E_STATE = 9;

  /** Flag bits {@code F_*}. */
  public static final int E_FLAGS = 10;

  /** The entity's level, counted from 1. */
  public static final int E_LEVEL = 11;

  /**
   * The move since the seat's last observation, in game units of the seat's frame (x, y); 0, 0 for
   * an entity that observation did not show. 2 fields.
   */
  public static final int E_DX = 12;

  public static final int E_DY = 13;

  /**
   * The seat's id of the entity's target, {@link #NO_TARGET} for none or for one the seat does not
   * see: a character's or tower's reference, a projectile's target.
   */
  public static final int E_TARGET = 14;

  /** The target's position in the seat's frame, 0, 0 for none; 2 fields. */
  public static final int E_TARGET_X = 15;

  public static final int E_TARGET_Y = 16;

  /** A character's deploy time left, in milliseconds. */
  public static final int E_DEPLOY_MS = 17;

  /** A character's or tower's attack timer as its targeting holds it: the attack time run. */
  public static final int E_ATTACK_MS = 18;

  /** A character's or tower's load countdown: the wind-up still to run, in milliseconds. */
  public static final int E_LOAD_MS = 19;

  /**
   * A character's or tower's movement speed after its buffs, in percent of its own: 100 with none,
   * 0 when they stop it; 0 for a projectile or an area effect.
   */
  public static final int E_SPEED_PERCENT = 20;

  /**
   * A character's or tower's attack pace after its buffs, in percent of its own: 100 with none, 0
   * under a stun or a freeze; 0 for a projectile or an area effect.
   */
  public static final int E_HIT_SPEED_PERCENT = 21;

  /** The buffs listed on a character or tower. */
  public static final int E_BUFF_COUNT = 22;

  /**
   * The first {@link #BUFF_SLOTS} buffs listed, oldest first, each the buff's id in the buff list
   * ({@link Vocabulary#buffNames()}, apart from the row vocabulary) and its time left in
   * milliseconds ({@link #BUFF_FOREVER} for one that never runs out); unused slots -1, 0.
   */
  public static final int E_BUFFS = 23;

  /** Buff slots of an entity row. */
  public static final int BUFF_SLOTS = 3;

  /** A projectile's aim, the point its flight ends at, in the seat's frame; 2 fields. */
  public static final int E_AIM_X = E_BUFFS + 2 * BUFF_SLOTS;

  public static final int E_AIM_Y = E_AIM_X + 1;

  /** An area effect's life left, in milliseconds. */
  public static final int E_TIME_LEFT_MS = E_AIM_Y + 1;

  /** An area effect's radius at its last update, in game units. */
  public static final int E_RADIUS = E_TIME_LEFT_MS + 1;

  /** An entity row's size. */
  public static final int ENTITY_INTS = E_RADIUS + 1;

  /** {@link #E_STATE} of an entity that has no state: a projectile or an area effect. */
  public static final int NO_STATE = -1;

  /** {@link #E_TARGET} of an entity with no target the seat sees. */
  public static final int NO_TARGET = -1;

  /** A buff slot's time left for a buff that never runs out. */
  public static final int BUFF_FOREVER = BuffInstance.FOREVER;

  /**
   * The most entities a seat's observation lists. Towers and characters are listed first, in the
   * battle's holder order (oldest first), then area effects and projectiles, newest first: when the
   * rows are full the oldest projectiles and area effects are left out before any character, and
   * the newest characters only once those are all out.
   */
  public static final int MAX_ENTITIES = 200;

  /** A seat's observation: the header and the entity rows. */
  public static final int SEAT_INTS = HEADER_INTS + MAX_ENTITIES * ENTITY_INTS;

  // The entity kinds.

  public static final int KIND_CHARACTER = 1;
  public static final int KIND_TOWER = 2;
  public static final int KIND_PROJECTILE = 3;
  public static final int KIND_AREA = 4;

  // The entity flags.

  /**
   * In play: a character or tower with hit points left (a fallen tower stays listed without it
   * until the battle lets it go), and every projectile and area effect listed.
   */
  public static final int F_ALIVE = 1;

  public static final int F_HIDDEN = 1 << 1;
  public static final int F_INVISIBLE = 1 << 2;
  public static final int F_UNDERGROUND = 1 << 3;
  public static final int F_AIR = 1 << 4;
  public static final int F_BUILDING = 1 << 5;
  public static final int F_KING = 1 << 6;

  /** A clone. */
  public static final int F_CLONE = 1 << 7;

  /** A tower whose targeting is on: a princess tower, a king woken; 0 for other kinds. */
  public static final int F_AWAKE = 1 << 8;

  /** A character or tower whose tags forbid it to move. */
  public static final int F_NO_MOVE = 1 << 9;

  /** A character or tower whose tags forbid it to attack. */
  public static final int F_NO_ATTACK = 1 << 10;

  /** A character whose charge is complete. */
  public static final int F_CHARGED = 1 << 11;

  // The per-environment info record, by field index.

  public static final int I_TICK = 0;
  public static final int I_EPISODE_STEP = 1;

  /**
   * The winning game side, 0 or 1, {@link #WINNER_DRAW} for a match ended with no winner, or {@link
   * #WINNER_NONE} while the match runs and for an episode cut short.
   */
  public static final int I_WINNER = 2;

  public static final int I_CROWNS_0 = 3;
  public static final int I_CROWNS_1 = 4;

  /**
   * The kind of the fault that ended the episode in this step: {@link #FAULT_NONE}, {@link
   * #FAULT_REFUSAL}, {@link #FAULT_BUG} or {@link #FAULT_ENV_BUG}; its message is {@link
   * VectorEnv#fault(int)}.
   */
  public static final int I_FAULT = 5;

  /** 1 when this step reset the environment instead of stepping it. */
  public static final int I_RESET = 6;

  /** 1 when seat 0's action of this step was refused as illegal and taken as a wait. */
  public static final int I_ILLEGAL_0 = 7;

  /** 1 when seat 1's action of this step was refused as illegal and taken as a wait. */
  public static final int I_ILLEGAL_1 = 8;

  /** 1 when the episode ended with no result: at the tick limit, or by a fault. */
  public static final int I_TRUNCATED = 9;

  /** The side whose action was given first this step. */
  public static final int I_FIRST_SIDE = 10;

  /** The plays of this step the battle core refused to give as not modelled, both seats. */
  public static final int I_NOT_MODELLED = 11;

  /** The info record's size. */
  public static final int INFO_INTS = 12;

  /** {@link #I_WINNER} while the match runs, and for an episode cut short. */
  public static final int WINNER_NONE = -1;

  /** {@link #I_WINNER} for a match that ended with no winner. */
  public static final int WINNER_DRAW = 2;

  // The fault kinds, as the info record numbers them.

  public static final int FAULT_NONE = 0;
  public static final int FAULT_REFUSAL = 1;
  public static final int FAULT_BUG = 2;
  public static final int FAULT_ENV_BUG = 3;

  /**
   * The action that plays a hand slot's card at a tile of the seat's frame.
   *
   * @param slot the hand slot, 0 to 3
   * @param col the tile across, 0 at the seat's left
   * @param row the tile along, 0 at the seat's own back line
   */
  public static int play(int slot, int col, int row) {
    if (slot < 0 || slot >= HAND_SLOTS || col < 0 || col >= COLS || row < 0 || row >= ROWS) {
      throw new IllegalArgumentException(
          "no action for slot " + slot + " at tile " + col + ", " + row);
    }
    return 1 + slot * TILES + row * COLS + col;
  }

  /** The hand slot a play action names. */
  public static int slot(int action) {
    checkPlay(action);
    return (action - 1) / TILES;
  }

  /** The tile across a play action names, in the seat's frame. */
  public static int col(int action) {
    checkPlay(action);
    return (action - 1) % TILES % COLS;
  }

  /** The tile along a play action names, in the seat's frame. */
  public static int row(int action) {
    checkPlay(action);
    return (action - 1) % TILES / COLS;
  }

  private static void checkPlay(int action) {
    if (action <= WAIT || action >= FIRST_ABILITY) {
      throw new IllegalArgumentException("no play action " + action);
    }
  }

  /**
   * The action that taps the ability of one of the king's champion slots.
   *
   * @param slot the champion slot, 0 for the king's first, 1 for its second
   */
  public static int ability(int slot) {
    if (slot < 0 || slot >= ABILITY_SLOTS) {
      throw new IllegalArgumentException("no ability action for champion slot " + slot);
    }
    return FIRST_ABILITY + slot;
  }

  /** Whether an action is an ability action. */
  public static boolean isAbility(int action) {
    return action >= FIRST_ABILITY && action < ACTIONS;
  }

  /** The champion slot an ability action names, 0 or 1. */
  public static int abilitySlot(int action) {
    if (!isAbility(action)) {
      throw new IllegalArgumentException("no ability action " + action);
    }
    return action - FIRST_ABILITY;
  }

  /** The game unit across of the centre of a tile of a seat's frame. */
  public static int gameX(int side, int col) {
    return toSeatX(side, col * TILE_UNITS + TILE_UNITS / 2);
  }

  /** The game unit along of the centre of a tile of a seat's frame. */
  public static int gameY(int side, int row) {
    return toSeatY(side, row * TILE_UNITS + TILE_UNITS / 2);
  }

  /**
   * A game unit across, turned into a seat's frame: mirrored for side 0, kept for side 1, as the
   * game's views draw it. Its own inverse, so it also turns the seat's frame back into the game's.
   */
  public static int toSeatX(int side, int x) {
    return side == 0 ? WIDTH_UNITS - x : x;
  }

  /**
   * A game unit along, turned into a seat's frame: kept for side 0, mirrored for side 1, so each
   * seat's own back line is at 0. Its own inverse.
   */
  public static int toSeatY(int side, int y) {
    return side == 0 ? y : HEIGHT_UNITS - y;
  }

  /**
   * The refusal codes' names, by code: the player's commands' own refusals at their order (0 for
   * none), then {@link #REFUSAL_MASKED} and {@link #REFUSAL_NOT_MODELLED}, which no giving makes.
   * Gaps are null.
   */
  public static List<String> refusalNames() {
    String[] names = new String[REFUSAL_NOT_MODELLED + 1];
    for (PlayerCommands.Refusal refusal : PlayerCommands.Refusal.values()) {
      names[refusal.ordinal()] = refusal.name();
    }
    names[REFUSAL_MASKED] = "MASKED";
    names[REFUSAL_NOT_MODELLED] = "NOT_MODELLED";
    return Arrays.asList(names);
  }

  /** The refusal code of a giving's refusal. */
  public static int refusalCode(PlayerCommands.Refusal refusal) {
    return refusal.ordinal();
  }

  /** The fault kinds' names, by the info record's number. */
  public static List<String> faultNames() {
    return List.of("none", "refusal", "bug", "env_bug");
  }

  /**
   * The header's field names, in order; a field of several values lists its name once per value.
   */
  public static List<String> headerFields() {
    String[] names = new String[HEADER_INTS];
    Arrays.fill(names, "reserved");
    names[H_TICK] = "tick";
    names[H_RESULT] = "result";
    names[H_ELIXIR] = "elixir";
    names[H_ELIXIR_RESERVED] = "elixir_reserved";
    names[H_CROWNS] = "crowns";
    names[H_CROWNS_ENEMY] = "crowns_enemy";
    names[H_HAND_REFILL_MS] = "hand_refill_ms";
    for (int k = 0; k < HAND_SLOTS; k++) {
      names[H_HAND_CARD + k] = "hand_card";
      names[H_HAND_COST + k] = "hand_cost";
      names[H_HAND_PENDING + k] = "hand_pending";
    }
    names[H_NEXT_CARD] = "next_card";
    for (int k = 0; k < DECK_SIZE; k++) {
      names[H_DECK + k] = "deck";
      names[H_ENEMY_SEEN + k] = "enemy_seen";
    }
    String[] towers = {
      "own_king", "own_left", "own_right", "enemy_king", "enemy_left", "enemy_right"
    };
    for (int t = 0; t < towers.length; t++) {
      names[H_TOWERS + 2 * t] = towers[t] + "_hp";
      names[H_TOWERS + 2 * t + 1] = towers[t] + "_max_hp";
    }
    names[H_ENTITY_COUNT] = "entity_count";
    names[H_ENTITY_OVERFLOW] = "entity_overflow";
    names[H_DECISION_TICKS] = "decision_ticks";
    names[H_PLAY_DELAY_TICKS] = "play_delay_ticks";
    names[H_SEAT] = "seat";
    names[H_PENDING_COUNT] = "pending_count";
    names[H_EPISODE_STEP] = "episode_step";
    names[H_TIME_MS] = "time_ms";
    names[H_SECTION] = "section";
    names[H_SECTION_TYPE] = "section_type";
    names[H_SECTION_LEFT_MS] = "section_left_ms";
    names[H_FULL_BAR_MS] = "full_bar_ms";
    names[H_OVERTIME] = "overtime";
    names[H_PLAY_OPEN_IN_TICKS] = "play_open_in_ticks";
    names[H_LAST_REFUSAL] = "last_refusal";
    names[H_LAST_RUN_REFUSAL] = "last_run_refusal";
    names[H_ILLEGAL] = "illegal";
    String[] pending = {"pending_card", "pending_x", "pending_y", "pending_ticks"};
    for (int r = 0; r < PENDING_ROWS; r++) {
      for (int f = 0; f < PENDING_INTS; f++) {
        names[H_PENDING + r * PENDING_INTS + f] = pending[f];
      }
    }
    for (int k = 0; k < DECK_SIZE; k++) {
      names[H_DECK_SLOTS + k] = "deck_slot";
      names[H_DECK_EVOLUTION + k] = "deck_evolution";
    }
    for (int k = 0; k < HAND_SLOTS; k++) {
      names[H_HAND_FORM + k] = "hand_form";
    }
    String[] ability = {
      "ability_champion",
      "ability_unit",
      "ability_state",
      "ability_cooldown_ms",
      "ability_charges",
      "ability_command_ticks"
    };
    for (int r = 0; r < ABILITY_SLOTS; r++) {
      for (int f = 0; f < ABILITY_INTS; f++) {
        names[H_ABILITY + r * ABILITY_INTS + f] = ability[f];
      }
    }
    names[H_LAST_ABILITY_RUN_REFUSAL] = "last_ability_run_refusal";
    names[H_ENEMY_PLAY_COUNT] = "enemy_play_count";
    names[H_ENEMY_ELIXIR] = "enemy_elixir";
    String[] play = {
      "enemy_play_card",
      "enemy_play_cast",
      "enemy_play_form",
      "enemy_play_ticks",
      "enemy_play_x",
      "enemy_play_y"
    };
    for (int r = 0; r < ENEMY_PLAY_ROWS; r++) {
      for (int f = 0; f < ENEMY_PLAY_INTS; f++) {
        names[H_ENEMY_PLAYS + r * ENEMY_PLAY_INTS + f] = play[f];
      }
    }
    for (int k = 0; k < QUEUE_SLOTS; k++) {
      names[H_QUEUE + k] = "queue";
    }
    for (int k = 0; k < DECK_SIZE; k++) {
      names[H_DECK_LEVEL + k] = "deck_level";
    }
    return List.of(names);
  }

  /** An entity row's field names, in order, each its own. */
  public static List<String> entityFields() {
    String[] names = new String[ENTITY_INTS];
    names[E_ID] = "id";
    names[E_KIND] = "kind";
    names[E_SIDE] = "side";
    names[E_ROW] = "row";
    names[E_X] = "x";
    names[E_Y] = "y";
    names[E_HP] = "hp";
    names[E_MAX_HP] = "max_hp";
    names[E_SHIELD] = "shield";
    names[E_STATE] = "state";
    names[E_FLAGS] = "flags";
    names[E_LEVEL] = "level";
    names[E_DX] = "dx";
    names[E_DY] = "dy";
    names[E_TARGET] = "target";
    names[E_TARGET_X] = "target_x";
    names[E_TARGET_Y] = "target_y";
    names[E_DEPLOY_MS] = "deploy_ms";
    names[E_ATTACK_MS] = "attack_ms";
    names[E_LOAD_MS] = "load_ms";
    names[E_SPEED_PERCENT] = "speed_percent";
    names[E_HIT_SPEED_PERCENT] = "hit_speed_percent";
    names[E_BUFF_COUNT] = "buff_count";
    // Named per slot, so every entity field has a name of its own.
    for (int b = 0; b < BUFF_SLOTS; b++) {
      names[E_BUFFS + 2 * b] = "buff_" + b;
      names[E_BUFFS + 2 * b + 1] = "buff_ms_" + b;
    }
    names[E_AIM_X] = "aim_x";
    names[E_AIM_Y] = "aim_y";
    names[E_TIME_LEFT_MS] = "time_left_ms";
    names[E_RADIUS] = "radius";
    return List.of(names);
  }

  /**
   * The state names, by the state number an entity row's {@link #E_STATE} holds: the battle core's
   * states of a character or tower, a category each.
   */
  public static List<String> stateNames() {
    return STATE_NAMES;
  }

  private static final List<String> STATE_NAMES =
      List.of(
          "standing",
          "moving",
          "attacking",
          "dashing",
          "deploying",
          "jumping",
          "spawn_pathfind",
          "ingame_pathfind",
          "clone_setup",
          "morphing",
          "casting",
          "waiting_to_deploy",
          "following_removed",
          "following_removed_building",
          "components_disabled",
          "route_following_alternate",
          "ability_follow_up");

  /**
   * The scales an encoder reads the amounts by, by name: the elixir's ten-thousandths to a whole
   * elixir, game units to a tile, the arena's size, the milliseconds of a tick, the speed and
   * attack pace percents' 100, and the highest state number.
   */
  public static Map<String, Integer> scales() {
    return Map.of(
        "elixir_scale",
        MatchSide.SCALE,
        "tile_units",
        TILE_UNITS,
        "width_units",
        WIDTH_UNITS,
        "height_units",
        HEIGHT_UNITS,
        "ms_per_tick",
        Battle.STEP_MS,
        "percent",
        100,
        "max_state",
        GridEntityState.MAX_STATE);
  }

  /** The info record's field names, in order. */
  public static List<String> infoFields() {
    return List.of(
        "tick",
        "episode_step",
        "winner",
        "crowns_0",
        "crowns_1",
        "fault",
        "reset",
        "illegal_0",
        "illegal_1",
        "truncated",
        "first_side",
        "not_modelled");
  }

  /** The entity flags' names, by bit, lowest first. */
  public static List<String> flagNames() {
    return List.of(
        "alive",
        "hidden",
        "invisible",
        "underground",
        "air",
        "building",
        "king",
        "clone",
        "awake",
        "no_move",
        "no_attack",
        "charged");
  }
}
