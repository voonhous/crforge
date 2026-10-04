package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Witch's soul drain: each skeleton she summons that dies sends its soul to her, and a
 * soul's flight later she heals by one hit of her heal buff, past her maximum hit points. The
 * Musketeer that shoots them dies too, and heals nothing.
 */
class BattleWitchEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Witch_Soul_Drain's ConstantFlightDuration 1000 ms, in ticks. */
  private static final int FLIGHT_TICKS = 20;

  @Test
  @DisplayName(
      "each of the evolved Witch's skeletons that dies heals her once, a soul's flight after its"
          + " death, and nothing else does")
  void herSkeletonsDeathsHealHer() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity witch = match.deploy(0, GameData.unit("Witch_EV1"), LEVEL, 0, 14500, 14000);
    match.deploy(50, GameData.unit("Musketeer"), LEVEL, 1, 14500, 21500);
    List<CharacterEntity> hers = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int t, SpawnHost source, CharacterEntity child, int x, int y) {
                if (source == witch) {
                  hers.add(child);
                }
              }
            });
    Set<CharacterEntity> dead = new HashSet<>();
    List<Integer> herDeaths = new ArrayList<>();
    int tick = 0;
    int max = witch.getHitPoints().getMaximum();
    int hp = witch.getHitPoints().getHitPoints();
    int peak = hp;
    List<Integer> heals = new ArrayList<>();
    List<Integer> amounts = new ArrayList<>();
    for (; tick < 1200 && !witch.isRemovable(); tick++) {
      match.getBattle().step();
      for (CharacterEntity skeleton : hers) {
        if (skeleton.getHitPoints().getHitPoints() <= 0 && dead.add(skeleton)) {
          herDeaths.add(tick);
        }
      }
      int now = witch.getHitPoints().getHitPoints();
      peak = Math.max(peak, now);
      if (now > hp) {
        heals.add(tick);
        amounts.add(now - hp);
      }
      hp = now;
    }

    assertThat(herDeaths).as("some of her skeletons die while she lives").isNotEmpty();
    assertThat(heals).as("one heal per death of hers").hasSameSizeAs(herDeaths);
    for (int i = 0; i < heals.size(); i++) {
      assertThat(heals.get(i) - herDeaths.get(i))
          .as("the heal comes a soul's flight after the death, and a tick for the heal buff")
          .isEqualTo(FLIGHT_TICKS + 1);
    }
    assertThat(amounts).as("one hit of the heal buff each").containsOnly(amounts.get(0));
    assertThat(peak).as("the heal goes past her maximum").isGreaterThan(max);
  }
}
