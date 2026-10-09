package org.crforge.core.battle.unit;

import static org.crforge.core.util.ValidationUtils.checkArgument;
import static org.crforge.core.util.ValidationUtils.checkState;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleCommand;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleMode;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.InitialDelay;
import org.crforge.core.battle.deploy.MaskEntity;
import org.crforge.core.battle.deploy.PlacementSearch;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.crforge.core.battle.match.VariantItem;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * A battle on the standard arena with the six crown towers in place, each built from its row of the
 * game's tables.
 *
 * <p>The towers are created side by side and, within a side, king first and then the two princess
 * towers from the low end of the arena's width to the high end. That order is their id order, and
 * it is load bearing: the building overlay folds ids into its per-side hash in that order, and a
 * character ranks its default targets in it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: tower positions, each side's from the spawn group of its tower selection, its"
            + " king row at the king's level and its other rows at the selection's level, the top"
            + " side mirrored along the arena's length, the creation order, and the towers"
            + " standing at their hit points at the level they are created at and fighting from"
            + " the first tick; a card play run as a command at the head of its step, stamped"
            + " with the battle's tick counter and run 20 ticks later, its placement worked out"
            + " against every character, live or queued, and its units created in formation"
            + " order, each deploying at once or waiting its turn; played as a Ladder match, the"
            + " players' hands, elixir, the match clock, its end and its tiebreaker, held by"
            + " every reference battle's hands and elixir and by timeline_spells_through_rates,"
            + " card_Pekka_until_stop, timeline_tiebreak_tower_hp, timeline_tiebreak_equal_arrows"
            + " and timeline_overtime_crown; a Mirror's play, its item built as it runs, held by"
            + " golden-gaps-v1/mirror_after_troop and mirror_after_spell; a variant card's play,"
            + " its option picked from the elixir after the step 21 before its run, held by"
            + " card_item_merge_maiden_mounted and card_item_merge_maiden_on_foot; a play of a"
            + " deck card with slots, its item built as it runs and its evolved or hero row"
            + " placed, held by knight_evolved_third_play and the hero reference battles"
            + " (hero_giant, deck_hero_and_champion); the draw each player's data takes of the"
            + " battle's random source before the decks are dealt, bounded by the number of its"
            + " choices, held by the recorded Knight battle's opening hands. Not established:"
            + " what the choices of a player's data are. Not modelled, and refused: a Mirror"
            + " outside a match, and one given while another play of its side is pending, which"
            + " the player's client may repeat in its place; a variant card outside a match, run"
            + " before tick 21, or given while another play of its side is pending, whose cost"
            + " the pick would set aside; a play of an evolution slot's card given while another"
            + " play of it is due, whose item the client builds from the count before that play."
            + " Supplied: the step a variant's option is picked after, the last the client can"
            + " have seen before it gives the play; the level of the option's units, the level"
            + " the play is given, as for every card.")
public class Standard1v1Battle {

  /**
   * The towers' level when none is given, and the level the mechanic tests and the visualizer play
   * their units at.
   */
  public static final int DEFAULT_LEVEL = 11;

  /** The spawn group of the default tower selection: the king tower and two princess towers. */
  public static final String PRINCESS_TOWERS = "King_PrincessTowers";

  /** The table of the spawn groups a side's towers are placed from. */
  private static final String SPAWN_GROUPS = "spawn_groups";

  /** The column of a spawn group that lists its objects: each a row name and a routing cell. */
  private static final String SPAWN_GROUP_OBJECTS = "Objects";

  /**
   * One side's towers: the spawn group they are placed from, with the level of its king row and the
   * level of its other rows, the princess slots.
   *
   * @param spawnGroup the spawn group's row name, whose objects are the towers in creation order,
   *     each a row of the characters or buildings and a routing cell on the bottom side
   * @param kingLevel the level the group's king row is created at, counted from 1
   * @param level the level the group's other rows are created at, counted from 1
   */
  public record Towers(String spawnGroup, int kingLevel, int level) {}

  @Getter private final BattleWorld world;
  @Getter private final Battle battle;

  /**
   * A battle on the game's tables whose towers stand at {@link #DEFAULT_LEVEL}.
   *
   * @param tables the game tables the battle reads its rows from
   */
  public Standard1v1Battle(GameTables tables) {
    this(tables, DEFAULT_LEVEL);
  }

  /**
   * A battle on the game's tables whose towers stand at the given level and fight.
   *
   * @param tables the game tables the battle reads its rows from
   * @param towerLevel the level all six towers are created at, counted from 1
   */
  public Standard1v1Battle(GameTables tables, int towerLevel) {
    this(tables, towerLevel, true);
  }

  /**
   * A battle on the game's tables whose towers stand at the given level, fighting or passive.
   *
   * @param tables the game tables the battle reads its rows from: its variables and game tags are
   *     declared, and its records and action rows are kept for the entities
   * @param towerLevel the level all six towers are created at, counted from 1
   * @param towersAttack false to keep every tower passive for the whole battle: none selects a
   *     target or fires, as the mechanic tests that keep the towers out of a fight play it
   */
  public Standard1v1Battle(GameTables tables, int towerLevel, boolean towersAttack) {
    this(
        tables,
        List.of(
            new Towers(PRINCESS_TOWERS, towerLevel, towerLevel),
            new Towers(PRINCESS_TOWERS, towerLevel, towerLevel)),
        towersAttack);
  }

  /**
   * A battle on the game's tables with each side's towers placed from its own spawn group.
   *
   * <p>A side's towers are its spawn group's objects in their order, each the row it names at the
   * routing cell it gives: the king row (the one {@link UnitData#king()} answers for) at the side's
   * king level and every other row at the side's level.
   *
   * @param tables the game tables the battle reads its rows from: its variables and game tags are
   *     declared, its records and action rows are kept for the entities, and its spawn groups give
   *     the towers
   * @param sides the towers of side 0 and side 1
   * @param towersAttack false to keep every tower passive for the whole battle: none selects a
   *     target or fires, as the mechanic tests that keep the towers out of a fight play it
   */
  public Standard1v1Battle(GameTables tables, List<Towers> sides, boolean towersAttack) {
    checkArgument(sides.size() == 2, () -> "two sides' towers, got " + sides.size());
    TileMap tileMap = TileMap.standard1v1();
    this.world = new BattleWorld(tileMap);
    world.load(tables);
    this.battle = new Battle(world.getHolder(), BattleMode.ENDLESS);
    for (int side : new int[] {WorldEntity.SIDE_BOTTOM, WorldEntity.SIDE_TOP}) {
      Towers towers = sides.get(side);
      JsonNode objects =
          tables.table(SPAWN_GROUPS).row(towers.spawnGroup()).value(SPAWN_GROUP_OBJECTS);
      for (int i = 0; i < objects.size(); i++) {
        JsonNode object = objects.get(i);
        // Each tower is the game's own row: the king tower and the princess slots' row.
        UnitData data = world.getRecords().unit(object.path("Data").asText());
        int x = object.path("x").asInt() * TileMap.CELL_UNITS;
        int row = object.path("y").asInt();
        // The top side is the bottom side mirrored along the arena's length.
        int y = (side == WorldEntity.SIDE_TOP ? tileMap.height() - row : row) * TileMap.CELL_UNITS;
        int level = data.king() ? towers.kingLevel() : towers.level();
        TowerEntity tower =
            new TowerEntity(world, data, data.name() + "_" + side + "_" + i, side, x, y, level);
        if (!towersAttack) {
          tower.holdFire();
        }
        battle.getHolder().add(tower);
      }
    }
    // The setup places the towers straight into the live list, so they stand from the first tick.
    battle.getHolder().cleanup();
  }

  /** Whether the symmetrical snap of a placement applies: on in the standard game. */
  public static final boolean SYMMETRICAL_DEPLOY_SNAP = true;

  /** Whether the formation's lane sequence applies: on in the standard game. */
  public static final boolean LANE_BASED_DEPLOY_SEQUENCE = true;

  /** Ticks between a player's play and the step it runs in. */
  public static final int PLAY_DELAY_TICKS = 20;

  /** Every card play run so far, in the order they ran. */
  @Getter private final List<Play> plays = new ArrayList<>();

  /**
   * One card play that has run.
   *
   * @param name the play's name, which its units are named after
   * @param side the placing side
   * @param x the requested point
   * @param y the requested point
   * @param tick the tick it ran on
   * @param result what the placement came to, or null for a play a match's gate refused
   * @param units the units it created, in creation order
   * @param matchCode the code a match's gate refused the play with, or 0
   * @param mirror the item a Mirror's play carried, or null for any other card
   * @param variant the item a variant card's play carried, or null for any other card
   * @param evolution the item a play of any other card carried in a match, or null
   */
  public record Play(
      String name,
      int side,
      int x,
      int y,
      int tick,
      CardPlacement.Result result,
      List<CharacterEntity> units,
      int matchCode,
      MirrorItem mirror,
      VariantItem variant,
      EvolutionItem evolution) {}

  /** What a queued play plays: a deck card, a Mirror or a variant card. */
  private enum PlayKind {
    CARD,
    MIRROR,
    VARIANT
  }

  /** A card play queued: the tick it runs on, its side, its card and what it plays. */
  private record QueuedPlay(int tick, int side, String card, PlayKind kind) {}

  /** An ability command queued: the tick it runs on and its side. */
  private record QueuedAbility(int tick, int side) {}

  /**
   * Every card play queued so far, which a Mirror's and a variant card's item are built against.
   */
  private final List<QueuedPlay> queuedPlays = new ArrayList<>();

  /** Every ability command queued so far, which a variant card's pick is checked against. */
  private final List<QueuedAbility> queuedAbilities = new ArrayList<>();

  /**
   * How many ticks before a Mirror's own run another play of its side would be pending as the
   * player gives the Mirror: the play delay and the step it is given in.
   */
  private static final int MIRROR_PENDING_TICKS = PLAY_DELAY_TICKS + 1;

  /**
   * How many ticks before its own run a variant card's option is picked: after the step this many
   * ticks before, the last the player's client can have seen before it gives the play. A play of
   * its side that runs from the next tick on is pending at the pick.
   */
  private static final int VARIANT_PICK_TICKS = PLAY_DELAY_TICKS + 1;

  /**
   * How many ticks after a command is given the player's client holds back the cost it promised,
   * unless the command's run releases it first: a play that passes its gates and is placed releases
   * it as it pays, one refused at its run keeps it until then.
   */
  private static final int PROMISE_TICKS = 60;

  /** The match the battle is played as, or null for a battle without players. */
  @Getter private LadderMatch match;

  /** What a player's data that lists no choice picks: nothing, with no draw. */
  public static final int NO_PICK = -1;

  /** What each player's data picked, in the order the data was handed over. */
  @Getter private final List<Integer> playerDataPicks = new ArrayList<>();

  /**
   * Hands the battle one player's data, as its setup does for each player in turn before the decks
   * are dealt. Data that lists choices has one of them picked by a draw of the battle's random
   * source, bounded by their number: the draw is taken of a single choice too, where it answers 0
   * and still moves the source, so the shuffles that follow are seeded by later draws. Data that
   * lists none keeps its default and draws nothing.
   *
   * <p>What the choices are is not established: only their number is read here, and the pick is
   * kept for whoever comes to read it.
   *
   * @param choices how many choices the player's data lists
   * @return the index picked, or {@link #NO_PICK} when nothing was drawn
   */
  public int addPlayerData(int choices) {
    checkState(match == null, "a player's data is handed over before the decks are dealt");
    int picked = choices >= 1 ? world.getRandom().next(choices) : NO_PICK;
    playerDataPicks.add(picked);
    return picked;
  }

  /**
   * Plays the battle as a Ladder match between two players, set up before the first step: each
   * king's starting elixir and its deck shuffled into its battle order, side 0 first, with the
   * battle's random source. From then on each card play passes the match's gates and pays for
   * itself, and the kings regenerate their elixir and refill their hands.
   *
   * @param deck0 side 0's deck, by card row name
   * @param deck1 side 1's deck, by card row name
   * @param playerWord0 side 0's word, added to its shuffle's draw
   * @param playerWord1 side 1's word, added to its shuffle's draw
   * @return the match
   */
  public LadderMatch startLadderMatch(
      List<String> deck0, List<String> deck1, int playerWord0, int playerWord1) {
    return startLadderMatch(
        deck0, deck1, playerWord0, playerWord1, new int[deck0.size()], new int[deck1.size()]);
  }

  /**
   * Plays the battle as a Ladder match whose decks mark their evolution and hero slots: a play of a
   * card is evolved, or in its hero form, as its side's count and its slot flags make its item, and
   * a hero slot's champion is the one its king's slot follows.
   *
   * @param deck0 side 0's deck, by card row name
   * @param deck1 side 1's deck, by card row name
   * @param playerWord0 side 0's word, added to its shuffle's draw
   * @param playerWord1 side 1's word, added to its shuffle's draw
   * @param slots0 side 0's slot flags, by deck index
   * @param slots1 side 1's slot flags, by deck index
   * @return the match
   * @see #startLadderMatch(List, List, int, int)
   */
  public LadderMatch startLadderMatch(
      List<String> deck0,
      List<String> deck1,
      int playerWord0,
      int playerWord1,
      int[] slots0,
      int[] slots1) {
    LadderMatch ladder =
        new LadderMatch(
            world,
            world.getRecords(),
            List.of(deck0, deck1),
            new int[] {playerWord0, playerWord1},
            List.of(slots0, slots1));
    battle.setMode(ladder);
    world.setKingVisit(ladder::kingVisit);
    this.match = ladder;
    // Each king's two champion slots, then each deck's pass over them, side 0 first: the avatar
    // setup ends its deck's setup with the pass, after the hand is dealt.
    for (int side = 0; side < 2; side++) {
      world.kingTower(side).makeChampionSlots();
    }
    for (int side = 0; side < 2; side++) {
      List<UnitData> champions = new ArrayList<>();
      int championCards = 0;
      MatchSide matchSide = ladder.side(side);
      for (int index = 0; index < matchSide.deck().size(); index++) {
        // The Mirror and a variant card summon nothing of their own. A hero slot's card is asked
        // in its hero form, whose linked champion comes first.
        MatchCard matchCard = matchSide.deck().get(index);
        boolean hero = (matchSide.slotFlags(index) & MatchSide.HERO_SLOT) != 0;
        String form = matchCard.formRow(hero ? MatchCard.HERO_FORM : MatchCard.BASIC_FORM).name();
        UnitData champion =
            matchCard.mirror() || matchCard.variant() != null
                ? null
                : world.getRecords().cardChampion(form);
        champions.add(champion);
        if (champion != null) {
          championCards++;
        }
      }
      // A deck may hold two champion cards - a hero slot's card counts as one, so a hero next to a
      // champion card, or two hero slots, as Ladder decks of 16.402.18 do - and the deck pass gives
      // each a slot. The pass stops after the second, so a third would get none, which no
      // reference holds.
      if (championCards > 2) {
        throw new UnsupportedOperationException(
            "side "
                + side
                + "'s deck holds "
                + championCards
                + " champion cards, more than the king's two slots, which no reference holds");
      }
      world.kingTower(side).championDeckPass(champions);
    }
    return ladder;
  }

  /**
   * One ability command that has run.
   *
   * @param name the command's name
   * @param side the commanding side
   * @param unit the name of the unit it names
   * @param tick the tick it ran on
   * @param outcome what it came to
   */
  public record AbilityUse(
      String name, int side, String unit, int tick, AbilityCommand.Outcome outcome) {}

  /** Every ability command run so far, in the order they ran. */
  @Getter private final List<AbilityUse> abilityUses = new ArrayList<>();

  /**
   * Queues a player's ability command to run on the given tick, in a match: the tap on a champion's
   * button, naming one unit a card play of the side made. It runs in the command pass of that tick,
   * in order with the card plays, before the tick's entity tick: it passes its gates, pays the
   * ability's cost and has the slot following the unit request its live copies' ability. The
   * command is taken as given: the player's client gives one only from a button it shows ready,
   * which a command refused here might never be.
   *
   * @param tick the tick the command runs on
   * @param side the commanding side
   * @param unit the name of the unit it names, made by a card play run before it
   * @param name the command's name
   */
  public void useAbility(int tick, int side, String unit, String name) {
    if (match == null) {
      throw new UnsupportedOperationException(
          name + ": an ability command outside a match, which has no champion slots");
    }
    queuedAbilities.add(new QueuedAbility(tick, side));
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            CharacterEntity named = playedUnit(unit);
            checkArgument(
                named != null && named.side() == side,
                () -> name + " names " + unit + ", which no play of side " + side + " made");
            abilityUses.add(
                new AbilityUse(
                    name, side, unit, target.getTick(), AbilityCommand.run(world, side, named)));
          }
        });
  }

  /**
   * Queues a player's ability command that names its unit by game object id, as a replay's command
   * does: it carries no row and no play, so only the live entity holding that id answers it (see
   * {@link AbilityCommand#run(BattleWorld, int, int)}). It runs in the command pass of its tick, in
   * order with the card plays, like {@link #useAbility(int, int, String, String)}; the use is
   * recorded under the name of the unit a card play made with that id, or {@code #id} for none.
   *
   * @param tick the tick the command runs on
   * @param side the commanding side
   * @param objectId the game object id of the unit it names
   * @param name the command's name
   */
  public void useAbility(int tick, int side, int objectId, String name) {
    if (match == null) {
      throw new UnsupportedOperationException(
          name + ": an ability command outside a match, which has no champion slots");
    }
    queuedAbilities.add(new QueuedAbility(tick, side));
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            String unit = "#" + objectId;
            for (Play play : plays) {
              for (CharacterEntity made : play.units()) {
                if (made.getId() == objectId) {
                  unit = made.name();
                }
              }
            }
            abilityUses.add(
                new AbilityUse(
                    name, side, unit, target.getTick(), AbilityCommand.run(world, side, objectId)));
          }
        });
  }

  /** The unit a card play run so far made under a name, or null. */
  private CharacterEntity playedUnit(String name) {
    for (Play play : plays) {
      for (CharacterEntity unit : play.units()) {
        if (unit.name().equals(name)) {
          return unit;
        }
      }
    }
    return null;
  }

  /**
   * Queues a card play as a player makes it, between two steps: the play is stamped with the
   * battle's tick counter, the number of the next step, or 1 while the counter is still 0, and runs
   * {@link #PLAY_DELAY_TICKS} ticks after that stamp.
   *
   * @see #play(int, DeployCard, int, int, int, int, String)
   */
  public void submit(DeployCard card, int level, int side, int x, int y, String name) {
    int given = Math.max(battle.getTick(), 1);
    play(given + PLAY_DELAY_TICKS, card, level, side, x, y, name);
  }

  /**
   * Queues a card play to run on the given tick: at the head of that step the play is worked out
   * against the battle as it stands, and its units are handed to the holder in creation order, so
   * the tick's entity tick admits and visits them. A spell's play casts instead, at the placed
   * point: its area effect, or its projectile from the side's king tower. A troop card with a
   * projectile casts it onto the placed point before its units are made; one with an area effect
   * makes it at the placed point after them. A refused play creates nothing.
   *
   * @param tick the tick the play runs on
   * @param card the card
   * @param level the level it is played at, counted from 1
   * @param side the placing side
   * @param x the requested point in game units
   * @param y the requested point in game units
   * @param name the play's name: unit {@code k} is named {@code name_k}
   */
  public void play(int tick, DeployCard card, int level, int side, int x, int y, String name) {
    queuedPlays.add(new QueuedPlay(tick, side, card.name(), PlayKind.CARD));
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            runPlay(target, card, level, side, x, y, name);
          }
        });
  }

  /**
   * Queues a Mirror's play to run on the given tick, in a match: it plays its side's last card
   * again, one level above the Mirror's, for the Mirror's cost plus the card's, and the Mirror goes
   * to the back of the queue. The repeated card is placed or cast as itself, its units named after
   * the play, and staggered as the Mirror's own row staggers them: the Mirror's rows set no
   * stagger, so every unit deploys on the tick the play runs.
   *
   * <p>The item the play carries is built from the side's last card as the play runs. The player's
   * client builds it as the play is given, from that card or from a play of its side it has given
   * and not yet seen run; with no other play of the side due in the ticks between, the two agree. A
   * Mirror with another play of its side due then is refused, and so is a Mirror outside a match,
   * which has no last card, and one that repeats a champion, which no reference holds.
   *
   * @param tick the tick the play runs on
   * @param card the Mirror's card row name
   * @param level the level the Mirror is played at, counted from 1
   * @param side the placing side
   * @param x the requested point in game units
   * @param y the requested point in game units
   * @param name the play's name: unit {@code k} is named {@code name_k}
   */
  public void playMirror(int tick, String card, int level, int side, int x, int y, String name) {
    if (match == null) {
      throw new UnsupportedOperationException(
          "the Mirror outside a match, which keeps no last card to play again");
    }
    QueuedPlay queued = new QueuedPlay(tick, side, card, PlayKind.MIRROR);
    queuedPlays.add(queued);
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            runMirror(target, queued, card, level, x, y, name);
          }
        });
  }

  private void runMirror(
      Battle target, QueuedPlay queued, String card, int level, int x, int y, String name) {
    int side = queued.side();
    for (QueuedPlay other : queuedPlays) {
      if (other != queued
          && other.side() == side
          && other.tick() >= queued.tick() - MIRROR_PENDING_TICKS
          && other.tick() <= queued.tick()) {
        throw new UnsupportedOperationException(
            name
                + ": a Mirror given while another play of its side is pending, which the client"
                + " may repeat in place of the last card, is not modelled");
      }
    }
    int deckIndex = match.deckIndex(side, card);
    MirrorItem item = match.mirrorItem(side, deckIndex, level);
    // The gates read the Mirror's own hand slot and the item's cost; a refused play changes
    // nothing.
    int code = match.gate(side, deckIndex, item.cost());
    if (code != 0) {
      plays.add(
          new Play(name, side, x, y, target.getTick(), null, List.of(), code, item, null, null));
      return;
    }
    // With nothing to repeat the search is handed the king's last card, finds none and answers no
    // position.
    if (item.repeats() == null) {
      CardPlacement.Result refused =
          new CardPlacement.Result(CardPlacement.NO_POSITION, 0, 0, null, 0, List.of());
      plays.add(
          new Play(name, side, x, y, target.getTick(), refused, List.of(), 0, item, null, null));
      return;
    }
    // The repeated card is placed as itself, its units staggered as the Mirror's own row staggers
    // them: the item names the Mirror.
    DeployCard repeated =
        world.getRecords().withStaggerOfCard(world.getRecords().card(item.repeats().name()), card);
    if (world.getRecords().cardChampion(repeated.name()) != null) {
      throw new UnsupportedOperationException(
          name + ": a Mirror of the champion " + repeated.name() + ", which no reference holds");
    }
    place(
        target,
        repeated,
        item.level(),
        side,
        x,
        y,
        name,
        () -> match.playMirror(side, item),
        item,
        null,
        null);
  }

  /**
   * Queues a variant card's play to run on the given tick, in a match: it is played as the option
   * its player's client picks from the king's elixir as it gives the play, for that option's cost,
   * and the variant card goes to the back of the queue. The option's card is placed and cast as any
   * troop card, its units named after the play.
   *
   * <p>The option is picked after the step {@value #VARIANT_PICK_TICKS} ticks before the run, the
   * last the client can have seen before it gives the play, from the king's elixir less the costs
   * its client has promised: each play of its side given and not yet run holds back its item's cost
   * from the step after it is given until its run pays it. A variant play given in the tick of
   * another play of its side or the tick after, where the client's moment decides whether that
   * play's cost is held back yet, is refused, and so is one with a Mirror's play of its side
   * pending, one given within {@value #PROMISE_TICKS} ticks after a play of its side the match
   * refused at its run, which may still hold its cost back, one with an ability command of its side
   * given in the {@value #PROMISE_TICKS} ticks up to it, one outside a match, which has no king's
   * elixir, and one run before tick 21, given before the first step.
   *
   * @param tick the tick the play runs on
   * @param card the variant card's row name
   * @param level the level it is played at, counted from 1
   * @param side the placing side
   * @param x the requested point in game units
   * @param y the requested point in game units
   * @param name the play's name: unit {@code k} is named {@code name_k}
   */
  public void playVariant(int tick, String card, int level, int side, int x, int y, String name) {
    if (match == null) {
      throw new UnsupportedOperationException(
          card + " outside a match, which picks its option from a king's elixir");
    }
    checkArgument(
        tick >= VARIANT_PICK_TICKS,
        () ->
            name
                + ": a variant play runs on tick 21 or later, its option picked after the step 21"
                + " ticks before");
    QueuedPlay queued = new QueuedPlay(tick, side, card, PlayKind.VARIANT);
    queuedPlays.add(queued);
    int[] option = {-1};
    // The head of the next step sees the battle as the step left it: the pick runs there, before
    // the commands that can change its side's elixir, which are refused.
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick - VARIANT_PICK_TICKS + 1;
          }

          @Override
          public void execute(Battle target) {
            option[0] = match.pickOption(side, card, promised(queued, name));
          }
        });
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            runVariant(target, queued, card, level, x, y, name, option[0]);
          }
        });
  }

  private void runVariant(
      Battle target,
      QueuedPlay queued,
      String card,
      int level,
      int x,
      int y,
      String name,
      int option) {
    int side = queued.side();
    checkPromisesKnown(queued, name);
    int deckIndex = match.deckIndex(side, card);
    VariantItem item = match.variantItem(side, deckIndex, option);
    // The gates read the variant card's own hand slot and the option's cost; a refused play
    // changes nothing.
    int code = match.gate(side, deckIndex, item.cost());
    if (code != 0) {
      plays.add(
          new Play(name, side, x, y, target.getTick(), null, List.of(), code, null, item, null));
      return;
    }
    place(
        target,
        world.getRecords().card(item.spell()),
        level,
        side,
        x,
        y,
        name,
        () -> match.playVariant(side, item),
        null,
        item,
        null);
  }

  /**
   * The whole elixir a variant play's pick sets aside, as its player's client counts it when it
   * builds the play's item after the step before the play is given: the item's cost of each play of
   * its side given at least two ticks before the variant play and not yet run. Each such play is a
   * deck card's: its item, built here from the side's count, is the one it runs with, a Mirror's or
   * a variant card's being refused.
   */
  private int promised(QueuedPlay queued, String name) {
    int side = queued.side();
    int given = queued.tick() - PLAY_DELAY_TICKS;
    int total = 0;
    for (QueuedPlay other : queuedPlays) {
      int otherGiven = other.tick() - PLAY_DELAY_TICKS;
      if (other == queued
          || other.side() != side
          || otherGiven > given - 2
          || other.tick() < given) {
        continue;
      }
      if (other.kind() != PlayKind.CARD) {
        throw new UnsupportedOperationException(
            name
                + ": a variant play given while a Mirror's play of its side is pending, whose"
                + " item's cost the pick would set aside, is not modelled");
      }
      total += match.item(side, match.deckIndex(side, other.card())).cost();
    }
    return total;
  }

  /**
   * Refuses a variant play whose pick may have counted a promise {@link #promised} does not: a play
   * of its side given in the tick of the variant play or the tick before, which the client files
   * apart and counts only from the next step, at a moment of the client's not modelled; a play of
   * its side refused at its run within {@value #PROMISE_TICKS} ticks of its giving, whose promise
   * the client keeps until then; and an ability command of its side given in the {@value
   * #PROMISE_TICKS} ticks up to it, whose promise is not modelled.
   */
  private void checkPromisesKnown(QueuedPlay queued, String name) {
    int side = queued.side();
    int given = queued.tick() - PLAY_DELAY_TICKS;
    for (QueuedPlay other : queuedPlays) {
      if (other != queued
          && other.side() == side
          && other.tick() - PLAY_DELAY_TICKS >= given - 1
          && other.tick() <= queued.tick()) {
        throw new UnsupportedOperationException(
            name
                + ": a variant play given in the tick of another play of its side or the tick"
                + " after, whose cost the player's client may not yet set aside, is not modelled");
      }
    }
    // A refused play's promise is dropped by the first step whose tick reaches PROMISE_TICKS after
    // its giving; which count of the tick that step reads is not traced, so the window is two
    // ticks wider than the pick's step.
    for (Play play : plays) {
      boolean refused = play.matchCode() != 0 || play.result() == null || !play.result().placed();
      if (play.side() == side
          && refused
          && play.tick() - PLAY_DELAY_TICKS + PROMISE_TICKS >= given - 2
          && play.tick() < given) {
        throw new UnsupportedOperationException(
            name
                + ": a variant play given while a play of its side refused at its run may still"
                + " hold back its cost, is not modelled");
      }
    }
    for (QueuedAbility ability : queuedAbilities) {
      int abilityGiven = ability.tick() - PLAY_DELAY_TICKS;
      if (ability.side() == side
          && abilityGiven >= given - PROMISE_TICKS - 2
          && abilityGiven <= given) {
        throw new UnsupportedOperationException(
            name
                + ": a variant play given while an ability command of its side may hold back its"
                + " cost, is not modelled");
      }
    }
  }

  private void runPlay(
      Battle target, DeployCard card, int level, int side, int x, int y, String name) {
    // In a match the play carries its deck card's item, and first passes the match's gates on the
    // item's cost; a refused play changes nothing.
    Runnable pay = null;
    EvolutionItem item = null;
    DeployCard cast = card;
    if (match != null) {
      int deckIndex = match.deckIndex(side, card.name());
      if ((match.side(side).slotFlags(deckIndex) & MatchSide.EVOLUTION_SLOT) != 0) {
        checkNoneDue(target.getTick(), side, card.name(), name);
      }
      item = match.item(side, deckIndex);
      int code = match.gate(side, deckIndex, item.cost());
      if (code != 0) {
        plays.add(
            new Play(name, side, x, y, target.getTick(), null, List.of(), code, null, null, item));
        return;
      }
      EvolutionItem carried = item;
      pay = () -> match.play(side, carried);
      // An evolved or hero play is placed and cast as its row in that form, its units staggered
      // as the deck's card staggers them.
      if (item.field() != 0) {
        cast = world.getRecords().card(item.spell().name()).withStaggerOf(card);
      }
    }
    place(target, cast, level, side, x, y, name, pay, null, null, item);
  }

  /**
   * Refuses a play of an evolution slot's card while another play of the same card of its side is
   * due in the ticks between the play's giving and its run: the player's client builds the item
   * from the count before that play runs, the item here from the count after.
   */
  private void checkNoneDue(int tick, int side, String card, String name) {
    for (QueuedPlay other : queuedPlays) {
      if (other.side() == side
          && other.card().equals(card)
          && other.tick() >= tick - PLAY_DELAY_TICKS
          && other.tick() < tick) {
        throw new UnsupportedOperationException(
            name
                + ": a play of "
                + card
                + ", an evolution slot's card, given while another play of it is due, which the"
                + " client builds from the count before that play, is not modelled");
      }
    }
  }

  /**
   * Places or casts a play that passed the match's gates, if any: the placement worked out, the
   * play paid for and its card cycled, the cast, and the units made.
   *
   * @param pay what pays for a placed play and cycles its card, or null outside a match
   * @param mirror the Mirror's item the play carried, or null
   * @param variant the variant card's item the play carried, or null
   * @param evolution the item a play of any other card carried in a match, or null
   */
  private void place(
      Battle target,
      DeployCard card,
      int level,
      int side,
      int x,
      int y,
      String name,
      Runnable pay,
      MirrorItem mirror,
      VariantItem variant,
      EvolutionItem evolution) {
    CardPlacement.Result result =
        CardPlacement.place(
            world.getTileMap(),
            card,
            x,
            y,
            side,
            maskEntities(target),
            SYMMETRICAL_DEPLOY_SNAP,
            LANE_BASED_DEPLOY_SEQUENCE);
    // In a match, the units carry the king's count of card plays before this one.
    int deployIndex = match != null ? match.side(side).getDeployCounter() : -1;
    // A play placed or cast pays for itself and cycles its card before anything is made.
    if (pay != null && result.placed()) {
      pay.run();
    }
    // The cast comes before the units are made: a troop card's projectile is queued ahead of them.
    if (card.casts() && result.placed()) {
      // The card item's level field; the hand is not modelled, so it is the level played, less 1.
      world.castSpell(card, level - 1, side, result.x(), result.y(), name, deployIndex);
    }
    List<CharacterEntity> units = new ArrayList<>();
    // A card that is a group links each unit it makes after the one made before it.
    CharacterEntity previous = null;
    for (CardPlacement.Unit unit : result.units()) {
      // The construction applies a played unit's starting buff again after its level setter's,
      // with no source and the play's level, which no row played by a card asks for.
      if (unit.unit().startingBuff() != null) {
        throw new UnsupportedOperationException(
            unit.unit().name()
                + " is played with a StartingBuff, which the play applies again, not modelled");
      }
      if (unit.tunnels()) {
        if (card.group()) {
          throw new UnsupportedOperationException(
              card.name() + " is a group whose unit tunnels, which no card is, not modelled");
        }
        units.add(tunnel(target, unit, level, side, result.x(), result.y(), name, deployIndex));
        continue;
      }
      boolean waits = unit.start().state() == InitialDelay.WAITING;
      CharacterEntity character =
          new CharacterEntity(
              world,
              unit.unit(),
              name + "_" + unit.index(),
              side,
              unit.x(),
              unit.y(),
              level,
              unit.lane(),
              waits ? unit.start().waitMs() : -1);
      character.setDeployIndex(deployIndex);
      // Linked as it is made, before the setter that sets it deploying.
      if (card.group()) {
        character.linkAfter(previous);
        previous = character;
      }
      // The construction sets the unit deploying before it queues it, and entering that state
      // makes the riders of a row that attaches its children: they are queued first. It makes a
      // row's area object after them, updated at once, while the unit is not yet in the battle,
      // and then a row's push, which finds nobody: the command pass runs between the holder's
      // post-pass, which empties the spatial index, and the next pre-pass, which fills it. A unit
      // that waits its turn makes them all as its state visit sets it deploying, its riders then
      // queued after it.
      if (!waits) {
        character.enteredDeploying();
      }
      target.getHolder().add(character);
      // The opening cleanup that admits it starts it, deploying or still waiting its turn: its
      // row's starting action is queued with its own delay, so one without runs in its phase-1
      // pending pass of the play tick, before its first component visit. Starting it here queues
      // the same entry for the same pass.
      world.characterPlayed(character);
      character.start();
      units.add(character);
    }
    // A troop card that makes an area effect as well makes it at the placed point after its units,
    // at the card's level, as the cast's construction comes before its area effect.
    if (!card.spell() && card.areaEffect() != null && result.placed()) {
      world.castSpell(card, level - 1, side, result.x(), result.y(), name, deployIndex);
    }
    // The play is sent to every listener of a card play after its units are made: never to those
    // its own units start, which are listed only in their pending pass.
    if (result.placed()) {
      String played = card.name();
      if (mirror != null) {
        played = match.side(side).deck().get(mirror.index()).name();
      } else if (variant != null) {
        played = match.side(side).deck().get(variant.index()).name();
      } else if (evolution != null) {
        played = match.side(side).deck().get(evolution.index()).name();
      }
      world.cardPlayed(side, card.name(), played, variant != null);
    }
    // In a match the champion slots hear the play after its cast.
    if (match != null && result.placed()) {
      world.championCardPlayed(
          side, world.getRecords().cardChampion(card.name()), deployIndex, name);
    }
    plays.add(
        new Play(
            name,
            side,
            x,
            y,
            target.getTick(),
            result,
            List.copyOf(units),
            0,
            mirror,
            variant,
            evolution));
  }

  /**
   * The characters a placement's mask reads: every character of the battle, the live list and then
   * the ones still queued.
   */
  private static List<MaskEntity> maskEntities(Battle target) {
    List<MaskEntity> entities = new ArrayList<>();
    List<BattleEntity> all = new ArrayList<>(target.getHolder().entities());
    all.addAll(target.getHolder().queued());
    for (BattleEntity entity : all) {
      if (entity instanceof WorldEntity w) {
        entities.add(
            PlacementSearch.maskEntity(
                w.getData(),
                w.side(),
                w.getView().getX(),
                w.getView().getY(),
                w.getView().isAlive()));
      }
    }
    return entities;
  }

  /**
   * Works out where a play of a card would be placed against the battle as it stands now, as the
   * play's own placement does, and changes nothing: no play is queued, nothing is paid and no unit
   * is made. A play given now runs {@link #PLAY_DELAY_TICKS} ticks later against the battle as it
   * stands then, so the two can differ when the characters around the point move in between.
   *
   * @param card the card
   * @param side the placing side
   * @param x the requested point in game units
   * @param y the requested point in game units
   * @return what the placement comes to now: refused, or the placed point and each unit's position
   */
  public CardPlacement.Result previewPlacement(DeployCard card, int side, int x, int y) {
    return CardPlacement.place(
        world.getTileMap(),
        card,
        x,
        y,
        side,
        maskEntities(battle),
        SYMMETRICAL_DEPLOY_SNAP,
        LANE_BASED_DEPLOY_SEQUENCE);
  }

  /**
   * Plays a unit that tunnels: made and levelled as any troop, it is handed over onto its own king
   * tower and aimed at the placed point - not its formation point - in the spawn-pathfinding state,
   * then queued with its registration visit, in which it searches its route and takes its first
   * step. It is started as any played unit is.
   */
  private CharacterEntity tunnel(
      Battle target,
      CardPlacement.Unit unit,
      int level,
      int side,
      int pointX,
      int pointY,
      String name,
      int deployIndex) {
    CharacterEntity character =
        new CharacterEntity(
            world,
            unit.unit(),
            name + "_" + unit.index(),
            side,
            unit.x(),
            unit.y(),
            level,
            unit.lane(),
            -1);
    character.setDeployIndex(deployIndex);
    // The king that fills the side's tower slot, which the setup placed; it is never removed.
    TowerEntity king = null;
    for (BattleEntity entity : target.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.getData().king() && tower.side() == side) {
        king = tower;
      }
    }
    character.tunnelFrom(king.getView().getX(), king.getView().getY(), pointX, pointY);
    target.getHolder().addRegistered(character);
    world.characterPlayed(character);
    character.start();
    return character;
  }

  /**
   * Queues the placement of one character on the given tick. The command runs at the head of that
   * tick's step, so the step's opening cleanup admits the character and its first deploy countdown
   * step is that same tick. The placement starts the character: its row's starting action, when it
   * has one, runs in its phase-1 pending pass of the step, before its first component visit.
   *
   * @param tick the tick the placement is due on
   * @param data the character's published columns
   * @param level the character's level, counted from 1
   * @param side the side that owns the character
   * @param x deploy position in game units
   * @param y deploy position in game units
   * @return the character, which has no id until the holder admits it
   */
  public CharacterEntity deploy(int tick, UnitData data, int level, int side, int x, int y) {
    return deploy(tick, data, level, side, x, y, data.name());
  }

  /**
   * Queues the placement of one character under a name of its own, for a battle with more than one
   * character of the same row. The character starts deploying without its setter's entry, so a row
   * that pushes the enemies around it as a card play sets it deploying pushes nobody; the cast its
   * card makes as it plays is a play's, which a placement makes none of.
   *
   * @param tick the tick the placement is due on
   * @param data the character's published columns
   * @param level the character's level, counted from 1
   * @param side the side that owns the character
   * @param x deploy position in game units
   * @param y deploy position in game units
   * @param name the character's unique name within the battle
   * @return the character, which has no id until the holder admits it
   */
  public CharacterEntity deploy(
      int tick, UnitData data, int level, int side, int x, int y, String name) {
    if (data.spawnAttach()) {
      throw new UnsupportedOperationException(
          data.name() + " makes its riders as a card play sets it deploying; play it by its card");
    }
    CharacterEntity character = new CharacterEntity(world, data, name, side, x, y, level);
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            target.getHolder().add(character);
            // The placement starts it: its starting action waits for its first pending pass.
            character.start();
          }
        });
    return character;
  }

  /**
   * Places an area effect on the given tick, in the command pass at the head of that step, as a
   * spell's play would: the step's opening cleanup admits it, and it updates in that same step.
   *
   * @param tick the tick it is placed on
   * @param row its row
   * @param level its level, counted from 1
   * @param side its side
   * @param x its point along the width
   * @param y its point along the length
   * @param name its name
   */
  public void placeAreaEffect(
      int tick, String row, int level, int side, int x, int y, String name) {
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            AreaEffectData data = world.getRecords().areaEffect(row);
            world.createAreaEffect(
                row, x, y, side, PackedLevel.fromLevel(level, data.rarity()), name, "placed", null);
          }
        });
  }

  /**
   * Places an action owner: an object with a position, a side, a level and an action holder and
   * nothing else, standing in for the objects that run spawn rows in the data. It is handed to the
   * holder at once and admitted by the next cleanup, so it stands from the next step on.
   *
   * @param name its name, which the children it spawns are named after
   * @param side its side
   * @param x its position in game units
   * @param y its position in game units
   * @param packedLevel its level, packed
   */
  public ActionOwnerEntity addActionOwner(String name, int side, int x, int y, int packedLevel) {
    ActionOwnerEntity owner = new ActionOwnerEntity(world, name, side, x, y, packedLevel);
    battle.getHolder().add(owner);
    return owner;
  }

  /**
   * Schedules an action on an owner in the command pass of the given tick, as the owner's own
   * starting action would be scheduled: with the row's own delay, not asked to start at once, and
   * the owner as its cause. Outside every pending pass, an action with no delay waits for the first
   * pending pass of that tick.
   *
   * @param tick the tick the schedule is made on
   * @param owner the owner
   * @param action the action
   */
  public void scheduleAction(int tick, SpawnHost owner, BattleAction action) {
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            owner
                .actionHolder()
                .schedule(action, ActionHolder.OWN_DELAY, false, owner.actionHolder());
          }
        });
  }
}
