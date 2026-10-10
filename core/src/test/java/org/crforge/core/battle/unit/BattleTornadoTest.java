/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A Tornado's pull and its buff's parent where the reference runs leave them: who a pull reaches,
 * the parent an instance keeps, an instance with the same parent stopping a new one, a removable
 * parent, a parent leaving and the not-attacking section's removal of a row.
 */
class BattleTornadoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A tick after every unit placed on the first one has deployed. */
  private static final int CAST_TICK = 25;

  /** A battle with the towers passive and a Tornado of side 0 on the top side's left. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final List<AreaEffectEntity> admitted = new ArrayList<>();
    final List<List<String>> pulled = new ArrayList<>();

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void areaEffectAdmitted(int tick, AreaEffectEntity areaEffect) {
                  admitted.add(areaEffect);
                }

                @Override
                public void areaPulled(
                    int tick, AreaEffectEntity areaEffect, List<AreaEffectEntity.Pull> pulls) {
                  pulled.add(pulls.stream().map(p -> p.target().name()).toList());
                }
              });
    }

    private int units;

    /** A unit placed on the first tick, under a name of its own. */
    CharacterEntity unit(int side, String row, int x, int y) {
      return match.deploy(0, GameData.unit(row), LEVEL, side, x, y, row + "_" + units++);
    }

    /**
     * Casts the Tornado once every unit has deployed and steps until it is admitted, then once
     * more: its row's HitSpeedOffset of 50 puts its first hit on the update after the one it is
     * admitted in.
     */
    AreaEffectEntity tornado() {
      match.placeAreaEffect(CAST_TICK, "Tornado", LEVEL, 0, 3500, 23500, "Tornado");
      for (int step = 0; step <= CAST_TICK && admitted.isEmpty(); step++) {
        match.getBattle().step();
      }
      match.getBattle().step();
      return admitted.get(0);
    }
  }

  @Test
  @DisplayName(
      "a pull reaches the enemy units in its circle, not a friendly unit, not one outside it and"
          + " not a tower, which takes the buff")
  void whoAPullReaches() {
    Scene scene = new Scene();
    CharacterEntity enemy = scene.unit(1, "Knight", 5500, 23500);
    CharacterEntity friend = scene.unit(0, "Knight", 2000, 22000);
    CharacterEntity far = scene.unit(1, "Knight", 14000, 23500);
    CharacterEntity cannon = scene.unit(1, "Cannon", 3500, 21500);
    CharacterEntity still = scene.unit(1, "Knight", 1500, 24500);
    // A unit whose movement is off as the Tornado lands.
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectAdmitted(int tick, AreaEffectEntity areaEffect) {
                still.setActive(CharacterEntity.MOVEMENT_SLOT, false);
              }
            });
    scene.tornado();

    assertThat(scene.pulled).isNotEmpty();
    assertThat(scene.pulled.get(0)).containsExactly(enemy.name());
    assertThat(cannon.getBuffs().carries("Tornado")).as("a building takes the buff").isTrue();
    assertThat(still.getBuffs().carries("Tornado")).isTrue();
    assertThat(friend.getBuffs().carries("Tornado")).isFalse();
    assertThat(far.getBuffs().carries("Tornado")).isFalse();
    TowerEntity tower =
        scene.match.getWorld().present().stream()
            .filter(e -> e instanceof TowerEntity t && t.side() == 1 && t.getBuffs() != null)
            .map(TowerEntity.class::cast)
            .filter(t -> t.getBuffs().carries("Tornado"))
            .findFirst()
            .orElseThrow();
    assertThat(scene.pulled.get(0)).doesNotContain(tower.name());
  }

  @Test
  @DisplayName("its buff keeps the Tornado as its parent; a buff that does not stack keeps none")
  void theParentIsKeptForABuffThatStacks() {
    Scene scene = new Scene();
    CharacterEntity enemy = scene.unit(1, "Knight", 5500, 23500);
    CharacterEntity other = scene.unit(1, "Knight", 9000, 9000);
    AreaEffectEntity tornado = scene.tornado();
    BuffInstance instance = enemy.getBuffs().items().get(0);
    assertThat(instance.getBuff().name()).isEqualTo("Tornado");
    assertThat(instance.getParent()).isSameAs(tornado);

    other.getBuffs().apply(GameData.records().buff("Rage"), 1000, LEVEL, tornado, 1, tornado);
    assertThat(other.getBuffs().items().get(0).getParent()).isNull();
  }

  @Test
  @DisplayName("an instance with the same parent, of any row, stops a new one from being listed")
  void theSameParentStopsANewInstance() {
    Scene scene = new Scene();
    CharacterEntity enemy = scene.unit(1, "Knight", 5500, 23500);
    AreaEffectEntity tornado = scene.tornado();
    BuffComponent buffs = enemy.getBuffs();
    buffs.apply(GameData.records().buff("Poison"), 1000, LEVEL, tornado, 0, tornado);
    assertThat(buffs.items()).hasSize(1);
    buffs.apply(GameData.records().buff("Poison"), 1000, LEVEL, tornado, 0);
    assertThat(buffs.items()).hasSize(2);
  }

  @Test
  @DisplayName("a removable parent removes the instance at the next visit, with time left")
  void aRemovableParentRemovesTheInstance() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.unit(1, "Knight", 5500, 23500);
    CharacterEntity parent = scene.unit(0, "Knight", 9000, 9000);
    scene.tornado();
    BuffComponent buffs = knight.getBuffs();
    buffs.entityRemoved(scene.admitted.get(0));
    buffs.apply(GameData.records().buff("Tornado"), 5000, LEVEL, parent, 0, parent);
    buffs.visit();
    assertThat(buffs.items()).hasSize(1);

    parent.takeDamage(100000, 0, 0, 1);
    assertThat(parent.isRemovable()).isTrue();
    buffs.visit();
    assertThat(buffs.items()).isEmpty();
  }

  @Test
  @DisplayName(
      "a parent that leaves removes its instances, where a source that leaves is only forgotten")
  void aLeavingParentRemovesItsInstances() {
    Scene scene = new Scene();
    CharacterEntity enemy = scene.unit(1, "Knight", 5500, 23500);
    AreaEffectEntity tornado = scene.tornado();
    BuffComponent buffs = enemy.getBuffs();
    buffs.apply(GameData.records().buff("Poison"), 1000, LEVEL, tornado, 0);
    assertThat(buffs.items()).hasSize(2);

    BuffInstance parented = buffs.items().get(0);
    buffs.entityRemoved(tornado);
    assertThat(parented.getParent()).as("let go as it is removed").isNull();
    assertThat(buffs.items()).hasSize(1);
    assertThat(buffs.items().get(0).getBuff().name()).isEqualTo("Poison");
    assertThat(buffs.items().get(0).getSource()).isNull();
  }

  @Test
  @DisplayName("the not-attacking section's removal of a row leaves an instance with a parent")
  void removingARowLeavesAParentedInstance() {
    Scene scene = new Scene();
    CharacterEntity enemy = scene.unit(1, "Knight", 5500, 23500);
    CharacterEntity source = scene.unit(0, "Knight", 9000, 9000);
    AreaEffectEntity tornado = scene.tornado();
    BuffComponent buffs = enemy.getBuffs();
    buffs.apply(GameData.records().buff("Tornado"), 1000, LEVEL, source, 0);
    assertThat(buffs.items()).hasSize(2);

    buffs.removeRow("Tornado");
    assertThat(buffs.items()).hasSize(1);
    assertThat(buffs.items().get(0).getParent()).isSameAs(tornado);
  }
}
