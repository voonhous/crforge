package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a request for a unit's ability does, and what it refuses. */
class BattleAbilityTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  @Test
  @DisplayName(
      "a request while the unit deploys is left pending and cast on the visit after the deploy")
  void aRequestWhileDeployingWaits() {
    Standard1v1Battle match = passiveTowers();
    // A Giant Buffer alone: its collector finds no friend, so only this request casts.
    CharacterEntity buffer =
        match.deploy(
            0, GameData.unit("GiantBuffer"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 9500);
    List<String> log = new ArrayList<>();
    int[] tick = {0};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void abilityRequested(int t, CharacterEntity unit, boolean now) {
                log.add(t + " requested " + (now ? "now" : "pending"));
              }

              @Override
              public void abilityFired(int t, CharacterEntity unit) {
                log.add(t + " fired");
              }
            });
    for (; tick[0] < 10; tick[0]++) {
      match.getBattle().step();
    }
    buffer.requestAbility();
    assertThat(buffer.getUnit().timers().isAbilityReady()).as("pending").isTrue();

    // The deploy ends in a visit whose own gate still finds the targeting component off.
    int deployEnd = -1;
    while (deployEnd < 0) {
      match.getBattle().step();
      tick[0]++;
      if (buffer.getView().getState() != GridEntityState.DEPLOYING) {
        deployEnd = tick[0];
      }
    }
    assertThat(buffer.getView().getState()).isNotEqualTo(GridEntityState.CASTING);

    match.getBattle().step();
    tick[0]++;
    assertThat(buffer.getView().getState()).as("cast on the next visit").isEqualTo(10);
    assertThat(buffer.getUnit().timers().isAbilityReady()).isFalse();
    int cast = tick[0];

    // It casts for its 933 ms, eighteen visits counting the one it entered in.
    while (buffer.getView().getState() == GridEntityState.CASTING) {
      match.getBattle().step();
      tick[0]++;
    }
    assertThat(tick[0] - cast).isEqualTo(17);
    // The battle numbers its first step 0, so the observer's ticks run one behind the steps: the
    // request came after the tenth step, and the effect fired on the visit the cast began.
    assertThat(log).containsExactly("9 requested pending", (cast - 1) + " fired");
  }

  @Test
  @DisplayName(
      "a collector's lock is granted after the phase-3 pass and dropped once its friend has died")
  void aLockGoesWithItsFriend() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 12500);
    CharacterEntity buffer =
        match.deploy(
            0, GameData.unit("GiantBuffer"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 9500);
    // The collector first looks on the twenty-first step, and the locks grant in its post-pass.
    for (int step = 0; step < 21; step++) {
      match.getBattle().step();
    }
    BattleWorld world = match.getWorld();
    assertThat(world.locks().claim(buffer.getId(), knight.getId(), 1)).isTrue();

    world.kill(knight, null);
    // The death's cleanup takes it out of the live list; the next pre-pass drops its lock.
    match.getBattle().step();
    match.getBattle().step();

    assertThat(world.locks().claim(buffer.getId(), knight.getId(), 1)).isFalse();
  }

  @Test
  @DisplayName(
      "a collector whose filter names a row the tables do not hold finds no friend and fires at"
          + " none")
  void aCollectorWithAMissingFilterFindsNoFriend(@TempDir Path folder) throws IOException {
    // The collector's filter renamed to a row no filter table holds, as a newer data version
    // ships it; the game's loader reads the name as no filter and the battle runs on.
    GameTables tables =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("giantbuffer_collect_friend_troops").get("fields"))
                    .put("TargetFilter", "friendly_troops_for_rune_giant"));
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    CharacterEntity knight =
        match.deploy(0, records.unit("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 12500);
    CharacterEntity buffer =
        match.deploy(
            0, records.unit("GiantBuffer"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 9500);
    List<String> abilities = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void abilityRequested(int t, CharacterEntity unit, boolean now) {
                abilities.add(t + " requested");
              }
            });
    BattleWorld world = match.getWorld();
    // With the shipped filter the Knight is asked for on the twenty-first step and buffed by a
    // projectile soon after; here the query lists nothing on every step.
    int launched = 0;
    for (int step = 0; step < 200; step++) {
      match.getBattle().step();
      launched += world.projectiles().size();
    }

    assertThat(world.locks().claim(buffer.getId(), knight.getId(), 1)).isFalse();
    assertThat(launched).as("projectile visits").isZero();
    assertThat(abilities).isEmpty();
  }

  @Test
  @DisplayName("a request for an ability with an effect the battle does not model is refused")
  void aRichAbilityIsRefused(@TempDir Path folder) throws IOException {
    // The Monk's ability given a morph, which no shipped ability has.
    GameTables tables =
        GameData.altered(
            folder,
            "character_abilities",
            rows -> GameData.columns(rows, "Deflect").put("MorphTarget", "Knight"));
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    CharacterEntity monk =
        match.deploy(
            0,
            new BattleRecords(tables).unit("Monk"),
            Standard1v1Battle.DEFAULT_LEVEL,
            0,
            3500,
            9500);

    assertThatThrownBy(monk::requestAbility)
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "Monk casts Deflect, which sets columns the battle does not model: [MorphTarget]");
  }
}
