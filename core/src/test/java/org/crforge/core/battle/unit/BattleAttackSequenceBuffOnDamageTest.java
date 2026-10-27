/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The buff on damage of an attack sequence entry: an entry's CustomBuffOnDamage and
 * CustomBuffOnDamageTime stand in for the row's BuffOnDamage and BuffOnDamageTime at the damage
 * drain, while the order has two or more elements and the attacker's index selects the entry. An
 * entry that leaves them out (no buff, time -1) keeps the row's; a time of 0 applies nothing.
 *
 * <p>The scene, as in {@link BattleBuffOnDamageDrainTest}: the top side's Electro Wizard stands in
 * reach of the bottom side's left princess tower, its first zap landing on the tower. The scene
 * gives the Wizard a hand-written attack sequence: two entries, both with the row's own damage, its
 * index left at 0 (no action moves it), and a hand-written buff copied from the row's own.
 */
class BattleAttackSequenceBuffOnDamageTest {

  /** The level of the towers and of the Wizard. */
  private static final int LEVEL = 1;

  /** Where the Wizard stands, in reach of the bottom side's left princess tower. */
  private static final int WIZARD_X = 3500;

  private static final int WIZARD_Y = 11000;

  /** Long enough for the Wizard's deploy and its first zap. */
  private static final int TICKS = 200;

  /** The unit the scene plays. */
  private static final String WIZARD = "ElectroWizard";

  /** The hand-written buff an entry names, a copy of the Wizard's own BuffOnDamage row. */
  private static final String ENTRY_BUFF = "TestEntryBuffOnDamage";

  /** The row's own buff on damage time the scene writes. */
  private static final int ROW_TIME = 500;

  /** Side 0's left princess tower, the one the Wizard stands in reach of. */
  private static TowerEntity leftTower(Standard1v1Battle match) {
    return match.getWorld().getHolder().entities().stream()
        .filter(TowerEntity.class::isInstance)
        .map(TowerEntity.class::cast)
        .filter(t -> t.side() == 0 && !t.getData().king() && t.x() < 9000)
        .findFirst()
        .orElseThrow();
  }

  /** The time left of a buff on an entity, or -1 when it carries none. */
  private static int left(WorldEntity entity, String buff) {
    for (BuffInstance instance : entity.getBuffs().items()) {
      if (instance.getBuff().name().equals(buff)) {
        return instance.getRemaining();
      }
    }
    return -1;
  }

  /** What the tower carries at the end of the tick the Wizard's first zap lands on it. */
  private record Zapped(int rowBuffLeft, int entryBuffLeft) {}

  /**
   * Runs the scene to the end of the tick of the Wizard's first zap on the tower.
   *
   * @param tables the tables with the Wizard's hand-written sequence
   * @return the time left of the row's buff and of the entry's buff on the tower
   */
  private static Zapped run(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    TowerEntity tower = leftTower(match);
    String rowBuff = match.getWorld().getRecords().unit(WIZARD).buffOnDamage();
    int[] zapTick = {-1};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target == tower && zapTick[0] < 0) {
                  zapTick[0] = tick;
                }
              }
            });
    match.deploy(
        0, match.getWorld().getRecords().unit(WIZARD), LEVEL, 1, WIZARD_X, WIZARD_Y, "Wizard");
    for (int step = 0; step < TICKS && zapTick[0] < 0; step++) {
      match.getBattle().step();
    }
    assertThat(zapTick[0]).as("the Wizard zapped the tower").isPositive();
    return new Zapped(left(tower, rowBuff), left(tower, ENTRY_BUFF));
  }

  /**
   * The tables with the Wizard's row given a buff on damage time, an order and two entries of the
   * row's own damage, each entry then edited, and the entry buff added as a copy of the row's buff.
   *
   * @param order the AttackSequence order the row is given
   * @param first what is written into the first entry
   * @param second what is written into the second entry
   */
  private static GameTables tables(
      Path folder, int[] order, Consumer<ObjectNode> first, Consumer<ObjectNode> second)
      throws IOException {
    String[] rowBuff = new String[1];
    GameData.altered(
        folder,
        "characters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, WIZARD);
          rowBuff[0] = columns.get("BuffOnDamage").asText();
          columns.put("BuffOnDamageTime", ROW_TIME).put("MultipleTargets", 2);
          // No action moves the index: it stays 0, selecting the first entry.
          columns.put("AttackSequenceMode", "None");
          ArrayNode sequence = columns.putArray("AttackSequence");
          for (int index : order) {
            sequence.add(index);
          }
          ArrayNode list = columns.putArray("AttackSequenceList");
          int damage = columns.get("Damage").asInt();
          first.accept(list.addObject().put("Damage", damage));
          second.accept(list.addObject().put("Damage", damage));
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode copy = rows.get(rowBuff[0]).deepCopy();
          copy.put("index", rows.size());
          ((ObjectNode) copy.get("columns")).put("Name", ENTRY_BUFF);
          rows.set(ENTRY_BUFF, copy);
        });
    return GameTables.load(folder);
  }

  @Test
  @DisplayName("the selected entry's CustomBuffOnDamageTime replaces the row's BuffOnDamageTime")
  void theEntryTimeReplacesTheRows(@TempDir Path folder) throws IOException {
    Zapped zapped =
        run(
            tables(
                folder,
                new int[] {0, 1},
                entry -> entry.put("CustomBuffOnDamageTime", 600),
                entry -> {}));
    assertThat(zapped.rowBuffLeft()).isEqualTo(600);
    assertThat(zapped.entryBuffLeft()).isEqualTo(-1);
  }

  @Test
  @DisplayName("an entry the index does not select leaves the row's time")
  void anotherEntrysTimeIsNotRead(@TempDir Path folder) throws IOException {
    Zapped zapped =
        run(
            tables(
                folder,
                new int[] {0, 1},
                entry -> {},
                entry -> entry.put("CustomBuffOnDamageTime", 600)));
    assertThat(zapped.rowBuffLeft()).isEqualTo(ROW_TIME);
  }

  @Test
  @DisplayName(
      "the selected entry's CustomBuffOnDamage replaces the row's buff, for the row's time")
  void theEntryBuffReplacesTheRows(@TempDir Path folder) throws IOException {
    Zapped zapped =
        run(
            tables(
                folder,
                new int[] {0, 1},
                entry -> entry.put("CustomBuffOnDamage", ENTRY_BUFF),
                entry -> {}));
    assertThat(zapped.entryBuffLeft()).isEqualTo(ROW_TIME);
    assertThat(zapped.rowBuffLeft()).as("the row's buff is not applied").isEqualTo(-1);
  }

  @Test
  @DisplayName("an entry's buff on damage time of 0 applies no buff")
  void aTimeOfZeroAppliesNothing(@TempDir Path folder) throws IOException {
    Zapped zapped =
        run(
            tables(
                folder,
                new int[] {0, 1},
                entry -> entry.put("CustomBuffOnDamageTime", 0),
                entry -> {}));
    assertThat(zapped.rowBuffLeft()).isEqualTo(-1);
    assertThat(zapped.entryBuffLeft()).isEqualTo(-1);
  }

  @Test
  @DisplayName("in an order of one the entry's buff on damage columns are not read")
  void anOrderOfOneKeepsTheRows(@TempDir Path folder) throws IOException {
    Zapped zapped =
        run(
            tables(
                folder,
                new int[] {0},
                entry ->
                    entry.put("CustomBuffOnDamageTime", 600).put("CustomBuffOnDamage", ENTRY_BUFF),
                entry -> {}));
    assertThat(zapped.rowBuffLeft()).isEqualTo(ROW_TIME);
    assertThat(zapped.entryBuffLeft()).isEqualTo(-1);
  }
}
