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
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Knight's taunt on an enemy troop that carries riders, a Goblin Giant: the perform taunts
 * the carrier and each of its riders onto the Knight, each with a run of its own, told to the
 * observers once for the carrier. A rider's run takes the reach test as a building's does; the
 * Goblin Giant itself, which targets only buildings, is not forced.
 */
class BattleKnightHeroTauntCarrierTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The ability's spawn of the taunting area effect. */
  private static final String SPAWN = "Knight_hero_CreateTauntAEO";

  /** The taunt the area effect's hit group runs. */
  private static final String TAUNT =
      Shipped.actionNames(
              Shipped.text(
                  Shipped.row("area_effect_objects", Shipped.text(SPAWN, "SpawnData")),
                  "OnHitAction"),
              "SubActions")
          .get(0);

  private static final String BUFF = Shipped.text(TAUNT, "ValidTargetBuff");

  /** How long a troop is taunted, in ms. */
  private static final int DURATION = Shipped.number(TAUNT, "ValidDuration");

  /**
   * A battle with the hero Knight and an enemy Goblin Giant walking at it, and every taunt line.
   */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    final CharacterEntity knight =
        match.deploy(0, GameData.unit("KnightHero"), LEVEL, 0, 9000, 13000, "knight");

    /** The Goblin Giant, played by its card, as it makes its riders then. */
    CharacterEntity enemy;

    final List<String> taunts = new ArrayList<>();

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void tauntPerformed(
                    int tick,
                    WorldEntity unit,
                    String action,
                    int phase,
                    ActionOwner instigator,
                    WorldEntity forced) {
                  taunts.add(unit.name() + " performed onto " + forced.name());
                }

                @Override
                public void tauntStepped(
                    int tick,
                    WorldEntity unit,
                    WorldEntity forced,
                    int durationMs,
                    int falloffMs,
                    List<String> calls) {
                  taunts.add(unit.name() + " " + durationMs + " " + calls);
                }
              });
    }

    /** Runs the ability's spawn of the taunting area effect, the Knight its cause. */
    void taunt() {
      BattleAction spawn = GameData.actions().build(SPAWN, match.getWorld().binding(knight));
      knight.actionHolder().start(spawn, knight.actionHolder());
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }
  }

  private static String reference(WorldEntity entity) {
    TargetView reference = entity.getTargeting().getReference();
    return reference == null ? null : reference.name();
  }

  @Test
  @DisplayName(
      "the hero Knight's taunt on a Goblin Giant taunts the Giant and each of its riders onto the"
          + " Knight, the perform told once")
  void carrierAndRidersAreTaunted() {
    Scene scene = new Scene();
    scene.match.play(0, GameData.card("GoblinGiant"), LEVEL, 1, 9000, 17000, "enemy");
    scene.step(30);
    scene.enemy =
        scene.match.getPlays().get(0).units().stream()
            .filter(unit -> !unit.riders().isEmpty())
            .findFirst()
            .orElseThrow();
    List<CharacterEntity> riders = scene.enemy.riders();
    assertThat(riders).isNotEmpty();
    scene.taunt();
    scene.step(1);
    assertThat(scene.taunts)
        .filteredOn(line -> line.contains("performed"))
        .containsExactly(scene.enemy.name() + " performed onto knight");
    // The Giant targets only buildings: its own run is armed with no duration and finishes, and
    // it keeps its building.
    assertThat(scene.taunts.get(1)).startsWith(scene.enemy.name() + " 0 [finish");
    assertThat(reference(scene.enemy)).isNotEqualTo("knight");
    // Each rider is armed after the carrier, in the order they were made, by a run of its own: in
    // reach of the Knight, it is forced onto it and takes the valid buff.
    assertThat(scene.taunts).hasSize(2 + riders.size());
    for (int i = 0; i < riders.size(); i++) {
      CharacterEntity rider = riders.get(i);
      assertThat(scene.taunts.get(2 + i))
          .isEqualTo(
              "%s %d [set_target knight 0 0 1, raise LOCK_TARGET, remaining %d, apply_buff %s %d"
                      .formatted(rider.name(), DURATION, DURATION, BUFF, DURATION)
                  + " level %d source knight side 0]".formatted(scene.knight.getPackedLevel()));
      assertThat(reference(rider)).isEqualTo("knight");
      assertThat(rider.getBuffs().carries(BUFF)).isTrue();
    }
  }
}
