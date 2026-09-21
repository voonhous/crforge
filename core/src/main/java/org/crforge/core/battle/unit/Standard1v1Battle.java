package org.crforge.core.battle.unit;

import lombok.Getter;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleCommand;
import org.crforge.core.battle.BattleMode;
import org.crforge.core.battle.EntityHolder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * A battle on the standard arena with the six crown towers in place.
 *
 * <p>The towers are created side by side and, within a side, king first and then the two princess
 * towers from the low end of the arena's width to the high end. That order is their id order, and
 * it is load bearing: the building overlay folds ids into its per-side hash in that order, and a
 * character ranks its default targets in it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: tower positions, the top side mirrored along the arena's length, and the"
            + " creation order. Not modelled yet: players, hands, elixir, the match clock and how"
            + " a match ends; the mode never ends the battle.")
public class Standard1v1Battle {

  /** Tower placements of the bottom side, in routing cells: king, then the princess towers. */
  private static final int[][] TOWER_CELLS = {{18, 6}, {7, 13}, {29, 13}};

  @Getter private final BattleWorld world;
  @Getter private final Battle battle;

  public Standard1v1Battle() {
    TileMap tileMap = TileMap.standard1v1();
    this.world = new BattleWorld(tileMap);
    this.battle = new Battle(new EntityHolder(world), BattleMode.ENDLESS);
    for (int side : new int[] {WorldEntity.SIDE_BOTTOM, WorldEntity.SIDE_TOP}) {
      for (int i = 0; i < TOWER_CELLS.length; i++) {
        UnitData data = i == 0 ? UnitData.KING_TOWER : UnitData.PRINCESS_TOWER;
        int x = TOWER_CELLS[i][0] * TileMap.CELL_UNITS;
        int row = TOWER_CELLS[i][1];
        // The top side is the bottom side mirrored along the arena's length.
        int y = (side == WorldEntity.SIDE_TOP ? tileMap.height() - row : row) * TileMap.CELL_UNITS;
        battle
            .getHolder()
            .add(new TowerEntity(data, data.name() + "_" + side + "_" + i, side, x, y));
      }
    }
  }

  /**
   * Queues the placement of one character on the given tick. The command runs at the tail of that
   * tick's step, so the character's first deploy countdown step is the following tick.
   *
   * @return the character, which has no id until the holder admits it
   */
  public CharacterEntity deploy(int tick, UnitData data, int side, int x, int y) {
    CharacterEntity character = new CharacterEntity(world, data, data.name(), side, x, y);
    battle.queue(
        new BattleCommand() {
          @Override
          public int tick() {
            return tick;
          }

          @Override
          public void execute(Battle target) {
            target.getHolder().add(character);
          }
        });
    return character;
  }
}
