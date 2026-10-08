package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the battle makes of a row's charge, and what it refuses of it. */
class BattleChargeTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  @Test
  @DisplayName("a row with a charge range starts tracking its charge at 0, one without tracks none")
  void aChargeStartsAtZero() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity prince = match.deploy(0, GameData.unit("Prince"), 11, 0, 3500, 10000);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), 11, 0, 14500, 10000);

    assertThat(prince.getUnit().movement().getChargeProgress()).isZero();
    assertThat(knight.getUnit().movement().getChargeProgress())
        .isEqualTo(MovementState.CHARGE_INACTIVE);
  }

  @Test
  @DisplayName("a Kamikaze row's hit destroys it in the tick it lands")
  void aKamikazeHitDestroysTheUnit() {
    Standard1v1Battle match = passiveTowers();
    // A Battle Ram just short of the red princess tower, which it walks to and hits.
    CharacterEntity ram = match.deploy(0, GameData.unit("BattleRam"), 11, 0, 3500, 22000);

    int tick = 0;
    while (ram.getHitPoints().getHitPoints() > 0 && tick < 200) {
      match.getBattle().step();
      tick++;
    }
    // The tick its hit landed on the tower is the tick it killed itself.
    WorldEntity tower =
        match.getWorld().present().stream()
            .filter(e -> e.name().equals("PrincessTower_1_1"))
            .findFirst()
            .orElseThrow();
    assertThat(ram.getHitPoints().getHitPoints()).isZero();
    assertThat(tower.getHitPoints().getHitPoints()).isLessThan(tower.getHitPoints().getMaximum());
  }

  /**
   * The configured tables with the evolved Battle Ram's charge and push, and the Knight it pushes,
   * as the test writes them (the values of data version 16.402.18): the ram walks 60 and charges at
   * 200 percent of it, its push waits 825 ms, is centred 800 ahead of it with a radius of 1000,
   * pushes 2000 and deals 83 at the first level; the Knight has a radius of 500 and a mass of 6,
   * deploys for 1000 ms and walks 60.
   */
  private static GameTables pushTables(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode push = (ObjectNode) rows.get("BattleRam_EV1_PushBack").get("fields");
          push.put("ActionDelay", 825);
          push.put("PushBackDamage", 83);
          push.put("PushBackRadius", 1000);
          push.put("PushBackStrength", 2000);
          push.put("PushRadiusDirectionalOffset", 800);
          push.put("OnPushEffectMinInterval", 1000);
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          ObjectNode ram = GameData.columns(rows, "BattleRam_EV1");
          ram.put("OnStartChargingAction", "BattleRam_EV1_PushBack");
          ram.put("Rarity", "Common");
          ram.put("Speed", 60);
          ram.put("ChargeSpeedMultiplier", 200);
          ram.put("ChargeRange", 300);
          ObjectNode knight = GameData.columns(rows, "Knight");
          knight.put("CollisionRadius", 500);
          knight.put("Mass", 6);
          knight.put("DeployTime", 1000);
          knight.put("Speed", 60);
        });
    return GameTables.load(folder);
  }

  /**
   * Steps an evolved Battle Ram until its charge completes, then places an enemy Knight beside the
   * point the push pass will be centred on 16 steps later, the action's delay of 825 ms in whole
   * steps. Answers the tick the charge completed on; fills in the Knight, and its hit points and
   * position along the width after each of the next 30 steps.
   */
  private static int chargeThenKnight(
      Standard1v1Battle match,
      BattleRecords records,
      CharacterEntity ram,
      CharacterEntity[] knight,
      int[] hitPoints,
      int[] widths) {
    int completed = -1;
    for (int tick = 0; tick < 200 && completed < 0; tick++) {
      match.getBattle().step();
      if (ram.getUnit().movement().getChargeProgress() >= MovementState.CHARGE_COMPLETE) {
        completed = match.getWorld().tick();
      }
    }
    assertThat(completed).isPositive();
    // The ram walks 120 units a step once charged; the push pass is centred 800 ahead of it, with
    // a radius of 1000. The Knight, still deploying, stands 300 to the side of that centre.
    int y = ram.getView().getY() + 16 * 120 + 800;
    knight[0] =
        match.deploy(completed + 1, records.unit("Knight"), 3, 1, ram.getView().getX() + 300, y);
    for (int i = 0; i < 30; i++) {
      match.getBattle().step();
      hitPoints[i] =
          knight[0].getHitPoints() == null ? -1 : knight[0].getHitPoints().getHitPoints();
      widths[i] = knight[0].getView().getX();
    }
    return completed;
  }

  @Test
  @DisplayName(
      "an evolved Battle Ram's completed charge runs its push 825 ms later: an enemy beside its"
          + " path is pushed to the side and hit once for the push damage")
  void theEvolvedRamPushesAndHitsOnce(@TempDir Path folder) throws IOException {
    GameTables tables = pushTables(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    // Level 3, as a Rare card at level index 0 plays it.
    CharacterEntity ram = match.deploy(0, records.unit("BattleRam_EV1"), 3, 0, 14500, 9000);
    CharacterEntity[] knight = new CharacterEntity[1];
    int[] hitPoints = new int[30];
    int[] widths = new int[30];
    int completed = chargeThenKnight(match, records, ram, knight, hitPoints, widths);

    int full = knight[0].getHitPoints().getMaximum();
    // Index i holds the hit points after the step of tick completed + 1 + i; the push pass first
    // runs on tick completed + 16.
    int first = 15;
    for (int i = 0; i < first; i++) {
      assertThat(hitPoints[i]).as("after step %d", i).isEqualTo(full);
    }
    // PushBackDamage 83 at level 3 of the character row's Common rarity, 83 * 121 / 100, and only
    // once, though the Knight stays inside the pass for several steps.
    for (int i = first; i < 30; i++) {
      assertThat(hitPoints[i]).as("after step %d", i).isEqualTo(full - 100);
    }
    // The push carries it away from the ram's line, to the side it stood on.
    assertThat(widths[first + 5]).isGreaterThan(widths[first - 1] + 500);
  }

  @Test
  @DisplayName(
      "the evolved Battle Ram's push run goes on through its recoil and ends with its charge")
  void thePushRunEndsWithTheCharge() {
    Standard1v1Battle match = passiveTowers();
    // Just short of the red princess tower: it charges, hits the tower and keeps hitting it.
    CharacterEntity ram = match.deploy(0, GameData.unit("BattleRam_EV1"), 11, 0, 14500, 17000);
    boolean ran = false;
    for (int tick = 0; tick < 200; tick++) {
      match.getBattle().step();
      boolean running =
          ram.actionHolder().running().stream()
              .anyMatch(r -> r.getAction().name().equals("BattleRam_EV1_PushBack"));
      ran |= running;
      if (ran && ram.getView().getState() == GridEntityState.ATTACKING) {
        break;
      }
    }
    assertThat(ran).isTrue();
    // Its hit recoils it and it keeps its charge, so the run goes on through the recoil.
    for (int tick = 0; tick < 5; tick++) {
      match.getBattle().step();
    }
    assertThat(ram.actionHolder().running())
        .anyMatch(r -> r.getAction().name().equals("BattleRam_EV1_PushBack"));
    // A Zap's stun takes its charge away once the recoil has flown: no charged displacement
    // follows, so the stop gate holds.
    int tick = match.getWorld().tick() + 1;
    match.placeAreaEffect(tick, "Zap", 11, 1, ram.getView().getX(), ram.getView().getY(), "zap");
    for (int i = 0; i < 20; i++) {
      match.getBattle().step();
    }
    assertThat(ram.actionHolder().running())
        .noneMatch(r -> r.getAction().name().equals("BattleRam_EV1_PushBack"));
  }

  @Test
  @DisplayName(
      "an evolved Battle Ram's direct hit on a tower recoils it by its AttackPushBack, away from"
          + " where the tower stood, in the tick the hit lands")
  void theEvolvedRamRecoilsAfterItsHit() {
    Standard1v1Battle match = passiveTowers();
    // Just short of the red princess tower on the right lane: it charges and hits the tower.
    CharacterEntity ram = match.deploy(0, GameData.unit("BattleRam_EV1"), 11, 0, 14500, 17000);
    // The towers are present from the first step on.
    match.getBattle().step();
    WorldEntity tower =
        match.getWorld().present().stream()
            .filter(e -> e.side() == 1 && e.name().startsWith("PrincessTower"))
            .filter(e -> e.getView().getX() > 9000)
            .findFirst()
            .orElseThrow();
    // Every pushback asked for the ram: the tick, whether it started, and the point it is pushed
    // away from.
    List<int[]> recoils = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void pushbackRequested(
                  int t,
                  WorldEntity unit,
                  boolean started,
                  int fromX,
                  int fromY,
                  MovementState pushback) {
                if (unit == ram) {
                  recoils.add(new int[] {t, started ? 1 : 0, fromX, fromY});
                }
              }
            });
    int full = tower.getHitPoints().getHitPoints();
    int hitTick = -1;
    int hitY = 0;
    for (int tick = 0; tick < 200 && hitTick < 0; tick++) {
      match.getBattle().step();
      if (tower.getHitPoints().getHitPoints() < full) {
        hitTick = match.getWorld().tick();
        hitY = ram.getView().getY();
      }
    }
    assertThat(hitTick).as("the ram's first hit on the tower").isPositive();

    // One request, in the hit's tick, started, away from the tower it hit.
    assertThat(recoils).hasSize(1);
    assertThat(recoils.get(0))
        .containsExactly(hitTick, 1, tower.getView().getX(), tower.getView().getY());
    MovementState movement = ram.getUnit().movement();
    assertThat(movement.getPushbackInFlight()).isEqualTo(1);
    assertThat(movement.getAttackPushback()).as("an attack's pushback").isEqualTo(1);
    // The pushback flies it back along the lane, away from the tower ahead of it.
    for (int i = 0; i < 10; i++) {
      match.getBattle().step();
    }
    assertThat(ram.getView().getY()).isLessThan(hitY - 1000);
  }
}
