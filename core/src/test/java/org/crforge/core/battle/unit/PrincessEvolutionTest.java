package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.GameData.fields;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Princess (Princess_EV1 of data version 16.402.18): its starting action sets its
 * attack count to 0, and the action it runs as each attack starts picks the special attack (entry
 * 1, whose first arrow is its CustomFirstProjectile) while the count is a multiple of the reload
 * frequency, the plain one (entry 0) otherwise. Only the first arrow of a volley has a starting
 * action, a run on its shooter, which hands the count-raising group back to the Princess: it raises
 * her count by one and then picks her next entry. So the volleys follow the count, the special one
 * first; without the hand-back the count would stay 0 and every volley would be the special one.
 *
 * <p>The scene writes the entries the two setting actions pick, 0 for the plain attack and 1 for
 * the special one, and the Princess's sequence of those two entries; the arrows and the frequency
 * are read from the rows.
 */
class PrincessEvolutionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The entries of the Princess's attack sequence the scene's setting actions pick. */
  private static final int PLAIN = 0;

  private static final int SPECIAL = 1;

  @TempDir static Path folder;

  /** The configured tables with the entries the setting actions pick written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          fields(rows, "Princess_EV1_set_default_attack").put("AttackIndex", PLAIN);
          fields(rows, "Princess_EV1_set_special_attack").put("AttackIndex", SPECIAL);
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Princess_EV1")
                .putArray("AttackSequence")
                .add(PLAIN)
                .add(SPECIAL));
    tables = GameTables.load(folder);
  }

  /** The first arrow of an entry of the Princess's attack sequence, as her row lists it. */
  private static String firstArrow(int entry) {
    JsonNode list = Shipped.column(Shipped.unitRow("Princess_EV1"), "AttackSequenceList");
    return list.get(entry).path("CustomFirstProjectile").asText();
  }

  @Test
  @DisplayName(
      "each volley's first arrow hands the count back to the Princess: special and plain"
          + " volleys alternate")
  void theVolleysAlternate() {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    // The rows of the arrows the Princess launches, in their order.
    List<String> arrows = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (projectile.getOwner() != null
                    && projectile.getOwner().name().equals("princess")) {
                  arrows.add(projectile.getData().name());
                }
              }
            });
    // In range of side 1's left princess tower, out of its reach.
    match.deploy(0, records.unit("Princess_EV1"), LEVEL, 0, 3500, 16600, "princess");
    for (int step = 0; step < 600; step++) {
      match.getBattle().step();
    }

    // The first arrow of each volley: the custom first projectile of the entry it was shot with.
    String plain = firstArrow(PLAIN);
    String special = firstArrow(SPECIAL);
    assertThat(plain).isNotEqualTo(special);
    List<String> firsts =
        arrows.stream().filter(row -> row.equals(plain) || row.equals(special)).toList();
    // The count is raised once a volley: the special entry on each multiple of the frequency.
    int frequency =
        Shipped.number(Shipped.row("variables", "Princess_EV1_reload_frequency"), "DefaultValue");
    List<String> expected = new ArrayList<>();
    for (int count = 0; count < 4; count++) {
      expected.add(count % frequency == 0 ? special : plain);
    }
    assertThat(firsts)
        .as("the first arrows of the volleys")
        .hasSizeGreaterThanOrEqualTo(4)
        .startsWith(expected.toArray(String[]::new));
  }
}
