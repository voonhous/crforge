package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An area effect an action's spawn row makes, where the reference runs do not reach: a spawn with
 * no cause, and a hit action on an area effect no action made, both refused; and what the spawn
 * keeps of its owner and its cause when the two differ.
 */
class BattleAreaEffectSpawnTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A point on the bottom side's left, away from every tower. */
  private static final int X = 3500;

  private static final int Y = 11500;

  @Test
  @DisplayName("a spawn with no cause is refused")
  void noCause() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, X, Y, "knight");
    match.getBattle().step();
    BattleAction spawn =
        GameData.actions().build("GoblinCurseCore", match.getWorld().binding(knight));

    assertThatThrownBy(() -> knight.actionHolder().start(spawn))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("no source");
  }

  @Test
  @DisplayName(
      "an area effect with a hit action that does not clone, placed by no action, is refused")
  void aPlacedHitAction() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    match.placeAreaEffect(0, "GoblinCurseBase", LEVEL, 0, X, Y, "base");

    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("not made by an action");
  }

  @Test
  @DisplayName(
      "the area effect stands at the owner's point and takes the cause's side and level, the cause"
          + " its parent, in the pass that ran the action")
  void ownerAndCause() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity owner = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, X, Y, "owner");
    CharacterEntity cause =
        match.deploy(0, GameData.unit("Knight"), LEVEL - 2, 1, X + 2000, Y + 3000, "cause");
    List<String> spawned = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectSpawned(
                  int tick,
                  SpawnHost from,
                  String action,
                  int phase,
                  SpawnHost source,
                  AreaEffectEntity a) {
                spawned.add(
                    "%s %s %s %s %d %d side %d level %d parent %s"
                        .formatted(
                            from.name(),
                            action,
                            source.name(),
                            a.getData().name(),
                            a.getX(),
                            a.getY(),
                            a.side(),
                            a.getPackedLevel(),
                            a.getParent().name()));
              }
            });
    match.getBattle().step();
    BattleAction spawn =
        GameData.actions().build("GoblinCurseCore", match.getWorld().binding(owner));
    owner.actionHolder().start(spawn, cause.actionHolder());

    // GoblinCurseBase is a Common row, so the cause's level stands as it is.
    assertThat(spawned)
        .containsExactly(
            "owner GoblinCurseCore cause GoblinCurseBase %d %d side 1 level %d parent cause"
                .formatted(owner.getView().getX(), owner.getView().getY(), cause.getPackedLevel()));
  }
}
