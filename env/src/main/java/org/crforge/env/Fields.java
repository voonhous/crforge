/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.crforge.core.battle.match.BattleTimeline;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.unit.AbilityCommand;
import org.crforge.core.battle.unit.ChampionController;

/**
 * The registry of the fields an environment exports, version {@link #VERSION}: every field's name,
 * place, shape, visibility tag, units and frame. It is the public contract of the field export,
 * kept apart from the observation layout of {@link Schema} (its own version, {@link
 * Schema#VERSION}). The export itself is not written yet: this is the registry it will be written
 * by, published in the vector's metadata as {@code fields} ({@link #toNode()}).
 *
 * <p>The block. Each environment exports one block of {@link #INTS} 32-bit integers a decision, in
 * the machine's byte order as every buffer of {@link Schema}. A field is {@code shape} integers at
 * {@code offset} in the block, laid out row-major (the last axis fastest). The fields are laid out
 * end to end in the order they are declared here, without gaps; an alias holds no integers of its
 * own (below).
 *
 * <p>The tags. A field is tagged {@link Tag#PUBLIC}, {@link Tag#SEAT} or {@link Tag#HIDDEN}:
 *
 * <ul>
 *   <li>{@code public}: the same for both seats, in the game's frame, and shown to both players
 *       (the battle clock, the decision interval).
 *   <li>{@code seat}: what a seat is shown, so what a model may read and still play fair. Its
 *       leading axis of 2 is the seat, the environment's seat 0 or 1 (the game side the seat
 *       plays), and its coordinates are in that seat's frame ({@link Schema#toSeatX}, {@link
 *       Schema#toSeatY}). A seat field holds exactly what that seat's schema v4 observation holds,
 *       computed by the same rules ({@link SeatView} and the environment's counting of the enemy),
 *       so the v4 observation is rebuilt bit for bit from the seat fields and the public ones alone
 *       ({@link #v4Source}).
 *   <li>{@code hidden}: a value the seat is not shown, or not shown in full: the true value of a
 *       seat field, named as it with {@code .true} after it ({@code entity.hp.true}), or a value no
 *       seat is shown at all ({@code entity.battle_id}). Hidden fields are in the game's frame.
 * </ul>
 *
 * <p>A field's plain name is what the seat sees, so a model that reads plain-named fields alone
 * reads honestly; a hidden value always has a name of its own.
 *
 * <p>Aliases. An alias names a seat field read with its seat axis flipped, holding no integers of
 * its own: {@code enemy.elixir.true} at seat s is {@code own.elixir} at seat 1 - s, the enemy's
 * elixir as the enemy holds it. Its offset and shape are those of the field it names ({@code
 * alias_of}), and it is tagged hidden. A coordinate read this way is in the enemy seat's frame
 * ({@link Frame#ENEMY_SEAT}), the frame of the seat it belongs to.
 *
 * <p>The entity table. The entity fields hold {@link #ENTITY_CAPACITY} rows, one entity a row, in
 * the order the v4 packer goes through them: the towers and characters in the battle's holder order
 * (oldest first), every one of them, seen by a seat or not, then the area effects and projectiles,
 * newest first. Entities past the capacity are counted in {@code entity.export_overflow}.
 *
 * <p>The rows a seat's v4 observation lists. A seat's {@code entity.visible} marks the rows the
 * seat is shown (seen), not the rows its v4 observation lists: past {@link Schema#MAX_ENTITIES}
 * rows shown, v4 lists no more. The rows v4 lists for seat s are the first {@code entity.count[s]}
 * rows, in row order, whose {@code entity.visible[s]} is 1; the k-th of them is v4's entity row k.
 * An adapter rebuilding v4 needs both fields: {@code entity.visible} to find the rows, {@code
 * entity.count} to cut them. The seat entity columns (every seat field of the table but {@code
 * entity.visible}) hold v4's values on the rows v4 lists, and 0 on every other row: a row the seat
 * is not shown, a row it is shown past the cut, a row holding no entity. The rebuild is exact while
 * {@code entity.export_overflow} is 0.
 *
 * <p>The units. A field's units are one of: {@code ticks} (50 ms battle steps), {@code ms}, {@code
 * game units} (1000 a tile), {@code 1/10000 elixir}, {@code elixir} (whole elixir), {@code hit
 * points}, {@code percent}, {@code level} (counted from 1), {@code count}, {@code index} (counted
 * from 0), {@code bool} (0 or 1), {@code vocabulary id} (the metadata's vocabulary), {@code buff
 * id} (the metadata's buff list), {@code seat id} (a seat's first-sight id, {@link SeatIds}),
 * {@code battle id} (the battle's own entity id), {@code enum:<name>} (a code, its labels by value
 * in the registry's {@code enums} under the name, keyed by value) or {@code flags:<name>} (bits,
 * their labels in {@code enums} under the name, keyed by bit index; {@link #enums()}). The frame is
 * {@code game} or {@code seat} (or {@code enemy_seat} for an alias) for a coordinate or a move in
 * game units, {@code none} for every other field.
 *
 * <p>The version. Adding a field moves the minor version; renaming or removing one, or moving its
 * meaning, moves the major version. A reader states the version it needs and refuses another major
 * version. The committed snapshot {@code python/tests/data/fields_v1.json} holds the registry's
 * metadata, so any change of it shows in review.
 */
public final class Fields {

  private Fields() {
    // Constants
  }

  /** The registry's version, major.minor, apart from the observation layout's {@link Schema}. */
  public static final String VERSION = "1.0";

  /** The rows of the exported entity table. */
  public static final int ENTITY_CAPACITY = 256;

  /** {@code entity.side.true} of an entity of neither side. */
  public static final int SIDE_NEITHER = 2;

  /** The seats a seat field's leading axis counts. */
  private static final int SEATS = 2;

  /** A field's visibility tag. */
  public enum Tag {
    /** The same for both seats, in the game's frame, shown to both. */
    PUBLIC,
    /** What a seat is shown, with a leading seat axis, in the seat's frame. */
    SEAT,
    /** A value a seat is not shown in full, in the game's frame. */
    HIDDEN;

    /** The tag as the metadata names it. */
    public String json() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The frame of a field's coordinates. */
  public enum Frame {
    /** The battle's own coordinates: y grows from side 0 towards side 1. */
    GAME,
    /** The seat's frame ({@link Schema#toSeatX}, {@link Schema#toSeatY}). */
    SEAT,
    /** The enemy seat's frame: an alias's coordinates, which belong to the other seat. */
    ENEMY_SEAT,
    /** Not a coordinate. */
    NONE;

    /** The frame as the metadata names it. */
    public String json() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * One exported field.
   *
   * @param name its name, dotted, of {@code [a-z_.0-9]}
   * @param offset its first integer in an environment's block
   * @param shape its axes; empty for one integer
   * @param tag what a seat is shown of it
   * @param units its units
   * @param frame the frame of its coordinates
   * @param aliasOf the seat field it reads with the seat axis flipped, or null for a field with
   *     integers of its own
   */
  public record Field(
      String name,
      int offset,
      List<Integer> shape,
      Tag tag,
      String units,
      Frame frame,
      String aliasOf) {

    /** Its integers: the product of its shape. */
    public int size() {
      int size = 1;
      for (int n : shape) {
        size *= n;
      }
      return size;
    }
  }

  /**
   * Where a field's values sit in a seat's schema v4 observation: a header field's indices, or an
   * entity row's field indices, one per element of the field's axes past its seat axis and its
   * entity row axis, in the field's order. A seat field's element at seat s (and row r) is the v4
   * observation of seat s at that index (of the v4 row r stands as); a public field's is the same
   * in both seats' observations.
   *
   * @param entity whether the indices are of an entity row ({@link Schema#entityFields()}) rather
   *     than the header ({@link Schema#headerFields()})
   * @param indices the v4 indices
   */
  public record V4Source(boolean entity, List<Integer> indices) {

    /** The region of the v4 observation the indices are of, as the metadata names it. */
    public String region() {
      return entity ? "entity" : "header";
    }
  }

  // The registration, in declaration order: every field below takes the next integers of the block.

  private static final List<Field> ALL = new ArrayList<>();
  private static final Map<String, Field> BY_NAME = new HashMap<>();
  private static final Map<String, V4Source> V4 = new HashMap<>();
  private static int next;

  // The public fields: the battle clock and the decision interval, the same for both seats.

  /** The battle's tick counter: the steps completed. */
  public static final Field GAME_TICK = pub("game.tick", "ticks", header(Schema.H_TICK));

  /** The decisions taken in the episode so far. */
  public static final Field GAME_EPISODE_STEP =
      pub("game.episode_step", "count", header(Schema.H_EPISODE_STEP));

  /** The ticks one decision advances the battle. */
  public static final Field GAME_DECISION_TICKS =
      pub("game.decision_ticks", "ticks", header(Schema.H_DECISION_TICKS));

  /** The ticks between a command's stamp and its run, {@link Schema#PLAY_DELAY_TICKS}. */
  public static final Field GAME_PLAY_DELAY_TICKS =
      pub("game.play_delay_ticks", "ticks", header(Schema.H_PLAY_DELAY_TICKS));

  /** The battle time on the match's timeline. */
  public static final Field GAME_TIME_MS = pub("game.time_ms", "ms", header(Schema.H_TIME_MS));

  /** The timeline's current section, counted from 0. */
  public static final Field GAME_SECTION = pub("game.section", "index", header(Schema.H_SECTION));

  /** The current section's type, as the game mode's battle timeline lists it. */
  public static final Field GAME_SECTION_TYPE =
      pub("game.section_type", "enum:section_type", header(Schema.H_SECTION_TYPE));

  /** The time left in the current section: the battle clock. */
  public static final Field GAME_SECTION_LEFT_MS =
      pub("game.section_left_ms", "ms", header(Schema.H_SECTION_LEFT_MS));

  /** The time the current elixir rate fills one bar in. */
  public static final Field GAME_FULL_BAR_MS =
      pub("game.full_bar_ms", "ms", header(Schema.H_FULL_BAR_MS));

  /** 1 in an overtime section. */
  public static final Field GAME_OVERTIME = pub("game.overtime", "bool", header(Schema.H_OVERTIME));

  /** The ticks until the match takes a card play, the start cooldown; 0 once it does. */
  public static final Field GAME_PLAY_OPEN_IN_TICKS =
      pub("game.play_open_in_ticks", "ticks", header(Schema.H_PLAY_OPEN_IN_TICKS));

  // The seat fields of the header: the seat's own side.

  /** The game side the seat plays, 0 or 1: the seat index itself. */
  public static final Field OWN_SIDE = seat("own.side", "enum:side", header(Schema.H_SEAT));

  /** The seat's result: {@link Schema#RUNNING}, {@link Schema#WON}, {@link Schema#LOST} or draw. */
  public static final Field OWN_RESULT = seat("own.result", "enum:result", header(Schema.H_RESULT));

  /** The seat's elixir as the king holds it. */
  public static final Field OWN_ELIXIR =
      seat("own.elixir", "1/10000 elixir", header(Schema.H_ELIXIR));

  /**
   * The elixir the seat's client holds back for the commands it gave: each waiting command's cost
   * and each play refused at its run whose promise is still kept.
   */
  public static final Field OWN_ELIXIR_RESERVED =
      seat("own.elixir_reserved", "1/10000 elixir", header(Schema.H_ELIXIR_RESERVED));

  /** The seat's crowns. */
  public static final Field OWN_CROWNS = seat("own.crowns", "count", header(Schema.H_CROWNS));

  /** The enemy's crowns, as the seat is shown them: all of them. */
  public static final Field ENEMY_CROWNS =
      seat("enemy.crowns", "count", header(Schema.H_CROWNS_ENEMY));

  /** The seat's hand's refill cooldown. */
  public static final Field OWN_HAND_REFILL_MS =
      seat("own.hand.refill_ms", "ms", header(Schema.H_HAND_REFILL_MS));

  /** The card in each of the seat's hand slots, -1 for an empty slot. */
  public static final Field OWN_HAND_CARD =
      seat(
          "own.hand.card",
          "vocabulary id",
          header(Schema.H_HAND_CARD, Schema.HAND_SLOTS, 1),
          Schema.HAND_SLOTS);

  /** Each hand slot's card's listed cost, 0 for an empty slot. */
  public static final Field OWN_HAND_COST =
      seat(
          "own.hand.cost",
          "elixir",
          header(Schema.H_HAND_COST, Schema.HAND_SLOTS, 1),
          Schema.HAND_SLOTS);

  /** 1 for a hand slot whose card has a play given and not run yet. */
  public static final Field OWN_HAND_PENDING =
      seat(
          "own.hand.pending",
          "bool",
          header(Schema.H_HAND_PENDING, Schema.HAND_SLOTS, 1),
          Schema.HAND_SLOTS);

  /**
   * The form a play of each hand slot's card would take now: 0 plain, {@link Schema#FORM_EVOLVED}
   * or {@link Schema#FORM_HERO}; 0 for an empty slot.
   */
  public static final Field OWN_HAND_FORM =
      seat(
          "own.hand.form",
          "enum:form",
          header(Schema.H_HAND_FORM, Schema.HAND_SLOTS, 1),
          Schema.HAND_SLOTS);

  /** The card that refills the seat's hand next, -1 for none. */
  public static final Field OWN_NEXT_CARD =
      seat("own.next_card", "vocabulary id", header(Schema.H_NEXT_CARD));

  /** The seat's queue, the cards behind the hand in the order they come back, -1 beyond. */
  public static final Field OWN_QUEUE =
      seat(
          "own.queue",
          "vocabulary id",
          header(Schema.H_QUEUE, Schema.QUEUE_SLOTS, 1),
          Schema.QUEUE_SLOTS);

  /** The seat's deck, by deck index. */
  public static final Field OWN_DECK_CARD =
      seat(
          "own.deck.card",
          "vocabulary id",
          header(Schema.H_DECK, Schema.DECK_SIZE, 1),
          Schema.DECK_SIZE);

  /** Each deck card's slot flags: the deck's evolution slot, its hero slot. */
  public static final Field OWN_DECK_SLOT =
      seat(
          "own.deck.slot",
          "flags:slot",
          header(Schema.H_DECK_SLOTS, Schema.DECK_SIZE, 1),
          Schema.DECK_SIZE);

  /**
   * Each deck card's evolution count as the king counts it: the plays of an evolution slot's card
   * since its last evolved play; -1 where it is not established.
   */
  public static final Field OWN_DECK_EVOLUTION =
      seat(
          "own.deck.evolution",
          "count",
          header(Schema.H_DECK_EVOLUTION, Schema.DECK_SIZE, 1),
          Schema.DECK_SIZE);

  /** The level each deck card is played at. */
  public static final Field OWN_DECK_LEVEL =
      seat(
          "own.deck.level",
          "level",
          header(Schema.H_DECK_LEVEL, Schema.DECK_SIZE, 1),
          Schema.DECK_SIZE);

  /** The seat's commands given and not run yet. */
  public static final Field OWN_PENDING_COUNT =
      seat("own.pending.count", "count", header(Schema.H_PENDING_COUNT));

  /** Each waiting command's card, oldest first; -1 past {@code own.pending.count}. */
  public static final Field OWN_PENDING_CARD =
      seat(
          "own.pending.card",
          "vocabulary id",
          header(Schema.H_PENDING, Schema.PENDING_ROWS, Schema.PENDING_INTS),
          Schema.PENDING_ROWS);

  /** The point across each waiting command carries; 0 past the count. */
  public static final Field OWN_PENDING_X =
      seat(
          "own.pending.x",
          "game units",
          Frame.SEAT,
          header(Schema.H_PENDING + 1, Schema.PENDING_ROWS, Schema.PENDING_INTS),
          Schema.PENDING_ROWS);

  /** The point along each waiting command carries; 0 past the count. */
  public static final Field OWN_PENDING_Y =
      seat(
          "own.pending.y",
          "game units",
          Frame.SEAT,
          header(Schema.H_PENDING + 2, Schema.PENDING_ROWS, Schema.PENDING_INTS),
          Schema.PENDING_ROWS);

  /** The ticks until each waiting command runs; 0 past the count. */
  public static final Field OWN_PENDING_TICKS =
      seat(
          "own.pending.ticks",
          "ticks",
          header(Schema.H_PENDING + 3, Schema.PENDING_ROWS, Schema.PENDING_INTS),
          Schema.PENDING_ROWS);

  /** The champion row each of the king's champion slots follows, -1 for none. */
  public static final Field OWN_ABILITY_CHAMPION =
      seat(
          "own.ability.champion",
          "vocabulary id",
          header(Schema.H_ABILITY, Schema.ABILITY_SLOTS, Schema.ABILITY_INTS),
          Schema.ABILITY_SLOTS);

  /** The unit each champion slot's ability action names while it lives, -1 otherwise. */
  public static final Field OWN_ABILITY_UNIT =
      seat(
          "own.ability.unit",
          "seat id",
          header(Schema.H_ABILITY + 1, Schema.ABILITY_SLOTS, Schema.ABILITY_INTS),
          Schema.ABILITY_SLOTS);

  /** Each champion slot's button state as the battle core works it out; 0 for no slot. */
  public static final Field OWN_ABILITY_STATE =
      seat(
          "own.ability.state",
          "enum:ability_state",
          header(Schema.H_ABILITY + 2, Schema.ABILITY_SLOTS, Schema.ABILITY_INTS),
          Schema.ABILITY_SLOTS);

  /** Each champion slot's cooldown left; 0 for no slot. */
  public static final Field OWN_ABILITY_COOLDOWN_MS =
      seat(
          "own.ability.cooldown_ms",
          "ms",
          header(Schema.H_ABILITY + 3, Schema.ABILITY_SLOTS, Schema.ABILITY_INTS),
          Schema.ABILITY_SLOTS);

  /** Each champion slot's charges left, -1 for uncounted; 0 for no slot. */
  public static final Field OWN_ABILITY_CHARGES =
      seat(
          "own.ability.charges",
          "count",
          header(Schema.H_ABILITY + 4, Schema.ABILITY_SLOTS, Schema.ABILITY_INTS),
          Schema.ABILITY_SLOTS);

  /** The ticks until the seat's latest ability command of each slot runs; 0 for none waiting. */
  public static final Field OWN_ABILITY_COMMAND_TICKS =
      seat(
          "own.ability.command_ticks",
          "ticks",
          header(Schema.H_ABILITY + 5, Schema.ABILITY_SLOTS, Schema.ABILITY_INTS),
          Schema.ABILITY_SLOTS);

  /** Why the seat's action of the last decision was refused, 0 for a wait or a play given. */
  public static final Field OWN_LAST_REFUSAL =
      seat("own.last_refusal", "enum:refusal", header(Schema.H_LAST_REFUSAL));

  /**
   * The code of the seat's play refused at its run in the last decision: the match gate's code, or
   * {@link Schema#RUN_NOT_PLACED}; 0 for none.
   */
  public static final Field OWN_LAST_RUN_REFUSAL =
      seat("own.last_run_refusal", "enum:run_refusal", header(Schema.H_LAST_RUN_REFUSAL));

  /** The ability gate's code of the seat's ability command refused at its run, 0 for none. */
  public static final Field OWN_LAST_ABILITY_RUN_REFUSAL =
      seat(
          "own.last_ability_run_refusal",
          "enum:ability_refusal",
          header(Schema.H_LAST_ABILITY_RUN_REFUSAL));

  /** The seat's actions refused as illegal in the episode so far. */
  public static final Field OWN_ILLEGAL = seat("own.illegal", "count", header(Schema.H_ILLEGAL));

  /**
   * The towers' hit points: the seat's king, its left and its right princess tower, then the
   * enemy's king, left and right, left and right as the seat's player sees them; 0 for a fallen
   * tower. Every tower's health bar is shown, so this is the truth too.
   */
  public static final Field TOWER_HP =
      seat("tower.hp", "hit points", header(Schema.H_TOWERS, 6, 2), 6);

  /** The towers' maximum hit points, in the order of {@code tower.hp}. */
  public static final Field TOWER_MAX_HP =
      seat("tower.max_hp", "hit points", header(Schema.H_TOWERS + 1, 6, 2), 6);

  /**
   * The entity rows of the seat's v4 observation: its rows shown, at most {@link
   * Schema#MAX_ENTITIES}.
   */
  public static final Field ENTITY_COUNT =
      seat("entity.count", "count", header(Schema.H_ENTITY_COUNT));

  /**
   * The entities the seat's v4 observation left out because its rows were full: those it is shown
   * past {@link Schema#MAX_ENTITIES}, wherever they are in the export.
   */
  public static final Field ENTITY_OVERFLOW =
      seat("entity.overflow", "count", header(Schema.H_ENTITY_OVERFLOW));

  // The seat fields of the header: the enemy as the seat's player sees it.

  /**
   * The enemy's cards as their plays ran and the screen showed them, first shown first, -1 beyond;
   * a Mirror's play lists the Mirror and the card it repeated.
   */
  public static final Field ENEMY_SEEN_CARD =
      seat(
          "enemy.seen.card",
          "vocabulary id",
          header(Schema.H_ENEMY_SEEN, Schema.DECK_SIZE, 1),
          Schema.DECK_SIZE);

  /**
   * The enemy's evolution progress as the seat's player counts it from the diamonds the screen
   * shows, by the place of the card in {@code enemy.seen.card}; -1 for a card not marked as an
   * evolution card or no card.
   */
  public static final Field ENEMY_SEEN_EVOLUTION =
      seat(
          "enemy.seen.evolution",
          "count",
          header(Schema.H_ENEMY_SEEN_EVOLUTION, Schema.DECK_SIZE, 1),
          Schema.DECK_SIZE);

  /**
   * The plays of base form a card marked in {@code enemy.seen.evolution} needs before it is played
   * evolved; 0 for a card not marked.
   */
  public static final Field ENEMY_SEEN_EVOLUTION_NEED =
      seat(
          "enemy.seen.evolution_need",
          "count",
          header(Schema.H_ENEMY_SEEN_EVOLUTION_NEED, Schema.DECK_SIZE, 1),
          Schema.DECK_SIZE);

  /** The enemy's plays placed so far in the episode, each seen to run. */
  public static final Field ENEMY_PLAYS_COUNT =
      seat("enemy.plays.count", "count", header(Schema.H_ENEMY_PLAY_COUNT));

  /**
   * The enemy's elixir as the seat's player counts it from the plays it was shown and the elixir
   * rules ({@link Schema#H_ENEMY_ELIXIR}).
   */
  public static final Field ENEMY_ELIXIR =
      seat("enemy.elixir", "1/10000 elixir", header(Schema.H_ENEMY_ELIXIR));

  /** Each of the enemy's last plays' deck card, newest first, -1 until the screen shows it. */
  public static final Field ENEMY_PLAYS_CARD = enemyPlays("card", "vocabulary id", Frame.NONE, 0);

  /** Each enemy play's row cast as, -1 until the screen shows the card. */
  public static final Field ENEMY_PLAYS_CAST = enemyPlays("cast", "vocabulary id", Frame.NONE, 1);

  /** Each enemy play's form, -1 until the screen shows the card. */
  public static final Field ENEMY_PLAYS_FORM = enemyPlays("form", "enum:form", Frame.NONE, 2);

  /** The ticks since each enemy play ran; 0 past the plays. */
  public static final Field ENEMY_PLAYS_TICKS = enemyPlays("ticks", "ticks", Frame.NONE, 3);

  /** The point across the screen shows each enemy play at, -1 until shown. */
  public static final Field ENEMY_PLAYS_X = enemyPlays("x", "game units", Frame.SEAT, 4);

  /** The point along the screen shows each enemy play at, -1 until shown. */
  public static final Field ENEMY_PLAYS_Y = enemyPlays("y", "game units", Frame.SEAT, 5);

  /** Each enemy play's kind, {@link Schema#PLAY_TROOP} to {@link Schema#PLAY_TUNNEL}; 0 past. */
  public static final Field ENEMY_PLAYS_KIND = enemyPlays("kind", "enum:play_kind", Frame.NONE, 6);

  /** Each enemy play's flags, the {@code PLAY_*} bits of {@link Schema}. */
  public static final Field ENEMY_PLAYS_FLAGS = enemyPlays("flags", "flags:play", Frame.NONE, 7);

  /** The ticks since each enemy play's point was shown, -1 before; 0 past the plays. */
  public static final Field ENEMY_PLAYS_POINT_TICKS =
      enemyPlays("point_ticks", "ticks", Frame.NONE, 8);

  // The seat fields of the entity table, by export row.

  /**
   * 1 for a row whose entity the seat is shown: its own entities, every tower, every projectile and
   * area effect, every enemy character but one waiting its turn to deploy ({@link SeatView#sees});
   * an enemy unit travelling underground is shown, as its trail. 0 for a row holding no entity.
   */
  public static final Field ENTITY_VISIBLE = seatEntity("entity.visible", "bool", Frame.NONE, null);

  /** The seat's id of the row's entity, in the order the seat was first shown its entities. */
  public static final Field ENTITY_ID =
      seatEntity("entity.id", "seat id", Frame.NONE, entity(Schema.E_ID));

  /** The entity's kind, {@link Schema#KIND_CHARACTER} to {@link Schema#KIND_AREA}. */
  public static final Field ENTITY_KIND =
      seatEntity("entity.kind", "enum:entity_kind", Frame.NONE, entity(Schema.E_KIND));

  /** 0 for the seat's own entity, 1 for the enemy's, 2 for neither side's. */
  public static final Field ENTITY_SIDE =
      seatEntity("entity.side", "enum:relative_side", Frame.NONE, entity(Schema.E_SIDE));

  /** The entity's row, -1 for a trail that does not tell its card. */
  public static final Field ENTITY_ROW =
      seatEntity("entity.row", "vocabulary id", Frame.NONE, entity(Schema.E_ROW));

  /** The entity's position across. */
  public static final Field ENTITY_X =
      seatEntity("entity.x", "game units", Frame.SEAT, entity(Schema.E_X));

  /** The entity's position along. */
  public static final Field ENTITY_Y =
      seatEntity("entity.y", "game units", Frame.SEAT, entity(Schema.E_Y));

  /**
   * The entity's hit points; -1 for an enemy character whose health bar the seat's screen does not
   * show ({@link SeatView.Bars#shown}), 0 for an entity without hit points.
   */
  public static final Field ENTITY_HP =
      seatEntity("entity.hp", "hit points", Frame.NONE, entity(Schema.E_HP));

  /** The entity's maximum hit points; -1 for a trail. */
  public static final Field ENTITY_MAX_HP =
      seatEntity("entity.max_hp", "hit points", Frame.NONE, entity(Schema.E_MAX_HP));

  /** The entity's shield, -1 where its hit points are not shown. */
  public static final Field ENTITY_SHIELD =
      seatEntity("entity.shield", "hit points", Frame.NONE, entity(Schema.E_SHIELD));

  /** A character's or tower's state, {@link Schema#NO_STATE} for a projectile or area effect. */
  public static final Field ENTITY_STATE =
      seatEntity("entity.state", "enum:state", Frame.NONE, entity(Schema.E_STATE));

  /** The entity's flags, the {@code F_*} bits of {@link Schema}; a trail's only its own three. */
  public static final Field ENTITY_FLAGS =
      seatEntity("entity.flags", "flags:entity", Frame.NONE, entity(Schema.E_FLAGS));

  /** The entity's level; -1 for a trail. */
  public static final Field ENTITY_LEVEL =
      seatEntity("entity.level", "level", Frame.NONE, entity(Schema.E_LEVEL));

  /**
   * The entity's move across since the seat's last observation, 0 for one that observation did not
   * list.
   */
  public static final Field ENTITY_DX =
      seatEntity("entity.dx", "game units", Frame.SEAT, entity(Schema.E_DX));

  /** The entity's move along since the seat's last observation, as {@code entity.dx}. */
  public static final Field ENTITY_DY =
      seatEntity("entity.dy", "game units", Frame.SEAT, entity(Schema.E_DY));

  /** The seat's id of the entity's target, {@link Schema#NO_TARGET} for none or one not shown. */
  public static final Field ENTITY_TARGET =
      seatEntity("entity.target", "seat id", Frame.NONE, entity(Schema.E_TARGET));

  /** The target's position across, 0 for none. */
  public static final Field ENTITY_TARGET_X =
      seatEntity("entity.target_x", "game units", Frame.SEAT, entity(Schema.E_TARGET_X));

  /** The target's position along, 0 for none. */
  public static final Field ENTITY_TARGET_Y =
      seatEntity("entity.target_y", "game units", Frame.SEAT, entity(Schema.E_TARGET_Y));

  /** A character's deploy time left. */
  public static final Field ENTITY_DEPLOY_MS =
      seatEntity("entity.deploy_ms", "ms", Frame.NONE, entity(Schema.E_DEPLOY_MS));

  /** A character's or tower's attack timer: the attack time run. */
  public static final Field ENTITY_ATTACK_MS =
      seatEntity("entity.attack_ms", "ms", Frame.NONE, entity(Schema.E_ATTACK_MS));

  /** A character's or tower's load countdown: the wind-up still to run. */
  public static final Field ENTITY_LOAD_MS =
      seatEntity("entity.load_ms", "ms", Frame.NONE, entity(Schema.E_LOAD_MS));

  /** A character's or tower's movement speed after its buffs, in percent of its own. */
  public static final Field ENTITY_SPEED_PERCENT =
      seatEntity("entity.speed_percent", "percent", Frame.NONE, entity(Schema.E_SPEED_PERCENT));

  /** A character's or tower's attack pace after its buffs, in percent of its own. */
  public static final Field ENTITY_HIT_SPEED_PERCENT =
      seatEntity(
          "entity.hit_speed_percent", "percent", Frame.NONE, entity(Schema.E_HIT_SPEED_PERCENT));

  /** The buffs listed on a character or tower. */
  public static final Field ENTITY_BUFF_COUNT =
      seatEntity("entity.buff_count", "count", Frame.NONE, entity(Schema.E_BUFF_COUNT));

  /** The first {@link Schema#BUFF_SLOTS} buffs listed, by rank ({@link BuffSlots}); -1 unused. */
  public static final Field ENTITY_BUFF =
      seatEntity(
          "entity.buff",
          "buff id",
          Frame.NONE,
          entity(Schema.E_BUFFS, Schema.BUFF_SLOTS, 2),
          Schema.BUFF_SLOTS);

  /** Each buff slot's time left, {@link Schema#BUFF_FOREVER} for one that never runs out. */
  public static final Field ENTITY_BUFF_MS =
      seatEntity(
          "entity.buff_ms",
          "ms",
          Frame.NONE,
          entity(Schema.E_BUFFS + 1, Schema.BUFF_SLOTS, 2),
          Schema.BUFF_SLOTS);

  /** A projectile's aim across, -1 for an enemy spell launched from the king until it lands. */
  public static final Field ENTITY_AIM_X =
      seatEntity("entity.aim_x", "game units", Frame.SEAT, entity(Schema.E_AIM_X));

  /** A projectile's aim along, as {@code entity.aim_x}. */
  public static final Field ENTITY_AIM_Y =
      seatEntity("entity.aim_y", "game units", Frame.SEAT, entity(Schema.E_AIM_Y));

  /** An area effect's life left. */
  public static final Field ENTITY_TIME_LEFT_MS =
      seatEntity("entity.time_left_ms", "ms", Frame.NONE, entity(Schema.E_TIME_LEFT_MS));

  /** An area effect's radius at its last update, a length. */
  public static final Field ENTITY_RADIUS =
      seatEntity("entity.radius", "game units", Frame.NONE, entity(Schema.E_RADIUS));

  /** The ticks since a character's champion ability last ran, -1 for none or a trail. */
  public static final Field ENTITY_ABILITY_TICKS =
      seatEntity("entity.ability_ticks", "ticks", Frame.NONE, entity(Schema.E_ABILITY_TICKS));

  // The hidden fields of the header: the truth of the enemy's plays, by the seat whose enemy
  // played.

  /** Each of the seat's enemy's last plays' deck card from its run, shown or not; -1 past. */
  public static final Field ENEMY_PLAYS_CARD_TRUE =
      hiddenEnemyPlays("card", "vocabulary id", Frame.NONE);

  /** Each enemy play's row cast as from its run, shown or not; -1 past. */
  public static final Field ENEMY_PLAYS_CAST_TRUE =
      hiddenEnemyPlays("cast", "vocabulary id", Frame.NONE);

  /** Each enemy play's form from its run, shown or not; -1 past. */
  public static final Field ENEMY_PLAYS_FORM_TRUE =
      hiddenEnemyPlays("form", "enum:form", Frame.NONE);

  /** The point across each enemy play was placed at, shown or not; -1 past. */
  public static final Field ENEMY_PLAYS_X_TRUE = hiddenEnemyPlays("x", "game units", Frame.GAME);

  /** The point along each enemy play was placed at, shown or not; -1 past. */
  public static final Field ENEMY_PLAYS_Y_TRUE = hiddenEnemyPlays("y", "game units", Frame.GAME);

  // The hidden fields of the entity table, by export row, every entity whatever the seats see.

  /** The battle's own id of the row's entity, -1 for a row holding none. */
  public static final Field ENTITY_BATTLE_ID =
      hiddenEntity("entity.battle_id", "battle id", Frame.NONE);

  /**
   * 1 for a row holding an entity: every character and tower, a unit waiting its turn to deploy and
   * one travelling underground included, and every projectile and area effect, up to the capacity.
   */
  public static final Field ENTITY_PRESENT = hiddenEntity("entity.present", "bool", Frame.NONE);

  /** The entity's kind. */
  public static final Field ENTITY_KIND_TRUE =
      hiddenEntity("entity.kind.true", "enum:entity_kind", Frame.NONE);

  /** The game side the entity is of, 0 or 1, or {@link #SIDE_NEITHER} for neither side's. */
  public static final Field ENTITY_SIDE_TRUE =
      hiddenEntity("entity.side.true", "enum:side", Frame.NONE);

  /** The entity's row, a trail's included. */
  public static final Field ENTITY_ROW_TRUE =
      hiddenEntity("entity.row.true", "vocabulary id", Frame.NONE);

  /** The entity's position across, where it truly is (a tunnelling unit's included). */
  public static final Field ENTITY_X_TRUE = hiddenEntity("entity.x.true", "game units", Frame.GAME);

  /** The entity's position along, where it truly is. */
  public static final Field ENTITY_Y_TRUE = hiddenEntity("entity.y.true", "game units", Frame.GAME);

  /** The entity's hit points, whatever bar shows; 0 for an entity without. */
  public static final Field ENTITY_HP_TRUE =
      hiddenEntity("entity.hp.true", "hit points", Frame.NONE);

  /** The entity's maximum hit points; 0 for an entity without. */
  public static final Field ENTITY_MAX_HP_TRUE =
      hiddenEntity("entity.max_hp.true", "hit points", Frame.NONE);

  /** The entity's shield; 0 for an entity without hit points. */
  public static final Field ENTITY_SHIELD_TRUE =
      hiddenEntity("entity.shield.true", "hit points", Frame.NONE);

  /** A character's or tower's state, {@link Schema#NO_STATE} for a projectile or area effect. */
  public static final Field ENTITY_STATE_TRUE =
      hiddenEntity("entity.state.true", "enum:state", Frame.NONE);

  /** The entity's flags as v4 writes them for a row shown in full, a trail's unit's included. */
  public static final Field ENTITY_FLAGS_TRUE =
      hiddenEntity("entity.flags.true", "flags:entity", Frame.NONE);

  /** The entity's level. */
  public static final Field ENTITY_LEVEL_TRUE =
      hiddenEntity("entity.level.true", "level", Frame.NONE);

  /** The battle's own id of the entity's target, -1 for none. */
  public static final Field ENTITY_TARGET_TRUE =
      hiddenEntity("entity.target.true", "battle id", Frame.NONE);

  /** The target's position across, 0 for none. */
  public static final Field ENTITY_TARGET_X_TRUE =
      hiddenEntity("entity.target_x.true", "game units", Frame.GAME);

  /** The target's position along, 0 for none. */
  public static final Field ENTITY_TARGET_Y_TRUE =
      hiddenEntity("entity.target_y.true", "game units", Frame.GAME);

  /** A character's deploy time left. */
  public static final Field ENTITY_DEPLOY_MS_TRUE =
      hiddenEntity("entity.deploy_ms.true", "ms", Frame.NONE);

  /** A character's or tower's attack timer. */
  public static final Field ENTITY_ATTACK_MS_TRUE =
      hiddenEntity("entity.attack_ms.true", "ms", Frame.NONE);

  /** A character's or tower's load countdown. */
  public static final Field ENTITY_LOAD_MS_TRUE =
      hiddenEntity("entity.load_ms.true", "ms", Frame.NONE);

  /** A character's or tower's movement speed after its buffs, in percent of its own. */
  public static final Field ENTITY_SPEED_PERCENT_TRUE =
      hiddenEntity("entity.speed_percent.true", "percent", Frame.NONE);

  /** A character's or tower's attack pace after its buffs, in percent of its own. */
  public static final Field ENTITY_HIT_SPEED_PERCENT_TRUE =
      hiddenEntity("entity.hit_speed_percent.true", "percent", Frame.NONE);

  /** The buffs listed on a character or tower. */
  public static final Field ENTITY_BUFF_COUNT_TRUE =
      hiddenEntity("entity.buff_count.true", "count", Frame.NONE);

  /** The first {@link Schema#BUFF_SLOTS} buffs listed, by rank; -1 unused. */
  public static final Field ENTITY_BUFF_TRUE =
      hiddenEntity("entity.buff.true", "buff id", Frame.NONE, Schema.BUFF_SLOTS);

  /** Each buff slot's time left. */
  public static final Field ENTITY_BUFF_MS_TRUE =
      hiddenEntity("entity.buff_ms.true", "ms", Frame.NONE, Schema.BUFF_SLOTS);

  /** A projectile's aim across, an enemy spell launched from the king's included. */
  public static final Field ENTITY_AIM_X_TRUE =
      hiddenEntity("entity.aim_x.true", "game units", Frame.GAME);

  /** A projectile's aim along. */
  public static final Field ENTITY_AIM_Y_TRUE =
      hiddenEntity("entity.aim_y.true", "game units", Frame.GAME);

  /** An area effect's life left. */
  public static final Field ENTITY_TIME_LEFT_MS_TRUE =
      hiddenEntity("entity.time_left_ms.true", "ms", Frame.NONE);

  /** An area effect's radius at its last update. */
  public static final Field ENTITY_RADIUS_TRUE =
      hiddenEntity("entity.radius.true", "game units", Frame.NONE);

  /** The ticks since a character's champion ability last ran, -1 for none; a trail's included. */
  public static final Field ENTITY_ABILITY_TICKS_TRUE =
      hiddenEntity("entity.ability_ticks.true", "ticks", Frame.NONE);

  /** The rows holding an entity: the first rows of the table. */
  public static final Field ENTITY_COUNT_TRUE = hidden("entity.count.true", "count", Frame.NONE);

  /**
   * The entities past the table's capacity, left out of every entity field. While it is 0 every v4
   * entity row is in the table; past it a seat's v4 rows are rebuilt exactly only when its rows
   * shown in the table reach {@code entity.count}.
   */
  public static final Field ENTITY_EXPORT_OVERFLOW =
      hidden("entity.export_overflow", "count", Frame.NONE);

  // The aliases: the enemy's own seat fields, read at the other seat. They hold no integers.

  /** The enemy's elixir as the enemy's king holds it. */
  public static final Field ENEMY_ELIXIR_TRUE = alias(OWN_ELIXIR);

  /** The elixir the enemy's client holds back. */
  public static final Field ENEMY_ELIXIR_RESERVED_TRUE = alias(OWN_ELIXIR_RESERVED);

  /** The enemy's hand's refill cooldown. */
  public static final Field ENEMY_HAND_REFILL_MS_TRUE = alias(OWN_HAND_REFILL_MS);

  /** The enemy's hand. */
  public static final Field ENEMY_HAND_CARD_TRUE = alias(OWN_HAND_CARD);

  /** The enemy's hand cards' costs. */
  public static final Field ENEMY_HAND_COST_TRUE = alias(OWN_HAND_COST);

  /** The enemy's hand slots with a play waiting. */
  public static final Field ENEMY_HAND_PENDING_TRUE = alias(OWN_HAND_PENDING);

  /** The form each of the enemy's hand cards would be played in now. */
  public static final Field ENEMY_HAND_FORM_TRUE = alias(OWN_HAND_FORM);

  /** The card that refills the enemy's hand next. */
  public static final Field ENEMY_NEXT_CARD_TRUE = alias(OWN_NEXT_CARD);

  /** The enemy's queue. */
  public static final Field ENEMY_QUEUE_TRUE = alias(OWN_QUEUE);

  /** The enemy's deck, by its deck index. */
  public static final Field ENEMY_DECK_CARD_TRUE = alias(OWN_DECK_CARD);

  /** The enemy's deck slot flags. */
  public static final Field ENEMY_DECK_SLOT_TRUE = alias(OWN_DECK_SLOT);

  /** The enemy's evolution counts as its king counts them, by its deck index. */
  public static final Field ENEMY_DECK_EVOLUTION_TRUE = alias(OWN_DECK_EVOLUTION);

  /** The enemy's card levels. */
  public static final Field ENEMY_DECK_LEVEL_TRUE = alias(OWN_DECK_LEVEL);

  /** The enemy's commands given and not run yet. */
  public static final Field ENEMY_PENDING_COUNT_TRUE = alias(OWN_PENDING_COUNT);

  /** The enemy's waiting commands' cards. */
  public static final Field ENEMY_PENDING_CARD_TRUE = alias(OWN_PENDING_CARD);

  /** The point across the enemy's waiting commands carry, in the enemy's frame. */
  public static final Field ENEMY_PENDING_X_TRUE = alias(OWN_PENDING_X);

  /** The point along the enemy's waiting commands carry, in the enemy's frame. */
  public static final Field ENEMY_PENDING_Y_TRUE = alias(OWN_PENDING_Y);

  /** The ticks until the enemy's waiting commands run. */
  public static final Field ENEMY_PENDING_TICKS_TRUE = alias(OWN_PENDING_TICKS);

  /** The champion rows the enemy king's champion slots follow. */
  public static final Field ENEMY_ABILITY_CHAMPION_TRUE = alias(OWN_ABILITY_CHAMPION);

  /** The unit each enemy champion slot names, by the enemy seat's ids ({@code entity.id}). */
  public static final Field ENEMY_ABILITY_UNIT_TRUE = alias(OWN_ABILITY_UNIT);

  /** The enemy's champion slots' button states. */
  public static final Field ENEMY_ABILITY_STATE_TRUE = alias(OWN_ABILITY_STATE);

  /** The enemy's champion slots' cooldowns left. */
  public static final Field ENEMY_ABILITY_COOLDOWN_MS_TRUE = alias(OWN_ABILITY_COOLDOWN_MS);

  /** The enemy's champion slots' charges left. */
  public static final Field ENEMY_ABILITY_CHARGES_TRUE = alias(OWN_ABILITY_CHARGES);

  /** The ticks until the enemy's ability commands run. */
  public static final Field ENEMY_ABILITY_COMMAND_TICKS_TRUE = alias(OWN_ABILITY_COMMAND_TICKS);

  /** Why the enemy's action of the last decision was refused. */
  public static final Field ENEMY_LAST_REFUSAL_TRUE = alias(OWN_LAST_REFUSAL);

  /** The enemy's play refused at its run in the last decision. */
  public static final Field ENEMY_LAST_RUN_REFUSAL_TRUE = alias(OWN_LAST_RUN_REFUSAL);

  /** The enemy's ability command refused at its run in the last decision. */
  public static final Field ENEMY_LAST_ABILITY_RUN_REFUSAL_TRUE =
      alias(OWN_LAST_ABILITY_RUN_REFUSAL);

  /** The enemy's actions refused as illegal in the episode so far. */
  public static final Field ENEMY_ILLEGAL_TRUE = alias(OWN_ILLEGAL);

  // The end of the registration.

  /** The integers of one environment's block: every field but the aliases, end to end. */
  public static final int INTS = next;

  private static final List<Field> FIELDS = List.copyOf(ALL);

  /** Every field, in the order of the block, the aliases last. */
  public static List<Field> fields() {
    return FIELDS;
  }

  /** The field of a name, or null for none. */
  public static Field field(String name) {
    return BY_NAME.get(name);
  }

  /**
   * Where a field's values sit in a seat's schema v4 observation, or null for a field v4 does not
   * hold (a hidden field, an alias, {@code entity.visible}). Every v4 header field but the reserved
   * ones and every v4 entity row field is the source of exactly one seat or public field.
   */
  public static V4Source v4Source(String name) {
    return V4.get(name);
  }

  /**
   * The labels of every code and bit the fields' units name, by the name after {@code enum:} or
   * {@code flags:}: an enum's labels keyed by code value, a flags' labels keyed by bit index (bit k
   * is the value {@code 1 << k}), each giving only the codes or bits defined, in ascending order.
   * -1, a field's unknown or none, is never listed. The labels come from the codes' own constants
   * and lists.
   */
  public static Map<String, SortedMap<Integer, String>> enums() {
    Map<String, SortedMap<Integer, String>> enums = new TreeMap<>();
    enums.put(
        "ability_refusal",
        byValue(
            AbilityCommand.OK, "none",
            AbilityCommand.NOT_CHAMPION, "not_champion",
            AbilityCommand.CLONE, "clone",
            AbilityCommand.KING_GONE, "king_gone",
            AbilityCommand.COOLDOWN, "cooldown",
            AbilityCommand.NO_CHAMPION, "no_champion",
            AbilityCommand.PENDING, "pending",
            AbilityCommand.FROZEN, "frozen",
            AbilityCommand.DISABLED, "disabled",
            AbilityCommand.NO_CHARGES, "no_charges",
            AbilityCommand.ELIXIR, "elixir"));
    enums.put("ability_state", byIndex(ChampionController.stateNames()));
    enums.put(
        "entity_kind",
        byValue(
            Schema.KIND_CHARACTER, "character",
            Schema.KIND_TOWER, "tower",
            Schema.KIND_PROJECTILE, "projectile",
            Schema.KIND_AREA, "area"));
    enums.put(
        "form", byValue(0, "plain", Schema.FORM_EVOLVED, "evolved", Schema.FORM_HERO, "hero"));
    enums.put("play_kind", byIndex(Schema.playKindNames()));
    enums.put("refusal", byIndex(Schema.refusalNames()));
    // As the packer numbers an entity's side from the seat's.
    enums.put("relative_side", byValue(0, "own", 1, "enemy", 2, "neither"));
    enums.put(
        "result",
        byValue(
            Schema.RUNNING,
            "running",
            Schema.WON,
            "won",
            Schema.LOST,
            "lost",
            Schema.DRAW,
            "draw"));
    enums.put(
        "run_refusal",
        byValue(
            0,
            "none",
            LadderMatch.OVER,
            "over",
            LadderMatch.KING_DEAD,
            "king_dead",
            LadderMatch.NOT_IN_HAND,
            "not_in_hand",
            LadderMatch.START_COOLDOWN,
            "start_cooldown",
            LadderMatch.NOT_ENOUGH_ELIXIR,
            "not_enough_elixir",
            Schema.RUN_NOT_PLACED,
            "not_placed"));
    enums.put(
        "section_type",
        byValue(
            BattleTimeline.NORMAL, "normal",
            BattleTimeline.OVERTIME, "overtime",
            BattleTimeline.BONUS_TIME, "bonus_time"));
    enums.put("side", byValue(0, "side_0", 1, "side_1", SIDE_NEITHER, "neither"));
    enums.put("state", byIndex(Schema.stateNames()));
    // The flags, by bit.
    enums.put("entity", byIndex(Schema.flagNames()));
    enums.put("play", byIndex(Schema.playFlagNames()));
    enums.put("slot", byBit(Schema.SLOT_EVOLUTION, "evolution", Schema.SLOT_HERO, "hero"));
    return Collections.unmodifiableMap(enums);
  }

  /** Labels keyed by value from value and label pairs. */
  private static SortedMap<Integer, String> byValue(Object... pairs) {
    SortedMap<Integer, String> labels = new TreeMap<>();
    for (int k = 0; k < pairs.length; k += 2) {
      int value = (Integer) pairs[k];
      if (labels.put(value, (String) pairs[k + 1]) != null) {
        throw new IllegalStateException("code " + value + " labelled twice");
      }
    }
    return Collections.unmodifiableSortedMap(labels);
  }

  /** Labels keyed by their index in a names list, its gaps (nulls) left out. */
  private static SortedMap<Integer, String> byIndex(List<String> names) {
    SortedMap<Integer, String> labels = new TreeMap<>();
    for (int k = 0; k < names.size(); k++) {
      if (names.get(k) != null) {
        labels.put(k, names.get(k));
      }
    }
    return Collections.unmodifiableSortedMap(labels);
  }

  /** Labels keyed by bit index from single-bit flag and label pairs. */
  private static SortedMap<Integer, String> byBit(Object... pairs) {
    Object[] byValue = pairs.clone();
    for (int k = 0; k < pairs.length; k += 2) {
      int flag = (Integer) pairs[k];
      if (Integer.bitCount(flag) != 1) {
        throw new IllegalStateException("flag " + flag + " is not one bit");
      }
      byValue[k] = Integer.numberOfTrailingZeros(flag);
    }
    return byValue(byValue);
  }

  /**
   * The registry as the vector's metadata publishes it ({@code fields}): the version, the block's
   * integers, the entity table's capacity, the labels of the codes and bits ({@code enums}, {@link
   * #enums()}: per name an object from the code value, or the bit index of a flags' name, written
   * as a string, to its label, the defined ones only in ascending order) and every field's name,
   * offset, shape, tag, units, frame, the field it aliases (null for none) and its v4 source
   * ({@code v4}: the region, {@code header} or {@code entity}, and the v4 index of each element
   * past the seat and row axes, {@link #v4Source}; null for none).
   */
  public static ObjectNode toNode() {
    JsonNodeFactory json = JsonNodeFactory.instance;
    ObjectNode node = json.objectNode();
    node.put("version", VERSION);
    node.put("ints", INTS);
    node.put("entity_capacity", ENTITY_CAPACITY);
    ObjectNode enums = node.putObject("enums");
    enums()
        .forEach(
            (name, labels) -> {
              ObjectNode codes = enums.putObject(name);
              labels.forEach((code, label) -> codes.put(Integer.toString(code), label));
            });
    ArrayNode list = node.putArray("fields");
    for (Field field : FIELDS) {
      ObjectNode item = list.addObject();
      item.put("name", field.name());
      item.put("offset", field.offset());
      ArrayNode shape = item.putArray("shape");
      field.shape().forEach(shape::add);
      item.put("tag", field.tag().json());
      item.put("units", field.units());
      item.put("frame", field.frame().json());
      item.put("alias_of", field.aliasOf());
      V4Source source = V4.get(field.name());
      if (source == null) {
        item.putNull("v4");
      } else {
        ObjectNode v4 = item.putObject("v4");
        v4.put("region", source.region());
        ArrayNode indices = v4.putArray("indices");
        source.indices().forEach(indices::add);
      }
    }
    return node;
  }

  // The registration's helpers.

  private static V4Source header(int index) {
    return new V4Source(false, List.of(index));
  }

  /** The v4 header indices of {@code count} values from {@code first}, {@code stride} apart. */
  private static V4Source header(int first, int count, int stride) {
    return new V4Source(false, strided(first, count, stride));
  }

  private static V4Source entity(int index) {
    return new V4Source(true, List.of(index));
  }

  private static V4Source entity(int first, int count, int stride) {
    return new V4Source(true, strided(first, count, stride));
  }

  private static List<Integer> strided(int first, int count, int stride) {
    List<Integer> indices = new ArrayList<>();
    for (int k = 0; k < count; k++) {
      indices.add(first + k * stride);
    }
    return List.copyOf(indices);
  }

  private static Field pub(String name, String units, V4Source v4) {
    return add(name, List.of(), Tag.PUBLIC, units, Frame.NONE, v4);
  }

  private static Field seat(String name, String units, V4Source v4, int... axes) {
    return seat(name, units, Frame.NONE, v4, axes);
  }

  private static Field seat(String name, String units, Frame frame, V4Source v4, int... axes) {
    return add(name, shape(SEATS, axes), Tag.SEAT, units, frame, v4);
  }

  /** A seat field of the enemy play rows, from the v4 rows' field {@code at}. */
  private static Field enemyPlays(String field, String units, Frame frame, int at) {
    return seat(
        "enemy.plays." + field,
        units,
        frame,
        header(Schema.H_ENEMY_PLAYS + at, Schema.ENEMY_PLAY_ROWS, Schema.ENEMY_PLAY_INTS),
        Schema.ENEMY_PLAY_ROWS);
  }

  /** The truth of a seat field of the enemy play rows, by the seat whose enemy played. */
  private static Field hiddenEnemyPlays(String field, String units, Frame frame) {
    return add(
        "enemy.plays." + field + ".true",
        shape(SEATS, Schema.ENEMY_PLAY_ROWS),
        Tag.HIDDEN,
        units,
        frame,
        null);
  }

  private static Field seatEntity(
      String name, String units, Frame frame, V4Source v4, int... axes) {
    int[] rest = new int[axes.length + 1];
    rest[0] = ENTITY_CAPACITY;
    System.arraycopy(axes, 0, rest, 1, axes.length);
    return add(name, shape(SEATS, rest), Tag.SEAT, units, frame, v4);
  }

  private static Field hidden(String name, String units, Frame frame) {
    return add(name, List.of(), Tag.HIDDEN, units, frame, null);
  }

  private static Field hiddenEntity(String name, String units, Frame frame, int... axes) {
    return add(name, shape(ENTITY_CAPACITY, axes), Tag.HIDDEN, units, frame, null);
  }

  /**
   * The alias of a seat field read at the other seat: {@code own.x} read so is {@code
   * enemy.x.true}.
   */
  private static Field alias(Field of) {
    if (of.tag() != Tag.SEAT || !of.name().startsWith("own.")) {
      throw new IllegalStateException("no alias of " + of.name());
    }
    String name = "enemy." + of.name().substring("own.".length()) + ".true";
    Frame frame = of.frame() == Frame.SEAT ? Frame.ENEMY_SEAT : of.frame();
    Field field =
        new Field(name, of.offset(), of.shape(), Tag.HIDDEN, of.units(), frame, of.name());
    register(field);
    return field;
  }

  private static List<Integer> shape(int first, int... rest) {
    List<Integer> shape = new ArrayList<>();
    shape.add(first);
    for (int n : rest) {
      shape.add(n);
    }
    return List.copyOf(shape);
  }

  private static Field add(
      String name, List<Integer> shape, Tag tag, String units, Frame frame, V4Source v4) {
    Field field = new Field(name, next, shape, tag, units, frame, null);
    next += field.size();
    register(field);
    if (v4 != null) {
      V4.put(name, v4);
    }
    return field;
  }

  private static void register(Field field) {
    if (BY_NAME.putIfAbsent(field.name(), field) != null) {
      throw new IllegalStateException("field " + field.name() + " named twice");
    }
    ALL.add(field);
  }
}
