/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import static org.crforge.core.util.ValidationUtils.checkArgument;
import static org.crforge.core.util.ValidationUtils.checkState;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleMode;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.KingElixir;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * A Ladder match between two players: the battle's clock, each king's elixir and hand, the gates a
 * card play passes before it is placed, and how the match ends.
 *
 * <p>The match is set up once the towers stand, side 0 first: each king starts with the timeline's
 * starting elixir, and its deck is shuffled into its battle order with one draw of the battle's
 * random source. Each step the update advances the timeline to the battle tick before the entities
 * are ticked; in their post-hook pass each king refills its hand and then regenerates its elixir,
 * at the rate and cooldown the timeline gives for that tick.
 *
 * <p>A card play first passes the match's gates, in order: the match must not be decided (code 4),
 * the king must be alive (code 8), the card must be in one of the hand's four slots (code 9), and
 * the whole elixir must cover its cost (code 0xd). A refused play changes nothing. One that places
 * or casts takes its cost and moves the card to the back of the queue before its units are created
 * or its spell cast.
 *
 * <p>A deck marks its evolution and hero slots, one flag each per deck card. A play of a deck card
 * carries an item built from its side's count for the card: evolved once the count has reached the
 * card's evolved row's DarkElixirCost, else the hero form for a hero slot's card, else neither. The
 * play is gated on, and spends, the cost of the row it is cast as - the card's first in that form -
 * and that row is placed and cast as any troop card. The hand cycles the deck card. A deck's slots
 * are where the deck puts them; the battle only reads the flags.
 *
 * <p>A Mirror plays its side's last card again. Its play carries an item the player's client builds
 * from the copy of the last card the king keeps: that card, one level above the Mirror's, for the
 * Mirror's cost plus the card's, never more than the most elixir there can be. After an evolved
 * play the card repeated is the deck card itself, after a hero play its hero row. The gates read
 * the Mirror's own hand slot and the item's cost. The card is then placed or cast as itself, at the
 * item's level; the play takes the item's cost, starts the card's production stop, and moves the
 * Mirror to the back of the queue. The last card stays the one the Mirror repeated. A Mirror with
 * nothing to repeat finds no position and is refused with 0x17, nothing taken.
 *
 * <p>A variant card, the Merge Maiden, is played as one of its options. The player's client picks
 * the option from the king's elixir as it gives the play, 20 ticks before it runs: the first whose
 * trigger the elixir reaches, else the last - the mounted maiden from 6 elixir, else the maiden on
 * foot. The play carries that option and its cost. The gates read the variant card's own hand slot
 * and that cost; the option's card is then placed and cast as any troop card, and the play takes
 * the cost, starts the option's production stop and moves the variant card to the back of the
 * queue.
 *
 * <p>The match is decided when a king has fallen, when overtime sees a crown, or when the time is
 * up. It is asked at the head of each step and after the entity tick, and the first time it holds
 * the match ends: the timeline freezes, the winner is the side with more crowns, and the end timer
 * starts at 1. The battle goes on - units fight, every play is refused - and each update adds 50 to
 * the timer, until the update that takes it to the end screen's delay ticks no entities and the
 * battle stops: from the next step, nothing runs, not even the tick counter. From the update after
 * a king's fall, a circle grows from it to the arena's length over half that delay and kills every
 * living entity of its side it reaches. From the end, the battle holds every attack timer at zero
 * and refuses every ordinary hit, so nothing fights on; only the circle kills.
 *
 * <p>A Ladder match allows no draws: when the time is up with equal crowns, the tiebreaker replaces
 * the step. Each of its steps adds 50 to its time, and from its first the battle refuses every
 * ordinary hit. Its first 30 steps clear the field - every projectile, area effect and character
 * without hit points is removed at once, every other unit and building is killed at the update's
 * damage drain, the crown towers left standing - and run the update, with its entity tick, only on
 * a step whose clearing removed or killed something; then the battle waits, running only the
 * commands, every play refused, until the step that begins at 3250 ms. From then each step drains
 * every tower of both sides by one step the lowest tower's hit points pick: 1 below 21, 10 below
 * 200, 20 below 500, 40 below 1000, else 50. When a tower reaches 0, or the two sides' lowest
 * towers are equal, the holder is cleaned up, and the next step ends the match by crowns: equal
 * lowest towers end it a draw.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the hands, queues and elixir every reference battle observes, among"
            + " them timeline_spells_through_rates: the setup in side order with the shuffle's"
            + " draw, the update's advance before the entity tick, the kings' refill and"
            + " regeneration in their post-hook, the gate 9 (held by refused_not_in_hand_s1) and"
            + " the gate 13 (held by golden-gaps-v1/mirror_after_spell) and the spend and cycle"
            + " before the placement's units; by card_Pekka_until_stop: the end at a king's fall,"
            + " the winner, the end timer and the entity ticks it allows, the fallen king's"
            + " circle and the stop, the attack timers held and the hits refused from the end; by"
            + " timeline_tiebreak_tower_hp, timeline_tiebreak_equal_arrows and"
            + " timeline_overtime_crown: the tiebreaker's steps, its idle window, the drain and"
            + " its steps, its end by a fallen tower and by equal towers, the winner and the"
            + " draw; by a battle recorded for it, with two volleys, a Fireball, a Poison and a"
            + " Bomb Tower's bomb listed as the tiebreaker begins: the clearing's removal at once"
            + " of a projectile, of an area effect and of a character without hit points, each"
            + " leaving the object after it for the next step; by a battle recorded for it, with"
            + " towers attacking, a Barbarian Hut, a Balloon, a Witch's Skeletons and a walking"
            + " Knight alive as the tiebreaker begins: the clearing's kill queued for the damage"
            + " drain of the update it runs, the killed unit's step in that update, the towers'"
            + " attacks on it and the place of its death spawns; by cg_elixir_collector_played and"
            + " card_ElixirGolem: the kings' elixir a collector and a death pay into; by"
            + " golden-gaps-v1/mirror_after_troop and"
            + " mirror_after_spell: the Mirror's item, its gates, its spend and its cycle, the"
            + " card repeated one level up, and the last card kept; by knight_evolved_third_play"
            + " and the hero reference battles (hero_giant, deck_hero_and_champion): the slot"
            + " flags, the count each play of an evolution slot's card moves, the evolved and the"
            + " hero item, their row cast at its cost, and the hero form in the deck pass; by"
            + " card_item_merge_maiden_mounted and card_item_merge_maiden_on_foot: a variant"
            + " card's option picked from the elixir, its cost gated and spent, and the variant"
            + " card cycled. Not held by a recorded battle: the Mirror of an evolved play. Held"
            + " by LadderMatchTest alone: the gate 4, the timeline's freeze, the clearing's kills"
            + " and the update it runs; by BattleMirrorTest alone: a Mirror after a Mirror and a"
            + " Mirror with nothing to repeat; by SpellVariantTest and BattleMergeMaidenTest"
            + " alone: the pick at its boundary, the projection that moves no shipped pick, a"
            + " variant play the elixir does not cover, and a Mirror after a Merge Maiden, which"
            + " repeats the option it was played as. Not held apart: the last card and the"
            + " copy a Mirror reads, which differ only in a tick a play of its side ran, a play"
            + " the Mirror refuses. Not modelled, and refused: a character of the neutral side,"
            + " which the clearing only counts, and the stand-in owner of actions, which stands"
            + " for an object the battle does not have; a Mirror of a champion; a Mirror of a"
            + " variant card as the card itself, and of an evolved play as its evolved row, which"
            + " no row sets; slot flags on a spell, a building, the Mirror or a variant card, and"
            + " two copies of an evolution slot's card in a deck, which no reference holds; and a"
            + " play of an evolution slot's card while another play of it is due, whose item the"
            + " client builds from the count before that play runs. Unreachable: an item's cost"
            + " held to the most elixir, which no shipped card reaches. Not carried: the flag set"
            + " when a fallen king's two towers stand whole, whose readers are not established;"
            + " the marks an evolved or hero play leaves on its objects and its king, which no"
            + " battle code reads.")
public final class LadderMatch implements BattleMode {

  /** The game mode row the match is played under. */
  public static final String GAME_MODE = "Ladder";

  /** The bound of the battle-source draw that seeds a deck's shuffle. */
  private static final int SHUFFLE_BOUND = 0x0fffffff;

  /** A refused play: the match is decided. */
  public static final int OVER = 4;

  /** A refused play: the king is dead. */
  public static final int KING_DEAD = 8;

  /** A refused play: the card is not in one of the hand's four slots. */
  public static final int NOT_IN_HAND = 9;

  /** A refused play: the whole elixir does not cover the card's cost. */
  public static final int NOT_ENOUGH_ELIXIR = 0xd;

  /** The global that sets how many levels above its own a Mirror plays its card. */
  private static final String MIRROR_LEVEL_OFFSET = "MIRROR_LEVEL_OFFSET";

  /** The width of a card item's level field, which a Mirror's level must fit in. */
  private static final int LEVEL_FIELD_BITS = 7;

  /** The side of an object that belongs to neither player. */
  private static final int NEUTRAL_SIDE = 100;

  /** The tiebreaker clears the field on a step whose time before it is at most this. */
  private static final int CLEARING_UNTIL_MS = 1450;

  /** The tiebreaker drains the towers from the step whose time before it is this. */
  private static final int DRAIN_FROM_MS = 3250;

  private final BattleWorld world;

  @Getter private final Timeline timeline;

  private final int maxMana;

  /** How many levels above its own a Mirror plays its card. */
  private final int mirrorLevelOffset;

  private final List<MatchSide> sides = new ArrayList<>();

  /** The battle-source draw each side's shuffle was seeded with, before the player's word. */
  private final int[] shuffleDraws = new int[2];

  /** How long the battle goes on after its end: the end screen's delay, in milliseconds. */
  private final int endDelayMs;

  /** The arena's length in game units, which a fallen king's circle grows to. */
  private final int arenaLength;

  /** Whether the match has ended. */
  private boolean ended;

  /** The end timer: 0 until the end, then 1, and 50 more each update. */
  private int endTimerMs;

  /** The side that won, or -1 for none. */
  private int winner;

  /** How long a fallen king's circle has grown, in milliseconds. */
  private int circleMs;

  /** Whether the last step ticked the entities. */
  private boolean lastTicked;

  /** The tiebreaker's time: 0 until it begins, then 50 more each of its steps. */
  private int tiebreakMs;

  /** Whether the tiebreaker has decided the match, which the next step ends by crowns. */
  private boolean tiebreakDone;

  /**
   * Sets a match up on a battle whose towers stand.
   *
   * @param world the battle's world, whose random source the shuffles draw from
   * @param records the battle's records
   * @param decks each side's deck, by card row name
   * @param playerWords each side's word, added to its shuffle's draw
   */
  public LadderMatch(
      BattleWorld world, BattleRecords records, List<List<String>> decks, int[] playerWords) {
    this(
        world,
        records,
        decks,
        playerWords,
        List.of(new int[decks.get(0).size()], new int[decks.get(1).size()]));
  }

  /**
   * Sets a match up on a battle whose towers stand, with each deck's evolution and hero slots.
   *
   * @param world the battle's world, whose random source the shuffles draw from
   * @param records the battle's records
   * @param decks each side's deck, by card row name
   * @param playerWords each side's word, added to its shuffle's draw
   * @param slotFlags each side's slot flags, by deck index: {@link MatchSide#EVOLUTION_SLOT},
   *     {@link MatchSide#HERO_SLOT}, both or neither
   */
  public LadderMatch(
      BattleWorld world,
      BattleRecords records,
      List<List<String>> decks,
      int[] playerWords,
      List<int[]> slotFlags) {
    checkArgument(decks.size() == 2 && playerWords.length == 2, () -> "a match has two players");
    checkArgument(slotFlags.size() == 2, () -> "a match has two decks of slot flags");
    this.world = world;
    this.timeline = new Timeline(records.gameModeTimeline(GAME_MODE));
    this.maxMana = records.globalNumber("MAX_MANA");
    this.mirrorLevelOffset = records.globalNumber(MIRROR_LEVEL_OFFSET);
    this.endDelayMs = records.endScreenDelayMs();
    this.arenaLength = world.getTileMap().height() * TileMap.CELL_UNITS;
    for (int side = 0; side < 2; side++) {
      List<MatchCard> deck = new ArrayList<>();
      for (String name : decks.get(side)) {
        deck.add(records.matchCard(name));
      }
      checkSlots(side, deck, slotFlags.get(side), records);
      MatchSide matchSide =
          new MatchSide(deck, timeline.row().startingElixir(), slotFlags.get(side));
      int draw = world.getRandom().next(SHUFFLE_BOUND);
      shuffleDraws[side] = draw;
      matchSide.getHand().deal(DeckShuffle.order(deck, draw + playerWords[side]));
      sides.add(matchSide);
    }
    // The kings' elixir, which a collector's payout and a unit's death pay into.
    world.setKingElixir(
        new KingElixir() {
          @Override
          public int wholeElixir(int side) {
            return sides.get(side).wholeElixir();
          }

          @Override
          public int elixir(int side) {
            return sides.get(side).getElixir();
          }

          @Override
          public void spend(int side, int amount) {
            sides.get(side).spend(amount);
          }

          @Override
          public void add(int side, int amount) {
            sides.get(side).add(amount, maxMana);
          }

          @Override
          public int maxMana() {
            return maxMana;
          }
        });
  }

  /**
   * Refuses the slot flags no reference holds: flags on the Mirror or a variant card, a hero slot
   * on a spell or building card with no hero form, a hero slot on a card that is not a troop, spell
   * or building card, an evolution slot on a card that is not a troop, spell or building card, and
   * two copies of an evolution slot's card in a deck, whose counts the play's deck index would mix.
   *
   * <p>A spell or building card in the evolution slot is counted and cast as a troop card is: the
   * item, the count and the row cast do not ask which kind of card it is. The evolved row it is
   * cast as is refused as it is cast when it sets something its kind's cast does not model.
   *
   * <p>A spell card in the hero slot is played as a troop card there is: its item names the hero
   * form, and the play casts the card's hero row, as a spell is cast; the deck's champion slots
   * follow the champion the hero row links, which the cast's projectile leaves behind.
   *
   * <p>A building card in the hero slot is played as a troop card there is too: the item's form
   * field is the hero form for any card with the hero bit, whatever its table, and the play places
   * the card's hero row, as the Tombstone's places its listed characters - a dummy, the building
   * and the passive monster that holds the ability - in the list's order at the placed point.
   */
  private static void checkSlots(
      int side, List<MatchCard> deck, int[] flags, BattleRecords records) {
    checkArgument(
        flags.length == deck.size(),
        () -> "side " + side + "'s slot flags are not one for each card");
    for (int index = 0; index < deck.size(); index++) {
      int flag = flags[index];
      MatchCard card = deck.get(index);
      checkArgument(
          flag >= 0 && flag <= (MatchSide.EVOLUTION_SLOT | MatchSide.HERO_SLOT),
          () -> card.name() + " has slot flags " + flag);
      if (flag == 0) {
        continue;
      }
      if (card.mirror() || card.variant() != null) {
        throw new UnsupportedOperationException(
            card.name()
                + " in an evolution or hero slot, which is the Mirror or a variant card, is held"
                + " by no reference");
      }
      if (!records.troopCard(card.name())) {
        if ((flag & MatchSide.HERO_SLOT) != 0 && !records.spellOrBuildingCard(card.name())) {
          throw new UnsupportedOperationException(
              card.name()
                  + " in a hero slot, which is neither a troop, a spell nor a building card, is"
                  + " held by no reference");
        }
        if ((flag & MatchSide.HERO_SLOT) != 0
            && card.evolvedSpells().stream().noneMatch(f -> f.form() == MatchCard.HERO_FORM)) {
          // The play would cast the base row with the hero bit set, which no reference holds.
          throw new UnsupportedOperationException(
              card.name()
                  + " in a hero slot, a "
                  + (records.spellCard(card.name()) ? "spell" : "building")
                  + " card with no hero form, is held by no reference");
        }
        if (!records.spellOrBuildingCard(card.name())) {
          throw new UnsupportedOperationException(
              card.name()
                  + " in an evolution slot, which is not a troop, spell or building card, is held"
                  + " by no reference");
        }
      }
      if ((flag & MatchSide.EVOLUTION_SLOT) != 0
          && deck.stream().filter(c -> c.name().equals(card.name())).count() > 1) {
        throw new UnsupportedOperationException(
            "two copies of "
                + card.name()
                + ", an evolution slot's card, in side "
                + side
                + "'s deck, which no reference holds");
      }
    }
  }

  /** How many players the match was set up between. */
  public int playerCount() {
    return sides.size();
  }

  /**
   * One side of the match.
   *
   * @param side 0 or 1
   */
  public MatchSide side(int side) {
    return sides.get(side);
  }

  /**
   * The battle-source draw a side's shuffle was seeded with.
   *
   * @param side 0 or 1
   */
  public int shuffleDraw(int side) {
    return shuffleDraws[side];
  }

  /**
   * Whether the battle has stopped: the match is decided and its end timer has reached the end
   * screen's delay. A stopped battle runs no step at all.
   */
  @Override
  public boolean isOver() {
    return decided() && endTimerMs >= endDelayMs;
  }

  /**
   * The head of a step: a match decided since the last step is ended here, unless equal crowns take
   * it to the tiebreaker.
   */
  @Override
  public void beforeCommands(Battle battle) {
    if (decided() && !tiebreakerArmed()) {
      end();
    }
  }

  /** A decided match with equal crowns runs the tiebreaker's step in place of the battle's. */
  @Override
  public boolean replacesStep(Battle battle) {
    if (!decided() || !tiebreakerArmed()) {
      return false;
    }
    tiebreakStep(battle);
    return true;
  }

  /**
   * One step of the tiebreaker: 50 more on its time, which holds every ordinary hit; then, by its
   * time before the step, the clearing and an update when it killed something, or nothing, or the
   * drain; the commands last.
   */
  private void tiebreakStep(Battle battle) {
    int before = tiebreakMs;
    tiebreakMs += 50;
    world.setHitsHeld(true);
    lastTicked = false;
    if (before <= CLEARING_UNTIL_MS) {
      if (clearField() >= 1) {
        afterUpdate(battle.runUpdate());
      }
      battle.runCommands();
      return;
    }
    if (before < DRAIN_FROM_MS) {
      battle.runCommands();
      return;
    }
    drain();
    if (lowestTower() < 1 || lowestTied()) {
      // A dead princess tower leaves the holder here, and its crown counts from now.
      world.getHolder().cleanup();
      tiebreakDone = true;
    }
    battle.runCommands();
  }

  /**
   * The tiebreaker's clearing: one walk over the holder's live list in id order, from the first
   * object of the area-effect kind's band. A projectile is removed from the holder at once; an area
   * effect has its life ended and is removed at once; a character without hit points is removed at
   * once; a crown tower is left standing; every other character is resumed at once and its kill,
   * with no attacker, queued for the damage drain of the update the clearing runs: it lives through
   * that update's passes, dies at its drain and leaves at its cleanup. A removal moves every object
   * after it up one place while the walk still steps on, so the object right after a removed one is
   * not reached in this step and waits for the next.
   *
   * <p>A character of the neutral side, which the clearing only counts, and the stand-in owner of
   * actions, which stands for an object the battle does not have, are refused.
   *
   * @return how many it removed or killed
   */
  private int clearField() {
    List<BattleEntity> live = world.getHolder().entities();
    int count = 0;
    int index = 0;
    while (index < live.size()
        && live.get(index).getId() < BattleEntity.KIND_AREA_EFFECT * BattleEntity.IDS_PER_KIND) {
      index++;
    }
    for (; index < live.size(); index++) {
      BattleEntity entity = live.get(index);
      if (entity instanceof ProjectileEntity || entity instanceof AreaEffectEntity) {
        world.clearingRemoval(entity);
        count++;
        continue;
      }
      if (entity instanceof TowerEntity) {
        continue;
      }
      if (!(entity instanceof CharacterEntity character)) {
        throw new UnsupportedOperationException(
            "the tiebreaker's clearing of "
                + entity.getClass().getSimpleName()
                + " "
                + entity.getId()
                + ", which stands in for an object the battle does not have, is not modelled");
      }
      if (character.getHitPoints() == null) {
        world.clearingRemoval(character);
        count++;
        continue;
      }
      if (character.side() == NEUTRAL_SIDE) {
        throw new UnsupportedOperationException(
            "the tiebreaker's clearing of the neutral "
                + character.name()
                + ", which it only counts, is not modelled");
      }
      world.clearingKill(character);
      count++;
    }
    return count;
  }

  /**
   * The tiebreaker's drain: every princess tower of both sides and both kings, side 0 first, lose
   * the step the lowest tower's hit points pick.
   */
  private void drain() {
    int step = drainStep(lowestTower());
    for (int side = 0; side < 2; side++) {
      for (TowerEntity tower : princessTowers(side)) {
        world.drain(tower, step);
      }
      world.drain(king(side), step);
    }
  }

  /** The drain's step for the lowest tower's hit points. */
  static int drainStep(int lowest) {
    if (lowest < 21) {
      return 1;
    }
    if (lowest < 200) {
      return 10;
    }
    if (lowest < 500) {
      return 20;
    }
    return lowest < 1000 ? 40 : 50;
  }

  /** The lowest hit points of any tower of both sides. */
  private int lowestTower() {
    return Math.min(lowestTower(0), lowestTower(1));
  }

  /** The lowest hit points of a side's towers still in the holder, its king included. */
  private int lowestTower(int side) {
    int lowest = king(side).getHitPoints().getHitPoints();
    for (TowerEntity tower : princessTowers(side)) {
      lowest = Math.min(lowest, tower.getHitPoints().getHitPoints());
    }
    return lowest;
  }

  /** Whether both sides' lowest towers are equal. */
  private boolean lowestTied() {
    return lowestTower(0) == lowestTower(1);
  }

  /** A side's princess towers still in the holder, in placement order. */
  private List<TowerEntity> princessTowers(int side) {
    List<TowerEntity> towers = new ArrayList<>();
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof TowerEntity tower
          && tower.getData().summonerTower()
          && tower.side() == side) {
        towers.add(tower);
      }
    }
    return towers;
  }

  /**
   * The update: the crowns-equal byte, the timeline advanced to the battle tick, the circle of a
   * fallen king, and once the match has ended its end timer, 50 more each update; the update that
   * takes it to the end screen's delay ticks no entities.
   */
  @Override
  public boolean update(Battle battle) {
    timeline.setCrownsEqual(crowns(0) == crowns(1));
    timeline.advance(battle.getTick());
    for (int side = 0; side < 2; side++) {
      TowerEntity king = king(side);
      if (!HitPoints.alive(king.getHitPoints())) {
        circle(king);
      }
    }
    if (endTimerMs >= 1) {
      endTimerMs += 50;
      if (decided() && endTimerMs >= endDelayMs) {
        return false;
      }
    }
    return true;
  }

  /** The tail of a step: the crowns-equal byte again, and a match the tick decided is ended. */
  @Override
  public void afterTick(Battle battle, boolean ticked) {
    afterUpdate(ticked);
    if (decided() && !tiebreakerArmed()) {
      end();
    }
  }

  /** The tail of the update: the crowns-equal byte again, after an entity tick. */
  private void afterUpdate(boolean ticked) {
    lastTicked = ticked;
    if (ticked) {
      timeline.setCrownsEqual(crowns(0) == crowns(1));
    }
  }

  /**
   * The end handler, once: the match ended, which holds the attack timers and refuses the hits, its
   * timeline frozen, the end timer started at 1, and the winner the side with more crowns, or none
   * for equal crowns.
   */
  private void end() {
    if (ended) {
      return;
    }
    ended = true;
    world.setMatchEnded(true);
    timeline.freeze();
    endTimerMs = 1;
    int taken0 = crowns(0);
    int taken1 = crowns(1);
    winner = taken0 < taken1 ? 1 : taken0 > taken1 ? 0 : -1;
  }

  /**
   * Whether a decided match goes to the tiebreaker rather than its end: the crowns are equal - the
   * mode allows no draws - the tiebreaker has not decided it, and the match has not ended.
   */
  private boolean tiebreakerArmed() {
    return crowns(0) == crowns(1) && !tiebreakDone && !ended;
  }

  /**
   * The circle from a fallen king: it grows to the arena's length over half the end screen's delay,
   * 50 ms an update from the update after the fall, and kills every living entity of the king's
   * side with hit points it reaches, in the holder's order, with no attacker.
   */
  private void circle(TowerEntity king) {
    circleMs += 50;
    int duration = endDelayMs / 2;
    int radius = arenaLength;
    if (duration >= 1) {
      radius = arenaLength * Math.min(circleMs, duration) / duration;
    }
    int x = king.getView().getX();
    int y = king.getView().getY();
    for (BattleEntity entity : List.copyOf(world.getHolder().entities())) {
      if (entity instanceof WorldEntity w
          && w.side() == king.side()
          && w.getHitPoints() != null
          && HitPoints.alive(w.getHitPoints())
          && inside(w.getView().getX(), w.getView().getY(), x, y, radius)) {
        world.circleKill(w, radius);
      }
    }
  }

  /** Whether a point lies within the circle: the square around it, then the squared distance. */
  private static boolean inside(int px, int py, int cx, int cy, int radius) {
    int dx = cx - px;
    int dy = cy - py;
    if (dx > radius || dx < -radius || dy > radius || dy < -radius) {
      return false;
    }
    checkArgument(radius <= 0x7ffe, () -> "a circle wider than the standard arena's length");
    return Integer.compareUnsigned(dx * dx + dy * dy, radius * radius) <= 0;
  }

  /** The end timer: 0 until the match ends, then 1 and 50 more each update. */
  public int getEndTimerMs() {
    return endTimerMs;
  }

  /** Whether the last step ticked the entities, rather than only cleaning the holder up. */
  public boolean isLastTicked() {
    return lastTicked;
  }

  /** Whether the match has ended. */
  public boolean isEnded() {
    return ended;
  }

  /** The tiebreaker's time: 0 until it begins, then 50 more each of its steps. */
  public int getTiebreakMs() {
    return tiebreakMs;
  }

  /** The winning side, or -1 for none; 0 until the match ends. */
  public int getWinner() {
    return winner;
  }

  /**
   * A king's visit in its post-hook: its side refills its hand and regenerates its elixir.
   *
   * @param king the king tower
   */
  public void kingVisit(TowerEntity king) {
    sides.get(king.side() & 1).visit(timeline, maxMana);
  }

  /**
   * The crowns a side has taken: 3 when the other side's king is dead, else one for each princess
   * tower the other side has lost, up to 2. A fallen princess tower counts from the cleanup that
   * removes it from the holder.
   *
   * @param side 0 or 1
   */
  public int crowns(int side) {
    int other = 1 - side;
    if (!kingAlive(other)) {
      return 3;
    }
    int princessTowers = 0;
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof TowerEntity tower
          && tower.getData().summonerTower()
          && tower.side() == other) {
        princessTowers++;
      }
    }
    return 2 - Math.min(princessTowers, 2);
  }

  /** A side's king, which is never removed from the holder. */
  private TowerEntity king(int side) {
    for (BattleEntity entity : world.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.getData().king() && tower.side() == side) {
        return tower;
      }
    }
    throw new IllegalStateException("side " + side + " has no king tower");
  }

  /** Whether a side's king still has hit points. */
  private boolean kingAlive(int side) {
    return HitPoints.alive(king(side).getHitPoints());
  }

  /**
   * Whether the match is decided: its end timer runs, a king is dead, overtime has seen a crown, or
   * the time is up.
   */
  private boolean decided() {
    if (endTimerMs > 0) {
      return true;
    }
    if (!kingAlive(0) || !kingAlive(1)) {
      return true;
    }
    if (timeline.overtime() && crowns(0) != crowns(1)) {
      return true;
    }
    return timeline.timeUp();
  }

  /**
   * The deck index a play of a card stands for: the first slot of the hand holding that card, else
   * the card's first index in the deck.
   *
   * @param side the playing side
   * @param card the card row's name
   */
  public int deckIndex(int side, String card) {
    MatchSide matchSide = sides.get(side);
    List<MatchCard> deck = matchSide.deck();
    for (int index : matchSide.getHand().slots()) {
      if (index != Hand.EMPTY && deck.get(index).name().equals(card)) {
        return index;
      }
    }
    for (int index = 0; index < deck.size(); index++) {
      if (deck.get(index).name().equals(card)) {
        return index;
      }
    }
    throw new IllegalArgumentException(card + " is not in side " + side + "'s deck");
  }

  /**
   * The match's gates for a play of a card, its cost the card's own. A Mirror's play and a variant
   * card's are gated on their item's cost instead.
   *
   * @param side the playing side
   * @param index the card's deck index
   * @return 0 when the play may go on, else the code it is refused with
   * @see #gate(int, int, int)
   */
  public int gate(int side, int index) {
    MatchCard card = sides.get(side).deck().get(index);
    checkArgument(
        !card.mirror() && card.variant() == null,
        () -> card.name() + "'s play is gated on its item's cost");
    return gate(side, index, card.cost());
  }

  /**
   * The match's gates for a play, in order: the king alive, the card in the hand, the elixir.
   *
   * @param side the playing side
   * @param index the card's deck index
   * @param cost the cost the elixir must cover: the card's, or a Mirror's or a variant's item's
   * @return 0 when the play may go on, else the code it is refused with
   */
  public int gate(int side, int index, int cost) {
    MatchSide matchSide = sides.get(side);
    if (decided()) {
      return OVER;
    }
    if (!kingAlive(side)) {
      return KING_DEAD;
    }
    int slot = matchSide.getHand().slotOf(index);
    if (slot < 0 || slot >= Hand.SLOTS) {
      return NOT_IN_HAND;
    }
    if (matchSide.wholeElixir() < cost) {
      return NOT_ENOUGH_ELIXIR;
    }
    return 0;
  }

  /**
   * The item a Mirror's play carries, as the player's client builds it from the king's copy of the
   * side's last card: that card, at the Mirror's level field plus the level offset, floored at 0,
   * for the Mirror's cost plus the card's, at most the most elixir there can be. With nothing to
   * repeat the item is the Mirror's own, at its level and its cost.
   *
   * <p>The card repeated is the last card itself, unless it was a hero play or its row sets
   * MirrorUsesRootSpell false: then it is the row the play was cast as, its hero row, or for a
   * variant card the option it was played as, whose cost the item adds. A variant card repeated as
   * itself, and an evolved play repeated as its evolved row, are in no shipped row and are refused.
   *
   * @param side the playing side
   * @param index the Mirror's deck index
   * @param level the level the Mirror is played at, counted from 1
   */
  public MirrorItem mirrorItem(int side, int index, int level) {
    MatchSide matchSide = sides.get(side);
    MatchCard mirror = matchSide.deck().get(index);
    checkArgument(mirror.mirror(), () -> mirror.name() + " is not a Mirror");
    int mirrorLevelField = level - 1;
    MatchCard last = matchSide.lastPlayedCopy();
    if (last == null) {
      return new MirrorItem(index, null, mirrorLevelField, mirrorLevelField, mirror.cost());
    }
    MatchCard source = repeatedCard(last, matchSide);
    // The level is not capped at the card's last: a level past it reads past its level tables.
    int levelField = Math.max(mirrorLevelField + mirrorLevelOffset, 0);
    checkArgument(
        levelField < 1 << LEVEL_FIELD_BITS,
        () -> "a Mirror's level past its item's level field: " + levelField);
    return new MirrorItem(
        index,
        source,
        mirrorLevelField,
        levelField,
        Math.min(mirror.cost() + source.cost(), maxMana));
  }

  /**
   * The card a Mirror repeats of its side's last card: the card itself, unless the last play was a
   * hero play or the card's row sets MirrorUsesRootSpell false, when it is the row the play was
   * cast as. After a hero play that is the hero row; after an evolved play, the card itself; after
   * a variant card's play, the option it was played as.
   *
   * @param last the copy of the side's last card
   * @param matchSide the side, which keeps the field and the option it was played with
   */
  private static MatchCard repeatedCard(MatchCard last, MatchSide matchSide) {
    int field = matchSide.lastPlayedCopyField();
    if (field == EvolutionItem.HERO) {
      return last.formRow(MatchCard.HERO_FORM);
    }
    if (last.variant() != null) {
      if (last.mirrorUsesRootSpell()) {
        throw new UnsupportedOperationException(
            "a Mirror of the variant card "
                + last.name()
                + " as the card itself, whose row casts no option, which no row sets");
      }
      // The option's row, which the item casts and whose cost it adds.
      int played = matchSide.lastPlayedCopyOption();
      checkState(played >= 0, () -> last.name() + " was played as no option");
      SpellVariant.Option option = last.variant().options().get(played);
      return new MatchCard(
          option.spell(),
          option.cost(),
          false,
          false,
          option.elixirProductionStopTimeMs(),
          false,
          null);
    }
    if (!last.mirrorUsesRootSpell() && field != 0) {
      throw new UnsupportedOperationException(
          "a Mirror of the evolved play of "
              + last.name()
              + " as its evolved row, which no row sets");
    }
    return last;
  }

  /**
   * The option a variant card's play is picked as, as the player's client picks it when it gives
   * the play: from the king's elixir as it stands less the whole elixir its client has promised to
   * the plays of the side it has given and not yet seen run, and the timeline's full bar now.
   *
   * @param side the playing side
   * @param card the variant card's row name
   * @param promised the whole elixir promised, set aside before the pick
   * @return the option's index
   */
  public int pickOption(int side, String card, int promised) {
    SpellVariant variant = sides.get(side).deck().get(deckIndex(side, card)).variant();
    checkArgument(variant != null, () -> card + " is not a variant card");
    int free = sides.get(side).getElixir() - promised * MatchSide.SCALE;
    return variant.pick(free, timeline.getFullBarMs(), maxMana);
  }

  /**
   * The item a variant card's play carries: the option it was picked as, that option's cost, and
   * the card's deck index.
   *
   * @param side the playing side
   * @param index the variant card's deck index
   * @param option the option's index
   */
  public VariantItem variantItem(int side, int index, int option) {
    MatchCard card = sides.get(side).deck().get(index);
    checkArgument(card.variant() != null, () -> card.name() + " is not a variant card");
    SpellVariant.Option picked = card.variant().options().get(option);
    return new VariantItem(
        index, option, picked.spell(), picked.cost(), picked.elixirProductionStopTimeMs());
  }

  /**
   * The item a play of a deck card carries, built from its side's count for the card as the play
   * runs.
   *
   * @param side the playing side
   * @param index the card's deck index
   */
  public EvolutionItem item(int side, int index) {
    return sides.get(side).item(index);
  }

  /**
   * A play that passed the gates and was placed or cast: its cost taken and its card cycled.
   *
   * @param side the playing side
   * @param index the card's deck index
   */
  public void play(int side, int index) {
    sides.get(side).play(index);
  }

  /**
   * A play that passed the gates and was placed or cast, with the item it carried: the cost of the
   * row it was cast as taken, the card cycled and its count moved.
   *
   * @param side the playing side
   * @param item the item the play carried
   */
  public void play(int side, EvolutionItem item) {
    sides.get(side).play(item);
  }

  /**
   * A Mirror's play that passed the gates and was placed or cast: the item's cost taken and the
   * Mirror cycled. The side's last card stays the one it repeated.
   *
   * @param side the playing side
   * @param item the Mirror's item, which repeats a card
   */
  public void playMirror(int side, MirrorItem item) {
    checkArgument(item.repeats() != null, () -> "a Mirror with nothing to repeat is not played");
    sides.get(side).playMirror(item);
  }

  /**
   * A variant card's play that passed the gates and was placed: the option's cost taken and its
   * production stop started, and the variant card cycled and kept as the last card.
   *
   * @param side the playing side
   * @param item the variant card's item
   */
  public void playVariant(int side, VariantItem item) {
    sides.get(side).playVariant(item);
  }
}
