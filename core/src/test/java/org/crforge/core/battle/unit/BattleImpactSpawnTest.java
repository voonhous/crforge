package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The children of a projectile's impact keep the step their registration visit takes: the
 * relocation off water that follows the holder add reads where the child stands then, not the point
 * it was made on. The Goblin Barrel's three Goblins, made close together on the impact, are already
 * one step apart on the impact step and go on the same way.
 */
class BattleImpactSpawnTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "the Goblin Barrel's Goblins have taken their first step apart on the impact step itself")
  void theFirstStepIsKept() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<CharacterEntity> children = new ArrayList<>();
    List<int[]> made = new ArrayList<>();
    int[] impactTick = {-1};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                children.add(child);
                made.add(new int[] {x, y});
                impactTick[0] = tick;
              }
            });
    match.play(0, GameData.card("GoblinBarrel"), LEVEL, 0, 3500, 25500, "Barrel");
    while (children.isEmpty()) {
      match.getBattle().step();
    }

    assertThat(children).hasSize(3);
    List<int[]> first = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      GridEntity view = children.get(i).getView();
      int[] step = {view.getX() - made.get(i)[0], view.getY() - made.get(i)[1]};
      assertThat(step).as("child %d moved on the impact step", i).isNotEqualTo(new int[] {0, 0});
      first.add(new int[] {view.getX(), view.getY()});
    }
    match.getBattle().step();
    for (int i = 0; i < 3; i++) {
      GridEntity view = children.get(i).getView();
      int[] before = {first.get(i)[0] - made.get(i)[0], first.get(i)[1] - made.get(i)[1]};
      int[] next = {view.getX() - first.get(i)[0], view.getY() - first.get(i)[1]};
      // The second step goes on along the first: the two vectors point the same way.
      long cross = (long) before[0] * next[1] - (long) before[1] * next[0];
      long dot = (long) before[0] * next[0] + (long) before[1] * next[1];
      assertThat(Math.abs(cross)).as("child %d keeps its heading", i).isLessThan(dot);
    }
  }
}
