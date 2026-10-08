package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.Shipped.actionNames;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action a unit's row runs for each hit it lands (OnHitTargetAction): scheduled on the attacker
 * by the hit count, for every hit the target's hit points let through and before the damage is
 * taken off, with what was hit as the cause and the row's own delay, so it runs in the attacker's
 * pending pass of the hit's tick. Only the hero Mini Pekka's row sets it: a group whose hit effect
 * waits for the ability and whose spawn gives the hero its tag buff for 50 ms.
 */
class BattleHitTargetActionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The hit action the hero Mini Pekka's row names. */
  private static final String HIT_ACTION = text(unitRow("MiniPekkaHero"), "OnHitTargetAction");

  /** The spawn of the hit action's group that gives the hero its tag buff. */
  private static final String TAG_SPAWN =
      actionNames(HIT_ACTION, "SubActions").stream()
          .filter(action -> "ActionSpawn".equals(text(action, "ClassType")))
          .findFirst()
          .orElseThrow();

  /**
   * The hero Mini Pekka's row of the given records without its starting action, the timer its
   * ability's level runs on; nothing the hits run reads it without the ability.
   */
  private static UnitData heroWithoutTimer(Standard1v1Battle battle) {
    return battle.getWorld().getRecords().unit("MiniPekkaHero").toBuilder()
        .onStartingAction(null)
        .build();
  }

  /** A unit placed on tick 0 that never moves. */
  private static CharacterEntity still(
      Standard1v1Battle battle, UnitData row, int side, int x, int y, String name) {
    CharacterEntity unit = battle.deploy(0, row, LEVEL, side, x, y, name);
    unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    return unit;
  }

  @Test
  @DisplayName("the hero Mini Pekka's row names its hit action, which the battle reads")
  void theRowNamesItsHitAction() {
    UnitData hero = GameData.unit("MiniPekkaHero");

    assertThat(HIT_ACTION).isNotNull();
    assertThat(hero.onHitTargetAction()).isEqualTo(HIT_ACTION);
    assertThat(hero.unmodelledColumns()).doesNotContain("OnHitTargetAction");
    assertThat(GameData.unit("MiniPekka").onHitTargetAction()).isNull();
  }

  @Test
  @DisplayName(
      "each hit gives the hero its tag buff for 50 ms with what it hit as the source, after the"
          + " damage on the hit's tick, and the buff has run out before the next hit")
  void eachHitGivesTheHeroItsTagBuff(@TempDir Path folder) throws IOException {
    // The tag buff's time is written: 50 ms, one visit.
    GameTables tables =
        GameData.altered(
            folder,
            "actions",
            actions -> ((ObjectNode) actions.get(TAG_SPAWN).get("fields")).put("SpawnTime", 50));
    String tag = text(TAG_SPAWN, "SpawnData");
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity hero = still(battle, heroWithoutTimer(battle), 0, 3500, 14000, "p");
    CharacterEntity golem =
        still(battle, battle.getWorld().getRecords().unit("Golem"), 1, 3500, 15200, "g");
    List<String> events = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target == golem) {
                  events.add(tick + " hit");
                }
              }

              @Override
              public void buffApplied(int tick, WorldEntity target, BuffInstance buff) {
                if (target == hero) {
                  events.add(
                      "%d %s %d %s"
                          .formatted(
                              tick,
                              buff.getBuff().name(),
                              buff.getRemaining(),
                              buff.getSource() == null ? null : buff.getSource().name()));
                }
              }
            });
    List<Integer> tagged = new ArrayList<>();
    while (battle.getBattle().getTick() <= 200) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      if (hero.getBuffs().carries(tag)) {
        tagged.add(tick);
      }
    }

    List<String> hits = events.stream().filter(e -> e.endsWith(" hit")).toList();
    assertThat(hits).hasSizeGreaterThan(2);
    List<String> expected = new ArrayList<>();
    List<Integer> expectedTagged = new ArrayList<>();
    for (String hit : hits) {
      int tick = Integer.parseInt(hit.split(" ")[0]);
      expected.add(hit);
      expected.add(tick + " " + tag + " 50 g");
      expectedTagged.add(tick);
    }
    assertThat(events).containsExactlyElementsOf(expected);
    // Applied in the pending pass of the hit's tick, the 50 ms buff is gone a step later.
    assertThat(tagged).containsExactlyElementsOf(expectedTagged);
  }

  @Test
  @DisplayName(
      "a typed hit and a buff's damage over time from a unit with a hit action are refused, as no"
          + " reference holds them")
  void typedHitsAndDamageOverTimeAreRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity hero = still(battle, heroWithoutTimer(battle), 0, 3500, 10000, "p");
    CharacterEntity knight = still(battle, GameData.unit("Knight"), 1, 3500, 11000, "k");

    assertThatThrownBy(() -> knight.takeTypedHit(hero, 10, 0, 0, 1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("with OnHitTargetAction " + HIT_ACTION + " by a typed hit");
    assertThatThrownBy(() -> knight.takeDamageOverTime(10, hero))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("with OnHitTargetAction " + HIT_ACTION + " by a typed hit");
  }

  @Test
  @DisplayName(
      "the hero Mini Pekka as the game ships it starts, its ability level timer included, which"
          + " BattleMiniPekkaHeroQuestTest follows")
  void theShippedHeroStartsWithItsTimer() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);

    assertThatCode(
            () -> {
              battle.deploy(0, GameData.unit("MiniPekkaHero"), LEVEL, 0, 3500, 14000, "p").start();
            })
        .doesNotThrowAnyException();
  }
}
