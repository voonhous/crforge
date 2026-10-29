/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, on a unit whose completing shot is deflected by the
 * Monk's Deflect.
 *
 * <p>The scene: side 1's Musketeer shoots side 0's Monk, both held where they deployed, the towers
 * passive; the Monk at another level than the Musketeer. A hand-written buff that adds damage to
 * every third attack is started on the Musketeer as it is placed, the Musketeer as its giver. Its
 * third shot carries a copy of the buff, primed to add the damage. The deflection leaves the copy
 * on the shot: the Monk, the deflector's parent, takes the shot's damage with the added damage on
 * it, and the turned shot lands on the Musketeer with the added damage again, at the giver's level,
 * the count unchanged.
 */
class BattleEnchantedDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Monk's level: another than the Musketeer's, the giver's. */
  private static final int MONK_LEVEL = LEVEL + 2;

  /** The hand-written enchanting buff. */
  private static final String ENCHANT = "TestEnchant";

  /**
   * The buff's added damage, before scaling: small enough that the Musketeer outlives the third
   * shot turned back at it, so its loss is the shot's whole damage.
   */
  private static final int ADDED = 20;

  /** Long enough for four shots and the Deflect's end. */
  private static final int TICKS = 260;

  /** One step's losses: the step and the hit points the Monk and the Musketeer lost on it. */
  private record Loss(int tick, int monk, int musketeer) {}

  /** One run of the scene: its losses and the steps a shot was deflected on. */
  private record Scene(List<Loss> losses, List<Integer> deflections) {}

  @Test
  @DisplayName(
      "a deflected shot keeps the enchant's copy: the Monk takes the added damage at the"
          + " deflection, and the shot lands on its shooter with the added damage at the giver's"
          + " level")
  void aDeflectedCompletingShotCarriesTheAddedDamageBothWays(@TempDir Path folder)
      throws IOException {
    GameTables tables = tables(folder);

    // Without the Deflect: the gain of the completing shot on the Monk, the added damage at the
    // giver's level, which nothing reduces.
    Scene plainHit = play(tables, false, false);
    Scene enchantedHit = play(tables, true, false);
    assertThat(enchantedHit.losses().get(2).tick()).isEqualTo(plainHit.losses().get(2).tick());
    int added = enchantedHit.losses().get(2).monk() - plainHit.losses().get(2).monk();
    assertThat(added).as("the added damage of the third shot").isPositive();
    assertThat(enchantedHit.losses().get(0)).isEqualTo(plainHit.losses().get(0));
    assertThat(enchantedHit.losses().get(1)).isEqualTo(plainHit.losses().get(1));

    // With the Deflect: every shot is turned at the Monk and lands back on the Musketeer.
    Scene plain = play(tables, false, true);
    Scene enchanted = play(tables, true, true);
    assertThat(plain.deflections()).as("shots deflected").hasSizeGreaterThanOrEqualTo(3);
    assertThat(enchanted.deflections().subList(0, 3))
        .as("the same first three deflections")
        .isEqualTo(plain.deflections().subList(0, 3));
    // Losses come in pairs: the Monk's at the deflection, then the Musketeer's at the landing.
    for (int i = 0; i < 4; i++) {
      assertThat(enchanted.losses().get(i))
          .as("loss %d, the first two shots", i)
          .isEqualTo(plain.losses().get(i));
    }
    Loss deflection = enchanted.losses().get(4);
    Loss landing = enchanted.losses().get(5);
    assertThat(deflection.tick()).isEqualTo(enchanted.deflections().get(2));
    assertThat(deflection.tick()).isEqualTo(plain.losses().get(4).tick());
    assertThat(landing.tick()).isEqualTo(plain.losses().get(5).tick());
    assertThat(deflection.monk())
        .as("the Monk takes the third shot's added damage, through the Deflect's reduction")
        .isGreaterThan(plain.losses().get(4).monk());
    assertThat(landing.musketeer())
        .as("the Musketeer outlives the third shot turned back at it")
        .isLessThan(musketeerHitPoints(tables));
    assertThat(landing.musketeer())
        .as("the turned third shot lands with the added damage at the giver's level")
        .isEqualTo(plain.losses().get(5).musketeer() + added);
  }

  /**
   * Plays the scene and returns every step either unit lost hit points on, and the steps a shot was
   * deflected on.
   *
   * @param enchanted whether the buff is started on the Musketeer
   * @param deflect whether the Monk's ability is requested as it has deployed
   */
  private static Scene play(GameTables tables, boolean enchanted, boolean deflect) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    BattleWorld world = match.getWorld();
    List<Integer> deflections = new ArrayList<>();
    world.addObserver(
        new WorldObserver() {
          @Override
          public void projectileDeflected(
              int tick,
              AreaEffectEntity deflector,
              ProjectileEntity projectile,
              WorldEntity parent,
              WorldEntity source) {
            deflections.add(tick);
          }
        });
    CharacterEntity monk =
        match.deploy(0, world.getRecords().unit("Monk"), MONK_LEVEL, 0, 3500, 10000, "monk");
    CharacterEntity musketeer =
        match.deploy(0, world.getRecords().unit("Musketeer"), LEVEL, 1, 3500, 15500, "musketeer");
    if (enchanted) {
      musketeer
          .actionHolder()
          .start(
              world.getActions().build(ENCHANT, world.binding(musketeer)),
              musketeer.actionHolder());
    }
    int tick = 0;
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Monk deploys").isLessThan(TICKS);
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    if (deflect) {
      monk.requestAbility();
    }
    List<Loss> losses = new ArrayList<>();
    int monkBefore = monk.getHitPoints().getHitPoints();
    int musketeerBefore = musketeer.getHitPoints().getHitPoints();
    for (; tick < TICKS; tick++) {
      match.getBattle().step();
      int monkAfter = monk.getHitPoints().getHitPoints();
      int musketeerAfter = musketeer.getHitPoints().getHitPoints();
      if (monkAfter != monkBefore || musketeerAfter != musketeerBefore) {
        losses.add(new Loss(tick, monkBefore - monkAfter, musketeerBefore - musketeerAfter));
      }
      monkBefore = monkAfter;
      musketeerBefore = musketeerAfter;
    }
    return new Scene(losses, deflections);
  }

  /** The Musketeer's hit points less the two plain shots turned back at it before the third. */
  private static int musketeerHitPoints(GameTables tables) {
    Scene plain = play(tables, false, true);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity musketeer =
        match.deploy(
            0, match.getWorld().getRecords().unit("Musketeer"), LEVEL, 1, 3500, 15500, "musketeer");
    return musketeer.getHitPoints().getHitPoints()
        - plain.losses().get(1).musketeer()
        - plain.losses().get(3).musketeer();
  }

  /** The configured tables with the enchanting buff written. */
  private static GameTables tables(Path folder) throws IOException {
    GameData.copyConfigured(folder);
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode row = rows.putObject(ENCHANT);
          row.put("class", "LogicActionGiantBufferBuffData");
          row.put("ClassType", "ActionGiantBufferBuff");
          row.putObject("fields")
              .put("ClassType", "ActionGiantBufferBuff")
              .put("AttackAmount", 3)
              .put("AddedDamage", ADDED)
              .put("AddedCrownTowerDamage", ADDED)
              .put("FinishIfInstigatorDies", -1);
        });
    return GameTables.load(folder);
  }
}
