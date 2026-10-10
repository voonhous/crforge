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
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Skeleton Army: its card names its soldier SummonNumber times (fifteen) and lists its
 * general besides, so the play makes the soldiers in their ring and then the general at its own
 * offset, all linked into one chain as the card is a group. A soldier that dies while the general
 * is in the chain leaves a spectral skeleton where it stood, which joins the chain; the general's
 * death kills the spectral skeletons of its chain.
 */
class BattleSkeletonArmyEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String SOLDIER = "SkeletonArmy_EV1_Soldier";

  private static final String GENERAL = "SkeletonArmy_EV1_General";

  private static final String SPECTRAL = "SkeletonArmy_EV1_Spectral";

  /** The evolved card's row. */
  private static final GameRow CARD = Shipped.row("spells_evolved", "SkeletonArmy_EV1");

  /** The card's soldiers, and the play's units: the soldiers and the general. */
  private static final int SOLDIERS = Shipped.number(CARD, "SummonNumber");

  private static final int UNITS = SOLDIERS + 1;

  /** The army played on tick 0 at (3500, 10000), with the towers passive. */
  private static Standard1v1Battle played(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    DeployCard card = match.getWorld().getRecords().card("SkeletonArmy_EV1");
    match.play(0, card, LEVEL, 0, 3500, 10000, "army");
    return match;
  }

  /** A Musketeer of the other side that never walks. */
  private static void musketeer(Standard1v1Battle match, int x, int y) {
    CharacterEntity unit =
        match.deploy(
            0, match.getWorld().getRecords().unit("Musketeer"), LEVEL, 1, x, y, "musketeer");
    unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
  }

  /** Steps until a unit of the row is alive, at most the given ticks. */
  private static List<CharacterEntity> stepUntil(Standard1v1Battle match, String row, int ticks) {
    for (int i = 0; i < ticks && living(match, row).isEmpty(); i++) {
      match.getBattle().step();
    }
    return living(match, row);
  }

  private static List<CharacterEntity> living(Standard1v1Battle match, String row) {
    List<CharacterEntity> found = new ArrayList<>();
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof CharacterEntity unit
          && unit.getData().name().equals(row)
          && unit.getView().isAlive()) {
        found.add(unit);
      }
    }
    return found;
  }

  @Test
  @DisplayName(
      "the play makes its SummonNumber of soldiers and then the general, the general at its listed"
          + " offset negated for the bottom side, all of them in one chain in creation order")
  void theGeneralFollowsItsSoldiers() {
    Standard1v1Battle match = played(GameData.tables());
    match.getBattle().step();
    Standard1v1Battle.Play play = match.getPlays().get(0);

    List<CharacterEntity> units = play.units();
    assertThat(units).hasSize(UNITS);
    assertThat(units.subList(0, SOLDIERS)).allMatch(unit -> unit.getData().name().equals(SOLDIER));
    assertThat(units.get(SOLDIERS).getData().name()).isEqualTo(GENERAL);

    // The listed offset, both of its parts negated for the bottom side's play.
    int dx = -Shipped.numbers(CARD, "SummonCharactersOffsetsX").get(0);
    int dy = -Shipped.numbers(CARD, "SummonCharactersOffsetsY").get(0);
    CardPlacement.Unit general = play.result().units().get(SOLDIERS);
    assertThat(List.of(general.dx(), general.dy())).containsExactly(dx, dy);
    assertThat(List.of(general.x(), general.y()))
        .containsExactly(play.result().x() + dx, play.result().y() + dy);

    assertThat(units.get(0).chainSize()).isEqualTo(UNITS);
    for (int k = 0; k < SOLDIERS; k++) {
      assertThat(units.get(k).chainNext()).isSameAs(units.get(k + 1));
    }
  }

  @Test
  @DisplayName(
      "a soldier killed while the general is in its chain leaves a spectral skeleton where it"
          + " stood, linked into the chain")
  void aSoldierLeavesASpectral() {
    Standard1v1Battle match = played(GameData.tables());
    musketeer(match, 3500, 16000);

    List<CharacterEntity> spectrals = stepUntil(match, SPECTRAL, 400);

    assertThat(spectrals).hasSize(1);
    CharacterEntity spectral = spectrals.get(0);
    assertThat(spectral.side()).isZero();
    List<CharacterEntity> dead =
        match.getPlays().get(0).units().stream().filter(unit -> !unit.getView().isAlive()).toList();
    assertThat(dead).hasSize(1);
    assertThat(dead.get(0).getData().name()).isEqualTo(SOLDIER);
    // Made where the soldier stood, then pushed apart from its neighbours in the same tick.
    assertThat(Math.abs(spectral.getView().getX() - dead.get(0).getView().getX())).isLessThan(500);
    assertThat(Math.abs(spectral.getView().getY() - dead.get(0).getView().getY())).isLessThan(500);
    CharacterEntity general = match.getPlays().get(0).units().get(SOLDIERS);
    assertThat(general.getView().isAlive()).isTrue();
    assertThat(general.chainSize()).isEqualTo(UNITS);
  }

  @Test
  @DisplayName(
      "once the general has died, a soldier's death leaves no spectral skeleton: the death effect"
          + " alone")
  void noSpectralWithoutTheGeneral(@TempDir Path folder) throws IOException {
    // A general with one hit point and no shield, nearest to a Musketeer right behind the army
    // that the soldiers cannot kill.
    GameTables frail =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              GameData.columns(rows, GENERAL).put("Hitpoints", 1).put("ShieldHitpoints", 0);
              GameData.columns(rows, "Musketeer").put("Hitpoints", 1000000);
            });
    Standard1v1Battle match = played(frail);
    musketeer(match, 3500, 8400);
    CharacterEntity general = null;
    for (int i = 0; i < 400; i++) {
      match.getBattle().step();
      assertThat(living(match, SPECTRAL)).isEmpty();
      List<CharacterEntity> units =
          match.getPlays().isEmpty() ? List.of() : match.getPlays().get(0).units();
      general = units.size() == UNITS ? units.get(SOLDIERS) : null;
      if (general != null && !general.getView().isAlive()) {
        break;
      }
    }
    assertThat(general).isNotNull();
    assertThat(general.getView().isAlive()).isFalse();
    int soldiers = living(match, SOLDIER).size();
    // The Musketeer goes on to kill soldiers; none leaves a spectral skeleton.
    assertThat(soldiers).isEqualTo(SOLDIERS);
    for (int i = 0; i < 300 && living(match, SOLDIER).size() == soldiers; i++) {
      match.getBattle().step();
      assertThat(living(match, SPECTRAL)).isEmpty();
    }
    assertThat(living(match, SOLDIER).size()).isLessThan(soldiers);
    assertThat(living(match, SPECTRAL)).isEmpty();
  }
}
