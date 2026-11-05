/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two projectile hits the battle core does not model are refused rather than played as something
 * else.
 *
 * <p>A projectile row with a RadiusY makes the area of its impact a box: its Radius across the
 * arena and its RadiusY along it. The impact here hits the circle of the Radius, so an impact that
 * would deal damage, push, apply a buff or make an area effect in that area is refused. A row whose
 * impact does none of these, as the Log's thrown projectile only spawns its rolling body, lands as
 * before.
 *
 * <p>A flying body runs its row's OnHitTargetAction on each object it hits on its way, once per
 * object; that run is not modelled, so a body with the action that meets an object on its way is
 * refused.
 *
 * <p>The scenes play the Log of the top side onto a Knight of the bottom side, with the Log's
 * thrown projectile or its rolling body written by the scene.
 */
class BattleProjectileUnmodelledHitTest {

  /** The first level, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** Where the Log is played, on the top side's half. */
  private static final int X = 3500;

  private static final int Y = 18000;

  /** How far in front of the Log's point, toward the bottom side, the Knight stands. */
  private static final int KNIGHT_AHEAD = 1500;

  /** The tick the Log is played on, once the Knight has deployed. */
  private static final int PLAYED = 40;

  /** The tick the scenes run to, long after the body has rolled over the Knight. */
  private static final int END = 200;

  /** The Log's thrown projectile and its rolling body, each written by a scene. */
  private static final String THROWN = "LogProjectile";

  private static final String BODY = "LogProjectileRolling";

  /** The area's half height along the arena's length the scenes write for the thrown projectile. */
  private static final int RADIUS_Y = 600;

  /** The action row the body's OnHitTargetAction names; the refusal comes before it is built. */
  private static final String HIT_ACTION = "TestBodyHitAction";

  /**
   * The configured tables with the thrown projectile's impact reduced to its rolling body, its area
   * a box, and one more change of the scene's.
   */
  private static GameTables written(Path folder, Consumer<GameRows> scene) throws IOException {
    Files.createDirectories(folder);
    return GameData.altered(
        folder,
        "projectiles",
        rows -> {
          GameRows written = new GameRows(rows);
          written
              .thrown()
              .put("RadiusY", RADIUS_Y)
              .remove(List.of("Damage", "Pushback", "TargetBuff", "SpawnAreaEffectObject"));
          scene.accept(written);
        });
  }

  /** The projectile rows a scene writes into. */
  private record GameRows(ObjectNode rows) {
    ObjectNode thrown() {
      return GameData.columns(rows, THROWN);
    }

    ObjectNode body() {
      return GameData.columns(rows, BODY);
    }
  }

  /** A battle with the bottom side's Knight standing in the body's way and the Log played. */
  private static Standard1v1Battle played(GameTables tables) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.deploy(0, records.unit("Knight"), LEVEL, 0, X, Y - KNIGHT_AHEAD);
    match.play(PLAYED, records.card("Log"), LEVEL, 1, X, Y, "Log");
    return match;
  }

  /** Steps the battle to the scenes' end, answering whether the rolling body ever rolled. */
  private static boolean stepToEnd(Standard1v1Battle match) {
    boolean rolled = false;
    while (match.getBattle().getTick() < END) {
      match.getBattle().step();
      for (BattleEntity entity : match.getBattle().getHolder().entities()) {
        rolled |=
            entity instanceof ProjectileEntity projectile
                && projectile.getData().name().equals(BODY);
      }
    }
    return rolled;
  }

  @Test
  @DisplayName("a projectile whose area is a box lands when its impact only spawns a projectile")
  void aBoxImpactThatOnlySpawnsLands(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = played(written(folder, rows -> {}));

    assertThat(stepToEnd(match)).as("the rolling body rolled").isTrue();
  }

  @Test
  @DisplayName("a projectile whose area is a box is refused when its impact deals damage")
  void aBoxImpactWithDamageIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = played(written(folder, rows -> rows.thrown().put("Damage", 100)));

    assertThatThrownBy(() -> stepToEnd(match))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(THROWN)
        .hasMessageContaining("RadiusY");
  }

  @Test
  @DisplayName("a projectile whose area is a box is refused when its impact pushes")
  void aBoxImpactWithPushbackIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = played(written(folder, rows -> rows.thrown().put("Pushback", 500)));

    assertThatThrownBy(() -> stepToEnd(match))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(THROWN)
        .hasMessageContaining("RadiusY");
  }

  @Test
  @DisplayName(
      "a flying body with an action on each object it hits is refused when it meets the Knight")
  void aBodyWithAHitTargetActionIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        played(written(folder, rows -> rows.body().put("OnHitTargetAction", HIT_ACTION)));

    assertThatThrownBy(() -> stepToEnd(match))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(BODY)
        .hasMessageContaining("OnHitTargetAction");
  }
}
