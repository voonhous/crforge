package org.crforge.core.battle.unit;

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
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MirrorItem;
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
        "Settled: tower positions, the top side mirrored along the arena's length, the creation"
            + " order, and the towers standing at their hit points at the level they are"
            + " created at and fighting from the first tick; a card play run as a command at the"
            + " head of its step, stamped with the battle's tick counter and run 20 ticks later,"
            + " its placement worked out against every character, live or queued, and its units"
            + " created in formation order, each deploying at once or waiting its turn; played as a"
            + " Ladder match, the players' hands, elixir, the match clock, its end and its"
            + " tiebreaker, held by match_elixir_150s, match_knights_king, match_overtime_tiebreak"
            + " and match_overtime_draw; a Mirror's play, its item built as it runs, held by"
            + " mirror_knight and mirror_fireball. Not modelled, and refused: a Mirror outside a"
            + " match, and one given while another play of its side is pending, which the"
            + " player's client may repeat in its place.")
public class Standard1v1Battle {

  /** The level the reference runs are played at, and the towers' level when none is given. */
  public static final int DEFAULT_LEVEL = 11;

  /** Tower placements of the bottom side, in routing cells: king, then the princess towers. */
  private static final int[][] TOWER_CELLS = {{18, 6}, {7, 13}, {29, 13}};

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
   *     target or fires, as in the reference runs made without the towers fighting
   */
  public Standard1v1Battle(GameTables tables, int towerLevel, boolean towersAttack) {
    TileMap tileMap = TileMap.standard1v1();
    this.world = new BattleWorld(tileMap);
    world.load(tables);
    this.battle = new Battle(world.getHolder(), BattleMode.ENDLESS);
    for (int side : new int[] {WorldEntity.SIDE_BOTTOM, WorldEntity.SIDE_TOP}) {
      for (int i = 0; i < TOWER_CELLS.length; i++) {
        // Both towers are the game's own rows: the king tower and the princess tower.
        UnitData data = world.getRecords().unit(i == 0 ? "KingTower" : "PrincessTower");
        int x = TOWER_CELLS[i][0] * TileMap.CELL_UNITS;
        int row = TOWER_CELLS[i][1];
        // The top side is the bottom side mirrored along the arena's length.
        int y = (side == WorldEntity.SIDE_TOP ? tileMap.height() - row : row) * TileMap.CELL_UNITS;
        TowerEntity tower =
            new TowerEntity(
                world, data, data.name() + "_" + side + "_" + i, side, x, y, towerLevel);
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
      MirrorItem mirror) {}

  /** A card play queued: the tick it runs on and its side. */
  private record QueuedPlay(int tick, int side) {}

  /** Every card play queued so far, which a Mirror's item is built against. */
  private final List<QueuedPlay> queuedPlays = new ArrayList<>();

  /**
   * How many ticks before a Mirror's own run another play of its side would be pending as the
   * player gives the Mirror: the play delay and the step it is given in.
   */
  private static final int MIRROR_PENDING_TICKS = PLAY_DELAY_TICKS + 1;

  /** The match the battle is played as, or null for a battle without players. */
  @Getter private LadderMatch match;

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
    LadderMatch ladder =
        new LadderMatch(
            world, world.getRecords(), List.of(deck0, deck1), new int[] {playerWord0, playerWord1});
    battle.setMode(ladder);
    world.setKingVisit(ladder::kingVisit);
    this.match = ladder;
    return ladder;
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
   * projectile casts it onto the placed point before its units are made. A refused play creates
   * nothing.
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
    queuedPlays.add(new QueuedPlay(tick, side));
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
   * the play.
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
    QueuedPlay queued = new QueuedPlay(tick, side);
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
      plays.add(new Play(name, side, x, y, target.getTick(), null, List.of(), code, item));
      return;
    }
    // With nothing to repeat the search is handed the king's last card, finds none and answers no
    // position.
    if (item.repeats() == null) {
      CardPlacement.Result refused =
          new CardPlacement.Result(CardPlacement.NO_POSITION, 0, 0, null, 0, List.of());
      plays.add(new Play(name, side, x, y, target.getTick(), refused, List.of(), 0, item));
      return;
    }
    DeployCard repeated = world.getRecords().card(item.repeats().name());
    if (repeated.unit() != null && repeated.unit().champion()) {
      throw new UnsupportedOperationException(
          name + ": a Mirror of the champion " + repeated.name() + ", which no reference holds");
    }
    place(
        target, repeated, item.level(), side, x, y, name, () -> match.playMirror(side, item), item);
  }

  private void runPlay(
      Battle target, DeployCard card, int level, int side, int x, int y, String name) {
    // In a match the play first passes the match's gates; a refused play changes nothing.
    Runnable pay = null;
    if (match != null) {
      int deckIndex = match.deckIndex(side, card.name());
      int code = match.gate(side, deckIndex);
      if (code != 0) {
        plays.add(new Play(name, side, x, y, target.getTick(), null, List.of(), code, null));
        return;
      }
      pay = () -> match.play(side, deckIndex);
    }
    place(target, card, level, side, x, y, name, pay, null);
  }

  /**
   * Places or casts a play that passed the match's gates, if any: the placement worked out, the
   * play paid for and its card cycled, the cast, and the units made.
   *
   * @param pay what pays for a placed play and cycles its card, or null outside a match
   * @param mirror the Mirror's item the play carried, or null
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
      MirrorItem mirror) {
    // The mask reads every character of the battle: the live list, then the ones still queued.
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
    CardPlacement.Result result =
        CardPlacement.place(
            world.getTileMap(),
            card,
            x,
            y,
            side,
            entities,
            SYMMETRICAL_DEPLOY_SNAP,
            LANE_BASED_DEPLOY_SEQUENCE);
    // A play placed or cast pays for itself and cycles its card before anything is made.
    if (pay != null && result.placed()) {
      pay.run();
    }
    // The cast comes before the units are made: a troop card's projectile is queued ahead of them.
    if (card.casts() && result.placed()) {
      // The card item's level field; the hand is not modelled, so it is the level played, less 1.
      world.castSpell(card, level - 1, side, result.x(), result.y(), name);
    }
    List<CharacterEntity> units = new ArrayList<>();
    for (CardPlacement.Unit unit : result.units()) {
      if (unit.tunnels()) {
        units.add(tunnel(target, unit, level, side, result.x(), result.y(), name));
        continue;
      }
      boolean waits = unit.start().state() == InitialDelay.WAITING;
      if (waits && unit.unit().spawnAttach()) {
        throw new UnsupportedOperationException(
            unit.unit().name()
                + " waits its turn to deploy, which makes its riders later, not held");
      }
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
      // The construction sets the unit deploying before it queues it, and entering that state
      // makes the riders of a row that attaches its children: they are queued first. It makes a
      // row's area object after them, updated at once, while the unit is not yet in the battle,
      // and then a row's push, which finds nobody: the command pass runs between the holder's
      // post-pass, which empties the spatial index, and the next pre-pass, which fills it.
      if (unit.unit().spawnAttach()) {
        world.attachRiders(character);
      }
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
    plays.add(new Play(name, side, x, y, target.getTick(), result, List.copyOf(units), 0, mirror));
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
      String name) {
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
   * character of the same row.
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
    if (data.pushesOnDeploy()) {
      throw new UnsupportedOperationException(
          data.name()
              + " pushes as a card play sets it deploying, and its card casts as it plays; play"
              + " it by its card");
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
