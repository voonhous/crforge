package org.crforge.core.battle.spawn;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.grid.TileMap;

/**
 * The perform of a character spawn row: which object the children come from, the point they are
 * placed around, what the spawner is handed, and the calls made on the children afterwards.
 *
 * <p><b>The source</b> is the entity that caused the action, or, when the row says so, the owner of
 * the action. It gives the point, the children's level and their side.
 *
 * <p><b>The point.</b> A spawn at the source takes the source's own position. A spawn to a location
 * tries its columns in order, for each axis on its own, and takes the first that is not zero: the
 * absolute column, counted in half tiles from the arena's edge; the relative column, in half tiles
 * from the source; the expression; the mirrored column, in half tiles from the source. With none of
 * them the axis is the source's own. The bottom side's relative column moves the point left along
 * the width and up along the length; the top side's the other way on both. The mirrored column
 * flips with the side along the length only. In a two-against-two battle only the lowest bit of the
 * side decides. The offset columns are not added: only an area-effect spawn adds them. A row that
 * asks for a building's placement then has its point moved by that search.
 *
 * <p><b>The block.</b> The count and the radius are the row's, and a single child stands on the
 * point itself. The level is the row's, or the source's when the row leaves it at -1. The action
 * each child runs is the row's, unless the row shares a context the action has, a target. The
 * prestige is the owner's, a character's or an area effect's, when the row inherits it.
 *
 * <p><b>After the spawn</b>, for each child in order: the action scheduled with the shared target;
 * then each child linked into its source's group, when the source is a character; then the champion
 * hand-over, when the unit is a champion; then the clone setting, for a clone spawn.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled and held by the recorded perform cases: the source, both classes' points with the"
            + " side's flips and the expression, no offsets, the block handed to the spawner, and"
            + " the calls after the spawn, in order. Supplied: the placement search a row that"
            + " asks for a building's placement moves the point by, and the two-against-two test."
            + " Not modelled: what the calls after the spawn do.")
public final class SpawnPerform {

  /** Game units per half tile, the unit of the location columns. */
  static final int HALF_TILE = TileMap.CELL_UNITS;

  /** The search that moves a point to where a building could stand. */
  @FunctionalInterface
  public interface PlacementSearch {

    /** The point the search settles on, as {x, y}. */
    int[] place(int x, int y);
  }

  /** A call the perform makes on a child after the spawn. */
  public enum AfterSpawnKind {
    /** The action to run on the child, scheduled with the shared target. */
    SCHEDULE("schedule"),
    /** The child linked into its source's group. */
    GROUP_LINK("group_link"),
    /** The champion hand-over. */
    CHAMPION("champion"),
    /** The child set up as a clone. */
    CLONE("clone");

    private final String label;

    AfterSpawnKind(String label) {
      this.label = label;
    }

    /** The call's short name. */
    public String label() {
      return label;
    }
  }

  /**
   * One call after the spawn.
   *
   * @param kind what the call does
   * @param child the child's index among those spawned
   */
  public record AfterSpawnCall(AfterSpawnKind kind, int child) {}

  private SpawnPerform() {
    // Utility class
  }

  /**
   * What the row hands the spawner.
   *
   * @param row the row
   * @param owner the owner of the action
   * @param instigator the entity that caused it, or null
   * @param hasTarget true when the action carries a target
   * @param twoVersusTwo true in a two-against-two battle
   * @param placement the building placement search, asked only by a row that wants it
   */
  public static SpawnArguments arguments(
      SpawnRow row,
      SpawnObject owner,
      SpawnObject instigator,
      boolean hasTarget,
      boolean twoVersusTwo,
      PlacementSearch placement) {
    SpawnObject source = row.parentGoAsSource() ? owner : instigator;
    UnitData configuration =
        row.spawnAsClone() && row.spawnDataClone() != null ? row.spawnDataClone() : row.spawnData();
    int x = row.toLocation() ? locationX(row, source, twoVersusTwo) : source.x();
    int y = row.toLocation() ? locationY(row, source, twoVersusTwo) : source.y();
    if (row.validatePlacementAsBuilding()) {
      int[] placed = placement.place(x, y);
      x = placed[0];
      y = placed[1];
    }
    BattleAction action = row.shareContext() && hasTarget ? null : row.actionToRunOnSpawned();
    int prestige = 0;
    if (row.inheritPrestigeFromParent()
        && (owner.kind() == BattleEntity.KIND_CHARACTER
            || owner.kind() == BattleEntity.KIND_AREA_EFFECT)) {
      prestige = owner.prestige();
    }
    return new SpawnArguments(
        configuration,
        x,
        y,
        row.count(),
        source,
        row.count() == 1,
        row.spawnRadius(),
        row.deployTimeMs(),
        row.useMorph(),
        row.isSpawnConstPriority(),
        row.isDeathSpawn(),
        row.ignoreEffects(),
        row.isEnemy(),
        row.spawnLevelIndex(),
        row.useDeploy(),
        action,
        row.spawnPushback(),
        prestige);
  }

  /**
   * The calls made on the children after the spawn, in order.
   *
   * @param row the row
   * @param source the source the children came from
   * @param hasTarget true when the action carries a target
   * @param champion true when the spawned unit is a champion
   * @param children how many children the spawn made
   */
  public static List<AfterSpawnCall> afterSpawn(
      SpawnRow row, SpawnObject source, boolean hasTarget, boolean champion, int children) {
    List<AfterSpawnCall> calls = new ArrayList<>();
    if (hasTarget && row.shareContext() && row.actionToRunOnSpawned() != null) {
      addEach(calls, AfterSpawnKind.SCHEDULE, children);
    }
    if (source != null
        && row.addToSourceGroup()
        && source.kind() == BattleEntity.KIND_CHARACTER
        && source.isCharacter()) {
      addEach(calls, AfterSpawnKind.GROUP_LINK, children);
    }
    if (champion) {
      addEach(calls, AfterSpawnKind.CHAMPION, children);
    }
    if (row.spawnAsClone()) {
      addEach(calls, AfterSpawnKind.CLONE, children);
    }
    return calls;
  }

  private static void addEach(List<AfterSpawnCall> calls, AfterSpawnKind kind, int children) {
    for (int child = 0; child < children; child++) {
      calls.add(new AfterSpawnCall(kind, child));
    }
  }

  /**
   * The side's flag for the location columns: the side, or only its lowest bit in two against two.
   */
  private static int sideFlag(SpawnObject source, boolean twoVersusTwo) {
    return twoVersusTwo ? source.side() & 1 : source.side();
  }

  /** The point along the width of a spawn to a location. */
  static int locationX(SpawnRow row, SpawnObject source, boolean twoVersusTwo) {
    if (row.absoluteX() != 0) {
      return row.absoluteX() * HALF_TILE;
    }
    if (row.relativeX() != 0) {
      int step = sideFlag(source, twoVersusTwo) != 0 ? HALF_TILE : -HALF_TILE;
      return row.relativeX() * step + source.x();
    }
    if (row.xPositionExpression() != null) {
      return row.xPositionExpression().getAsInt();
    }
    if (row.mirroredX() != 0) {
      return row.mirroredX() * HALF_TILE + source.x();
    }
    return source.x();
  }

  /** The point along the length of a spawn to a location. */
  static int locationY(SpawnRow row, SpawnObject source, boolean twoVersusTwo) {
    if (row.absoluteY() != 0) {
      return row.absoluteY() * HALF_TILE;
    }
    if (row.relativeY() != 0) {
      int step = sideFlag(source, twoVersusTwo) != 0 ? -HALF_TILE : HALF_TILE;
      return row.relativeY() * step + source.y();
    }
    if (row.yPositionExpression() != null) {
      return row.yPositionExpression().getAsInt();
    }
    if (row.mirroredY() != 0) {
      int step = sideFlag(source, twoVersusTwo) != 0 ? -HALF_TILE : HALF_TILE;
      return row.mirroredY() * step + source.y();
    }
    return source.y();
  }
}
