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
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
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
            + " created in formation order, each deploying at once or waiting its turn. Not"
            + " modelled yet: players, hands, elixir, the match clock and how a match ends; the"
            + " mode never ends the battle.")
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
   * @param result what the play came to
   * @param units the units it created, in creation order
   */
  public record Play(
      String name,
      int side,
      int x,
      int y,
      int tick,
      CardPlacement.Result result,
      List<CharacterEntity> units) {}

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
   * the tick's entity tick admits and visits them. A refused play creates nothing.
   *
   * @param tick the tick the play runs on
   * @param card the card
   * @param level the level of its units, counted from 1
   * @param side the placing side
   * @param x the requested point in game units
   * @param y the requested point in game units
   * @param name the play's name: unit {@code k} is named {@code name_k}
   */
  public void play(int tick, DeployCard card, int level, int side, int x, int y, String name) {
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

  private void runPlay(
      Battle target, DeployCard card, int level, int side, int x, int y, String name) {
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
    List<CharacterEntity> units = new ArrayList<>();
    for (CardPlacement.Unit unit : result.units()) {
      if (unit.unit().onStartingAction() != null) {
        throw new UnsupportedOperationException(
            unit.unit().name() + " has a starting action, whose start by a card play is not held");
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
      target.getHolder().add(character);
      units.add(character);
    }
    plays.add(new Play(name, side, x, y, target.getTick(), result, List.copyOf(units)));
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
