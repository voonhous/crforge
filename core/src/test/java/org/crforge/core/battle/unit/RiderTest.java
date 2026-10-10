/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.move.ContactRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The riders a Goblin Giant carries, and what the battle refuses of a row that attaches them. */
class RiderTest {

  private static final int LEVEL_11 = 10;

  /** The Goblin Giant's riders' ring radius, written into its row. */
  private static final int RIDER_RADIUS = 900;

  /** The height a rider rides at, written into its row. */
  private static final int RIDER_HEIGHT = 4000;

  @TempDir static Path tablesFolder;

  /**
   * The configured tables with the Goblin Giant's riders written: two Spear Goblins on a ring of
   * 900, each turned by -22 within an arc of 90, riding at 4000, each leaving one Spear Goblin as
   * it dies; the Giant's card plays one Giant.
   */
  private static GameTables tables;

  /** The records of {@link #tables}. */
  private static BattleRecords records;

  @BeforeAll
  static void writeTheRiders() throws IOException {
    GameData.altered(
        tablesFolder,
        "characters",
        rows -> {
          GameData.columns(rows, "GoblinGiant")
              .put("SpawnCharacter", "SpearGoblinGiant")
              .put("SpawnNumber", 2)
              .put("SpawnRadius", RIDER_RADIUS);
          GameData.columns(rows, "SpearGoblinGiant")
              .put("SpawnAngleShift", -22)
              .put("SpawnMaxAngle", 90)
              .put("FlyingHeight", RIDER_HEIGHT)
              .put("DeathSpawnCharacter", "SpearGoblin")
              .put("DeathSpawnCount", 1);
        });
    GameData.alterLoaded(
        tablesFolder,
        "spells_characters",
        rows -> GameData.columns(rows, "GoblinGiant").put("SummonNumber", 1));
    tables = GameTables.load(tablesFolder);
    records = new BattleRecords(tables);
  }

  /** A Goblin Giant played for the bottom side on tick 0, after one step. */
  private static CharacterEntity playedGoblinGiant(Standard1v1Battle match) {
    match.play(0, records.card("GoblinGiant"), 11, 0, 3500, 10000, "GoblinGiant");
    match.getBattle().step();
    return match.getPlays().get(0).units().get(0);
  }

  @Test
  @DisplayName(
      "a played Goblin Giant makes its two riders first, with the lower ids, deploying with it")
  void theRidersComeFirst() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);

    List<CharacterEntity> riders = giant.riders();
    assertThat(riders)
        .extracting(CharacterEntity::name)
        .containsExactly("GoblinGiant_0_0", "GoblinGiant_0_1");
    for (CharacterEntity rider : riders) {
      assertThat(rider.getId()).isLessThan(giant.getId());
      assertThat(rider.getParent()).isSameAs(giant);
      assertThat(rider.getView().getDeployCountdown())
          .isEqualTo(giant.getView().getDeployCountdown());
    }
  }

  @Test
  @DisplayName("a rider is untouchable, answers no attacker and takes no part in collision")
  void aRiderIsOutOfReach() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);
    CharacterEntity rider = giant.riders().get(0);

    assertThat(rider.untouchable()).isTrue();
    assertThat(rider.getTargetView().acceptsAttacker(new GridEntity(), false)).isFalse();
    assertThat(ContactRule.collides(rider.getView())).isZero();
    assertThat(rider.filterSubject().attachedChild()).isTrue();
    assertThat(giant.untouchable()).isFalse();
    assertThat(ContactRule.collides(giant.getView())).isEqualTo(1);
  }

  @Test
  @DisplayName("a rider rides at its parent's radius behind its heading, turned by its own shift")
  void aRiderSitsBehindItsParent() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);
    // Facing up (heading 90), the first rider's ring angle 180 gives a share of 45 of its 90 arc,
    // turned by -22: a base of 113, at 900 from the Giant.
    CharacterEntity rider = giant.riders().get(0);
    assertThat(rider.getView().getX()).isEqualTo(3850);
    assertThat(rider.getView().getY()).isEqualTo(9672);
    assertThat(rider.getView().getZ()).isEqualTo(RIDER_HEIGHT);
  }

  @Test
  @DisplayName(
      "the cleanup that removes a parent lets its riders go and removes them too, their death"
          + " spawns folded in by that same cleanup")
  void theRidersLeaveWithTheirParent() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);
    List<CharacterEntity> riders = List.copyOf(giant.riders());
    for (int tick = 1; tick <= 30; tick++) {
      match.getBattle().step();
    }
    List<String> presentAfter = new ArrayList<>();
    List<String> letGo = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void parentLeft(int tick, CharacterEntity rider, CharacterEntity parent) {
                letGo.add(rider.name());
              }

              @Override
              public void afterPrePass(int tick, List<WorldEntity> present) {
                if (presentAfter.isEmpty()) {
                  present.forEach(e -> presentAfter.add(e.name()));
                }
              }
            });
    // Killed between two steps, the Giant dies at the next step's damage drain and leaves at the
    // opening cleanup of the step after.
    match.getWorld().kill(giant, null);
    match.getBattle().step();
    presentAfter.clear();
    match.getBattle().step();

    assertThat(letGo).containsExactly("GoblinGiant_0_0", "GoblinGiant_0_1");
    assertThat(presentAfter)
        .doesNotContain("GoblinGiant_0", "GoblinGiant_0_0", "GoblinGiant_0_1")
        .contains("GoblinGiant_0_0_0", "GoblinGiant_0_1_0");
    for (CharacterEntity rider : riders) {
      assertThat(rider.getParent()).isNull();
    }
  }

  @Test
  @DisplayName("a row that attaches riders is refused when placed directly, not by its card")
  void aDirectPlacementIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    assertThatThrownBy(() -> match.deploy(0, records.unit("GoblinGiant"), 11, 0, 3500, 10000))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("riders");
  }

  /** The instances an entity lists, as row, remaining time, total time, level and source. */
  private static List<String> instances(CharacterEntity unit) {
    return unit.getBuffs().items().stream()
        .map(
            i ->
                "%s %d %d %d %s"
                    .formatted(
                        i.getBuff().name(),
                        i.getRemaining(),
                        i.getTotal(),
                        i.getPackedLevel(),
                        i.getSource() == null ? null : i.getSource().name()))
        .toList();
  }

  @Test
  @DisplayName(
      "a buff on a parent is handed to each rider as an instance of its own, which the parent's"
          + " removal leaves")
  void aBuffIsHandedToEachRider() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);
    giant.getBuffs().apply(records.buff("ZapFreeze"), 500, LEVEL_11, giant, 1);

    List<String> expected = List.of("ZapFreeze 500 500 10 " + giant.name());
    assertThat(instances(giant)).isEqualTo(expected);
    for (CharacterEntity rider : giant.riders()) {
      assertThat(instances(rider)).isEqualTo(expected);
      assertThat(rider.getBuffs().items().get(0)).isNotSameAs(giant.getBuffs().items().get(0));
    }
    giant.getBuffs().removeRow("ZapFreeze");
    assertThat(giant.getBuffs().items()).isEmpty();
    for (CharacterEntity rider : giant.riders()) {
      assertThat(instances(rider)).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("a refresh on the parent refreshes each rider's instance by the same rule")
  void aRefreshIsHandedOver() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);
    BuffData zap = records.buff("ZapFreeze");
    giant.getBuffs().apply(zap, 500, LEVEL_11, null, 1);
    for (int i = 0; i < 5; i++) {
      giant.getBuffs().visit();
      giant.riders().forEach(rider -> rider.getBuffs().visit());
    }
    giant.getBuffs().apply(zap, 1500, LEVEL_11, null, 1);

    // 250 left of 500, refreshed to 1500: the whole grows by the difference.
    List<String> expected = List.of("ZapFreeze 1500 1750 10 null");
    assertThat(instances(giant)).isEqualTo(expected);
    for (CharacterEntity rider : giant.riders()) {
      assertThat(instances(rider)).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("a buff that names another for riders hands them that one")
  void aBuffNamingAnotherForRiders() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);
    giant.getBuffs().apply(records.buff("Vines_Trap_Snare_Base"), 2000, LEVEL_11, null, 1);

    assertThat(instances(giant)).containsExactly("Vines_Trap_Snare_Base 2000 2000 10 null");
    for (CharacterEntity rider : giant.riders()) {
      assertThat(instances(rider)).containsExactly("Vines_Trap_Snare_No_Effect 2000 2000 10 null");
    }
  }

  @Test
  @DisplayName(
      "a Clone buff stays with the parent, and a rider refuses what its row ignores, as the Goblin"
          + " Curse")
  void whatTheRidersDoNotTake() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);
    giant.getBuffs().apply(records.buff("Clone"), 1000, LEVEL_11, null, 0);
    giant.getBuffs().apply(records.buff("GoblinCurse"), 1000, LEVEL_11, null, 1);

    assertThat(giant.getBuffs().items())
        .extracting(i -> i.getBuff().name())
        .containsExactly("Clone", "GoblinCurse");
    for (CharacterEntity rider : giant.riders()) {
      assertThat(rider.getBuffs().items()).isEmpty();
    }
  }

  @Test
  @DisplayName("a buff applied to a rider other than through its parent is refused")
  void aDirectBuffOnARiderIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    CharacterEntity giant = playedGoblinGiant(match);
    assertThatThrownBy(
            () ->
                giant
                    .riders()
                    .get(0)
                    .getBuffs()
                    .apply(records.buff("ZapFreeze"), 500, LEVEL_11, null, 1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a rider, other than through its parent");
  }

  @Test
  @DisplayName("a Ram Rider's rider takes no building as its target, and fires its bola at a troop")
  void theRamRidersRiderTargetsTroopsOnly() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    match.play(0, records.card("RamRider"), 11, 0, 3500, 10000, "RamRider");
    match.getBattle().step();
    CharacterEntity rider = match.getPlays().get(0).units().get(0).riders().get(0);
    for (int tick = 1; tick <= 40; tick++) {
      match.getBattle().step();
    }
    assertThat(rider.getTargeting().getReference()).isNull();

    // A red Knight ahead of it: the rider takes it, and its bola snares it.
    CharacterEntity knight = match.deploy(41, records.unit("Knight"), 11, 1, 3500, 16000);
    boolean snared = false;
    for (int tick = 41; tick <= 200 && !snared; tick++) {
      match.getBattle().step();
      snared = knight.getBuffs().carries("BolaSnare");
    }
    assertThat(snared).isTrue();
  }

  @Test
  @DisplayName("the rider ranks a carrier of the buff it names lower")
  void theRiderRanksASnaredTargetLower() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    match.play(0, records.card("RamRider"), 11, 0, 3500, 10000, "RamRider");
    CharacterEntity knight = match.deploy(0, records.unit("Knight"), 11, 1, 3500, 20000);
    match.getBattle().step();
    CharacterEntity rider = match.getPlays().get(0).units().get(0).riders().get(0);
    assertThat(rider.getSelection().carriesDeprioritizingBuff(knight.getTargetView())).isFalse();

    knight.getBuffs().apply(records.buff("BolaSnare"), 2000, LEVEL_11, null, 0);
    assertThat(rider.getSelection().carriesDeprioritizingBuff(knight.getTargetView())).isTrue();
  }

  @Test
  @DisplayName("the buff a character's targeting passes over outright is refused as it is applied")
  void aBuffTheTargetingPassesOverIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(tables);
    // No row passes over a buff without ranking its carriers lower instead; this one is made to.
    match.deploy(
        0,
        records.unit("RamRider").toBuilder().deprioritizeTargetsWithBuff(false).build(),
        11,
        0,
        3500,
        10000);
    CharacterEntity knight = match.deploy(0, records.unit("Knight"), 11, 1, 3500, 20000);
    match.getBattle().step();

    assertThatThrownBy(
            () -> knight.getBuffs().apply(records.buff("BolaSnare"), 2000, LEVEL_11, null, 0))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("targeting reads");
  }
}
