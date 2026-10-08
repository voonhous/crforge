package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Princess (Princess_EV1 of data version 16.402.18): its starting action sets its
 * attack count to 0, and the action it runs as each attack starts picks the special attack (entry
 * 1, whose first arrow is Princess_EV1_FreezeProjectile) while the count is a multiple of two, the
 * plain one (entry 0, whose first arrow is Princess_EV1_Projectile) otherwise. Only the first arrow
 * of a volley has a starting action, a run on its shooter, which hands the count-raising group back
 * to the Princess: it raises her count by one and then picks her next entry. So the volleys
 * alternate, the special one first; without the hand-back the count would stay 0 and every volley
 * would be the special one.
 */
class PrincessEvolutionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "each volley's first arrow hands the count back to the Princess: special and plain"
          + " volleys alternate")
  void theVolleysAlternate() {
    GameTables tables = GameData.tables();
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
    List<String> firsts =
        arrows.stream()
            .filter(
                row ->
                    row.equals("Princess_EV1_Projectile")
                        || row.equals("Princess_EV1_FreezeProjectile"))
            .toList();
    assertThat(firsts)
        .as("the first arrows of the volleys")
        .hasSizeGreaterThanOrEqualTo(4)
        .startsWith(
            "Princess_EV1_FreezeProjectile",
            "Princess_EV1_Projectile",
            "Princess_EV1_FreezeProjectile",
            "Princess_EV1_Projectile");
  }
}
