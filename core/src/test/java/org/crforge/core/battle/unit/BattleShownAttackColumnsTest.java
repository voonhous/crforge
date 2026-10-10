/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Two columns a unit's row of a newer data version sets for its melee area attack, both only shown:
 * TryToFinishAttackAnimationBeforeNextAttack, read only by the character's view as it plays the
 * attack animation, and DisableMeleeAeoDamageEffect, which keeps the melee area hit from telling
 * the battle's presentation listener of it, a call whose answer the hit does not read. A Valkyrie
 * whose row sets both fights a Skeleton Army hit for hit as one whose row sets neither.
 */
class BattleShownAttackColumnsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The ticks the fight is followed for. */
  private static final int TICKS = 400;

  /**
   * One damage dealt: the tick, the area hit's attacker (empty for any damage dealt), the entity's
   * name and the amount.
   */
  private record Damage(int tick, String attacker, String target, int amount) {}

  /** Plays a Valkyrie into a Skeleton Army and records every damage dealt. */
  private static List<Damage> fight(GameTables tables) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<Damage> out = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                out.add(new Damage(tick, "", target.name(), damage));
              }

              @Override
              public void areaHit(
                  int tick,
                  WorldEntity attacker,
                  WorldEntity victim,
                  int damage,
                  int hitId,
                  DamageResult result) {
                out.add(new Damage(tick, attacker.name(), victim.name(), damage));
              }
            });
    battle.play(
        1, battle.getWorld().getRecords().card("Valkyrie"), LEVEL, 0, 3500, 14000, "Valkyrie");
    battle.play(
        1, battle.getWorld().getRecords().card("SkeletonArmy"), LEVEL, 1, 3500, 19500, "Army");
    while (battle.getBattle().getTick() <= TICKS) {
      battle.getBattle().step();
    }
    return out;
  }

  @Test
  @DisplayName(
      "a Valkyrie whose row sets the two shown attack columns deals every hit as one whose row"
          + " sets neither")
  void theShownColumnsChangeNoHit(@TempDir Path folder) throws IOException {
    GameTables altered =
        GameData.altered(
            folder,
            "characters",
            rows ->
                GameData.columns(rows, "Valkyrie")
                    .put("TryToFinishAttackAnimationBeforeNextAttack", true)
                    .put("DisableMeleeAeoDamageEffect", true));

    List<Damage> plain = fight(GameData.tables());
    List<Damage> shown = fight(altered);

    // The Valkyrie's area reaches several skeletons.
    assertThat(plain.stream().filter(d -> d.attacker().equals("Valkyrie_0")).count())
        .isGreaterThan(4);
    assertThat(shown).isEqualTo(plain);
  }
}
