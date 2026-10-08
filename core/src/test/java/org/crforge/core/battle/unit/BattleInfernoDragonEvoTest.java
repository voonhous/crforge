package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Inferno Dragon: its attack sequence has four entries and the Manual mode, so only its
 * actions move the index. Each hit counts one attack in a variable of the dragon, capped at 50; a
 * ticker started with the dragon sets the index from that count every 50 ms (entry 0 below 4
 * attacks, 1 below 9, 2 below 49, then 3), so a count reached by a hit is read from the hit after
 * the next tick's set.
 *
 * <p>The scene writes the entries the ticker's four setting actions pick (0 to 3), the dragon's
 * sequence of them and its hit speed; the entries' damages and the counts that pick them are read
 * from the rows.
 */
class BattleInfernoDragonEvoTest {

  private static final String DRAGON = "InfernoDragon_EV1";

  /** The dragon's hit speed, as the scene writes it. */
  private static final int HIT_SPEED = 400;

  /** The attack sequence's entries, one per setting action of the ticker. */
  private static final int ENTRIES = 4;

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          for (int entry = 0; entry < ENTRIES; entry++) {
            ((ObjectNode)
                    rows.get("InfernoDragon_EV1_UpdateAttackSequence_SubActions" + entry)
                        .get("fields"))
                .put("AttackIndex", entry);
          }
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          ObjectNode dragon = GameData.columns(rows, DRAGON).put("HitSpeed", HIT_SPEED);
          ArrayNode sequence = dragon.putArray("AttackSequence");
          for (int entry = 0; entry < ENTRIES; entry++) {
            sequence.add(entry);
          }
        });
    tables = GameTables.load(folder);
  }

  /** An entry's damage, as the dragon's row lists it; the first level deals it unscaled. */
  private static int damage(int entry) {
    JsonNode list = Shipped.column(Shipped.unitRow(DRAGON), "AttackSequenceList");
    return list.get(entry).path("Damage").asInt();
  }

  /** The counts below which the ticker picks the first three entries, read from its conditions. */
  private static List<Integer> bounds() {
    List<Integer> out = new ArrayList<>();
    for (String condition :
        Shipped.texts("InfernoDragon_EV1_UpdateAttackSequence", "PerActionConditions")) {
      Matcher bound = Pattern.compile("< (\\d+)$").matcher(condition);
      assertThat(bound.find()).as(condition).isTrue();
      out.add(Integer.parseInt(bound.group(1)));
    }
    return out;
  }

  /** The cap of the count, read from the expression that raises it. */
  private static int cap() {
    String value = Shipped.text("InfernoDragon_EV1_IncrementAttackCount", "Value");
    Matcher cap = Pattern.compile("^min\\((\\d+),").matcher(value);
    assertThat(cap.find()).as(value).isTrue();
    return Integer.parseInt(cap.group(1));
  }

  /**
   * The entry a hit deals: the hits before it are the count the ticker read, and the entry is the
   * number of bounds at or below that count.
   */
  private static int entryOfHit(int hit) {
    int entry = 0;
    for (int bound : bounds()) {
      if (hit >= bound) {
        entry++;
      }
    }
    return entry;
  }

  /** The card's first level, as the evolved play in the reference case stands. */
  private static final int LEVEL = 1;

  /** Side 1's right princess tower, at (14500, 25500). */
  private static final String TOWER = "PrincessTower_1_2";

  /** The dragon's hits on side 1's buildings, and the dragon itself. */
  private record Beam(List<int[]> hits, CharacterEntity dragon, BattleWorld world) {}

  /**
   * Runs an evolved Inferno Dragon of side 0 in front of side 1's right princess tower, the towers
   * at the level given and passive, so only the dragon deals damage.
   */
  private static Beam beamAtTheTower(int towerLevel, int ticks) {
    Standard1v1Battle match = new Standard1v1Battle(tables, towerLevel, false);
    List<int[]> hits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target.side() == WorldEntity.SIDE_TOP) {
                  hits.add(new int[] {tick, damage, target.name().equals(TOWER) ? 1 : 0});
                }
              }
            });
    CharacterEntity dragon =
        match.deploy(0, match.getWorld().getRecords().unit(DRAGON), LEVEL, 0, 14500, 20000);
    for (int tick = 0; tick < ticks; tick++) {
      match.getBattle().step();
    }
    return new Beam(hits, dragon, match.getWorld());
  }

  @Test
  @DisplayName(
      "the first four hits deal the first entry's damage, the next five the second's and the hits"
          + " after them the third's")
  void theDamageStepsWithTheAttackCount() {
    Beam beam = beamAtTheTower(15, 300);
    List<int[]> hits = beam.hits();
    // Every hit up to three past the third entry's first.
    int count = bounds().get(1) + 3;
    assertThat(hits).hasSizeGreaterThanOrEqualTo(count);
    for (int i = 0; i < count; i++) {
      assertThat(hits.get(i)[1]).as("hit %d", i + 1).isEqualTo(damage(entryOfHit(i)));
      assertThat(hits.get(i)[2]).as("hit %d on the tower", i + 1).isEqualTo(1);
    }
    assertThat(entryOfHit(count - 1)).isEqualTo(2);
    for (int i = 1; i < count; i++) {
      assertThat(hits.get(i)[0] - hits.get(i - 1)[0])
          .as("one hit a hit speed")
          .isEqualTo(HIT_SPEED / 50);
    }
  }

  @Test
  @DisplayName(
      "each hit counts one attack in the dragon's own variable, the count capped as its expression"
          + " caps it")
  void theCountIsTheDragonsAndCapped() {
    Beam beam = beamAtTheTower(15, 300);
    int key = beam.world().variableKey("InfernoDragon_EV1_AttackCount");
    long towerHits = beam.hits().stream().filter(hit -> hit[2] == 1).count();
    assertThat(beam.dragon().variable(key)).isEqualTo((int) Math.min(cap(), towerHits));
  }

  @Test
  @DisplayName(
      "from the hit after the third bound's count on (the 50th), the count kept across targets,"
          + " the hits deal the fourth entry's damage")
  void theFourthEntryFromTheFiftiethHit() {
    Beam beam = beamAtTheTower(1, 900);
    List<int[]> hits = beam.hits();
    int fourth = bounds().get(2);
    assertThat(hits).hasSizeGreaterThanOrEqualTo(fourth + 2);
    assertThat(hits.get(fourth - 1)[1]).as("the last hit of the third").isEqualTo(damage(2));
    assertThat(hits.get(fourth)[1]).as("the first hit of the fourth").isEqualTo(damage(3));
    assertThat(hits.get(fourth + 1)[1]).as("the next").isEqualTo(damage(3));
  }
}
