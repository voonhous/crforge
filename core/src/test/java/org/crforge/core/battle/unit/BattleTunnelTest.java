package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a unit that tunnels to its placement is while it tunnels, and what is refused of it. */
class BattleTunnelTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  /**
   * The configured tables with the buildings a dig surfaces beside written as the drill scenes
   * count on them - a princess tower of collision radius 1000 keeping 11 by 21 tiles closed, a king
   * tower keeping 18 by 16, the Goblin Drill and its evolved form of radius 500 reaching 2000 - the
   * buildings' rows then edited.
   */
  private static GameTables drillTables(Path folder, Consumer<ObjectNode> buildings)
      throws IOException {
    return GameData.altered(
        folder,
        "buildings",
        rows -> {
          GameData.columns(rows, "PrincessTower")
              .put("CollisionRadius", 1000)
              .put("NoDeploySizeW", 11)
              .put("NoDeploySizeH", 21);
          GameData.columns(rows, "KingTower").put("NoDeploySizeW", 18).put("NoDeploySizeH", 16);
          for (String drill : List.of("GoblinDrill", "GoblinDrill_EV1")) {
            GameData.columns(rows, drill)
                .put("CollisionRadius", 500)
                .put("Range", 2000)
                .put("SightRange", 2000);
          }
          buildings.accept(rows);
        });
  }

  /** Plays a Miner for the bottom side onto the top side's half and runs its play's step. */
  private static CharacterEntity playMiner(Standard1v1Battle match) {
    match.play(0, GameData.card("Miner"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 25500, "Miner");
    match.getBattle().step();
    return match.getPlays().get(0).units().get(0);
  }

  @Test
  @DisplayName("a tunnelling unit is hidden from the hand-over on, before its first state visit")
  void itIsHiddenFromTheHandOver() {
    Standard1v1Battle match = passiveTowers();
    match.play(0, GameData.card("Miner"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 25500, "Miner");
    List<Boolean> accepts = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void afterPrePass(int tick, List<WorldEntity> present) {
                for (WorldEntity entity : present) {
                  if (entity.name().equals("Miner_0")) {
                    accepts.add(entity.getTargetView().acceptsAttacker(new GridEntity(), false));
                  }
                }
              }
            });
    match.getBattle().step();

    // The play's command pass ran before this pre-pass: the towers' visits that follow see it
    // hidden already.
    assertThat(accepts).containsExactly(false);
  }

  @Test
  @DisplayName("a tunnelling unit starts on its own king, hidden, and surfaces deploying")
  void itTunnelsHiddenFromItsKing() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity miner = playMiner(match);
    assertThat(miner.untouchable()).as("every hit and buff passes it by").isTrue();

    assertThat(miner.getView().getState()).isEqualTo(GridEntityState.SPAWN_PATHFIND);
    assertThat(miner.hidden()).isTrue();
    assertThat(miner.getTargetView().acceptsAttacker(new GridEntity(), false))
        .as("no attacker takes it")
        .isFalse();
    // Its registration visit took the first step off the king at (9000, 3000).
    assertThat(miner.getView().getY()).isGreaterThan(3000);

    while (miner.getView().getState() == GridEntityState.SPAWN_PATHFIND) {
      match.getBattle().step();
    }
    assertThat(miner.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(miner.hidden()).isFalse();
    assertThat(miner.getTargetView().acceptsAttacker(new GridEntity(), false)).isTrue();
    assertThat(miner.getView().getX())
        .as("on the placed point")
        .isEqualTo(match.getPlays().get(0).result().x());
  }

  @Test
  @DisplayName(
      "a tunnelling unit is untouchable to the bookkeeping too: it takes no damage over time and"
          + " no typed hit")
  void itTakesNoDamageOverTimeInItsTunnel() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity miner = playMiner(match);
    int before = miner.getHitPoints().getHitPoints();

    assertThat(miner.takeDamageOverTime(100, null)).isEqualTo(DamageResult.NOTHING);
    assertThat(miner.takeTypedHit(null, 100, 0, 0, 1)).isEqualTo(DamageResult.NOTHING);
    assertThat(miner.getHitPoints().getHitPoints()).isEqualTo(before);
  }

  @Test
  @DisplayName("a hit's buff on damage passes over a tunnelling unit and reaches it surfaced")
  void aBuffOnDamagePassesItOver() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity miner = playMiner(match);
    CharacterEntity sparky =
        match.deploy(
            1, GameData.unit("MiniZapMachine"), Standard1v1Battle.DEFAULT_LEVEL, 1, 3500, 20000);

    match.getWorld().buffOnDamage(sparky, miner);
    assertThat(miner.getBuffs().carries("ZapFreeze")).isFalse();

    while (miner.getView().getState() == GridEntityState.SPAWN_PATHFIND) {
      match.getBattle().step();
    }
    match.getWorld().buffOnDamage(sparky, miner);
    assertThat(miner.getBuffs().carries("ZapFreeze")).isTrue();
  }

  @Test
  @DisplayName("the damage entry refuses a tunnelling unit")
  void theDamageEntryRefusesIt() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity miner = playMiner(match);
    int before = miner.getHitPoints().getHitPoints();

    assertThat(miner.takeDamage(100, 0, 0, 1)).isEqualTo(DamageResult.NOTHING);
    assertThat(miner.getHitPoints().getHitPoints()).isEqualTo(before);
  }

  @Test
  @DisplayName(
      "an area effect of the hit-switch form that reaches hidden units reaching a tunnelling one is"
          + " refused")
  void anAreaReachingHiddenUnitsIsRefused(@TempDir Path folder) throws IOException {
    // The configured Freeze chooses by a filter that leaves out underground units; written with
    // its hit switches and AffectsHidden, it reaches them.
    GameTables tables =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> {
              ObjectNode columns = GameData.columns(rows, "Freeze");
              columns.remove("Filter");
              columns.put("Damage", 58);
              columns.put("AffectsHidden", true);
              columns.put("HitsAir", true);
              columns.put("HitsGround", true);
              columns.put("OnlyEnemies", true);
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    match.play(
        0,
        match.getWorld().getRecords().card("Miner"),
        Standard1v1Battle.DEFAULT_LEVEL,
        0,
        3500,
        25500,
        "Miner");
    match.getBattle().step();
    CharacterEntity miner = match.getPlays().get(0).units().get(0);
    // A Freeze of the top side cast where the Miner walks.
    match.placeAreaEffect(
        1,
        "Freeze",
        Standard1v1Battle.DEFAULT_LEVEL,
        1,
        miner.getView().getX(),
        miner.getView().getY(),
        "Freeze");

    assertThatThrownBy(
            () -> {
              for (int step = 0; step < 5; step++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("reaches hidden units");
  }

  @Test
  @DisplayName(
      "a dig that surfaces morphs into its building, deploying, with the dig's share of hit"
          + " points")
  void theDigMorphsIntoItsBuilding(@TempDir Path folder) throws IOException {
    // The building written as the scene counts on it: 1313 hit points, its own at the first
    // level, a life of 10000 ms, a deploy time of 1000.
    Standard1v1Battle match =
        new Standard1v1Battle(
            drillTables(
                folder,
                rows ->
                    GameData.columns(rows, "GoblinDrill")
                        .put("Hitpoints", 1313)
                        .put("LifeTime", 10000)
                        .put("DeployTime", 1000)),
            Standard1v1Battle.DEFAULT_LEVEL,
            false);
    List<String> made = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void morphed(int tick, CharacterEntity old, CharacterEntity building) {
                made.add(
                    old.name()
                        + " "
                        + building.name()
                        + " "
                        + building.getView().getState()
                        + " "
                        + building.getView().getDeployCountdown()
                        + " "
                        + building.getHitPoints().getHitPoints()
                        + " facing "
                        + building.getView().getDirX()
                        + " "
                        + building.getView().getDirY());
              }

              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity areaEffect, String how, String source) {
                made.add(how + " " + source);
              }
            });
    match.play(0, match.getWorld().getRecords().card("GoblinDrill"), 1, 0, 3500, 25500, "Drill");
    CharacterEntity dig = null;
    for (int step = 0; step < 100 && made.isEmpty(); step++) {
      match.getBattle().step();
      if (dig == null) {
        dig = match.getPlays().get(0).units().get(0);
      }
    }

    // The building's registration visit took one LifeTime step, 1313 * 50 / 10000, off its 1313
    // before it was set deploying. Its row makes its damage area by its starting action, not in the
    // entry.
    assertThat(made)
        .containsExactly(
            // The building's registration visit attacks the princess tower it surfaced beside, and
            // an attack tick turns it toward its reference: (2500, -500) scaled to 256.
            "Drill_0 Drill_0_GoblinDrill 4 1000 1307 facing 251 -50");
    assertThat(dig.getView().getX()).as("on the searched corner").isEqualTo(1000);
    match.getBattle().step();
    assertThat(match.getBattle().getHolder().entities()).doesNotContain(dig);
  }

  @Test
  @DisplayName(
      "a dig whose morph's row starts an action, the evolved Goblin Drill's relocation, has it"
          + " started at the fold: its first-appear area is made in the next tick's pending pass")
  void aMorphStartsItsRowsActionAtTheFold(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(
            drillTables(folder, rows -> {}), Standard1v1Battle.DEFAULT_LEVEL, false);
    match.play(
        0,
        match.getWorld().getRecords().card("GoblinDrill_EV1"),
        Standard1v1Battle.DEFAULT_LEVEL,
        0,
        14000,
        23000,
        "Drill");
    CharacterEntity drill = null;
    for (int step = 0; step < 200 && drill == null; step++) {
      match.getBattle().step();
      drill = BattleGoblinDrillEvoTest.drill(match);
    }
    assertThat(drill).as("the dig surfaced").isNotNull();
    assertThat(BattleGoblinDrillEvoTest.areas(match, "GoblinDrillDamageArea")).isZero();

    match.getBattle().step();

    assertThat(BattleGoblinDrillEvoTest.areas(match, "GoblinDrillDamageArea")).isEqualTo(1);
  }
}
