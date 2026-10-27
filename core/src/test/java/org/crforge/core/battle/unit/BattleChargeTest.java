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
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.target.TargetingState;
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
   * as the test writes them: the ram walks 60 and charges at 200 percent of it, its push waits 825
   * ms, is centred 800 ahead of it with a radius of 1000, pushes 2000 and deals 83 at the first
   * level; the Knight has a radius of 500 and a mass of 6, deploys for 1000 ms and walks 60.
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
    return chargeThen(match, records, ram, "Knight", knight, hitPoints, widths);
  }

  /**
   * As {@link #chargeThenKnight}, with an enemy of the named row placed where the Knight is.
   *
   * @param row the row of the enemy placed beside the ram's path
   */
  private static int chargeThen(
      Standard1v1Battle match,
      BattleRecords records,
      CharacterEntity ram,
      String row,
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
    knight[0] = match.deploy(completed + 1, records.unit(row), 3, 1, ram.getView().getX() + 300, y);
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

  /**
   * The push tables with the push's damage written as the 16.635.1 data writes it, an inline damage
   * type of the same base damage flagged NoCounter.
   */
  private static GameTables inlinePushDamageTables(Path folder) throws IOException {
    pushTables(folder);
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode damage =
              GameData.fields(rows, "BattleRam_EV1_PushBack").putObject("PushBackDamage");
          damage.put("BaseDamage", 83);
          damage.put("Flags", "NoCounter");
        });
    return GameTables.load(folder);
  }

  /**
   * Runs the evolved ram's push past an enemy Ronin beside its path. Answers the Ronin's hit points
   * after each of the 30 steps after it is placed, its full hit points last, and fills in the buffs
   * the ram was given.
   */
  private static int[] pushPastRonin(GameTables tables, List<String> ramBuffs) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    CharacterEntity ram = match.deploy(0, records.unit("BattleRam_EV1"), 3, 0, 14500, 9000);
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffApplied(int at, WorldEntity target, BuffInstance buff) {
                if (target == ram) {
                  ramBuffs.add(buff.getBuff().name());
                }
              }
            });
    CharacterEntity[] ronin = new CharacterEntity[1];
    int[] hitPoints = new int[31];
    chargeThen(match, records, ram, "Ronin", ronin, hitPoints, new int[30]);
    hitPoints[30] = ronin[0].getHitPoints().getMaximum();
    return hitPoints;
  }

  @Test
  @DisplayName(
      "the evolved Battle Ram's push damage written as a whole number is countered by a Ronin's"
          + " parry, as any hit is")
  void aWholeNumberPushIsParried(@TempDir Path folder) throws IOException {
    List<String> ramBuffs = new ArrayList<>();
    int[] hitPoints = pushPastRonin(pushTables(folder), ramBuffs);
    for (int i = 0; i < 30; i++) {
      assertThat(hitPoints[i]).as("the Ronin after step %d", i).isEqualTo(hitPoints[30]);
    }
    assertThat(ramBuffs)
        .as("the parry's reflect stuns the ram")
        .contains("ronin_reflect_stun_buff");
  }

  @Test
  @DisplayName(
      "the evolved Battle Ram's push damage written as an inline damage type flagged NoCounter"
          + " deals its base damage at the ram's level and is not countered by a Ronin's parry")
  void aNoCounterPushIsNotParried(@TempDir Path folder) throws IOException {
    List<String> ramBuffs = new ArrayList<>();
    int[] hitPoints = pushPastRonin(inlinePushDamageTables(folder), ramBuffs);
    // Index i holds the hit points after the step of tick completed + 1 + i; the push pass first
    // runs on tick completed + 16. BaseDamage 83 at level 3 of the Common rarity, 83 * 121 / 100,
    // as the whole number deals it.
    for (int i = 0; i < 15; i++) {
      assertThat(hitPoints[i]).as("the Ronin after step %d", i).isEqualTo(hitPoints[30]);
    }
    for (int i = 15; i < 30; i++) {
      assertThat(hitPoints[i]).as("the Ronin after step %d", i).isEqualTo(hitPoints[30] - 100);
    }
    assertThat(ramBuffs).doesNotContain("ronin_reflect_stun_buff");
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

  /** The global that makes a stun reset a charge while a pushback flies; 16.402 tables had none. */
  private static final String CHARGE_FIX =
      "FIX_RESET_CHARGE_DURING_PUSHBACK_WHEN_MOVEMENT_DISABLED";

  /**
   * The configured tables with the global that resets a charge during a pushback while movement is
   * disabled published, set as the test asks: its row's value changed where the tables publish it,
   * else a row added for it.
   */
  private static GameTables withChargeFix(Path folder, boolean set) throws IOException {
    return GameData.altered(
        folder,
        "globals",
        rows -> {
          if (rows.has(CHARGE_FIX)) {
            ((ObjectNode) rows.get(CHARGE_FIX).get("columns")).put("BooleanValue", set);
            return;
          }
          int index = rows.size();
          ObjectNode row = rows.putObject(CHARGE_FIX);
          row.put("index", index);
          row.put("class", "LogicData");
          ObjectNode columns = row.putObject("columns");
          columns.put("Name", CHARGE_FIX);
          columns.put("BooleanValue", set);
        });
  }

  /** The configured tables with no charge fix published, as the 16.402 tables had none. */
  private static GameTables withoutChargeFix(Path folder) throws IOException {
    return GameData.altered(folder, "globals", rows -> rows.remove(CHARGE_FIX));
  }

  /**
   * Walks an evolved Battle Ram into the red princess tower on the right lane until its first hit
   * recoils it, then drops an enemy Zap on it the next tick. Answers, for each of the next 20
   * steps, whether a pushback was in flight after the step, whether it was the attack's own, the
   * charge progress and whether the Zap's stun was listed on the ram. The stun is listed after the
   * movement pass of the step it lands in, so the movement pass of the next step is the first to
   * find it.
   */
  private static List<int[]> zapInTheRecoil(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    BattleRecords records = new BattleRecords(tables);
    CharacterEntity ram = match.deploy(0, records.unit("BattleRam_EV1"), 11, 0, 14500, 17000);
    match.getBattle().step();
    WorldEntity tower =
        match.getWorld().present().stream()
            .filter(e -> e.side() == 1 && e.name().startsWith("PrincessTower"))
            .filter(e -> e.getView().getX() > 9000)
            .findFirst()
            .orElseThrow();
    int full = tower.getHitPoints().getHitPoints();
    boolean hit = false;
    for (int tick = 0; tick < 200 && !hit; tick++) {
      match.getBattle().step();
      hit = tower.getHitPoints().getHitPoints() < full;
    }
    assertThat(hit).as("the ram's first hit on the tower").isTrue();
    MovementState movement = ram.getUnit().movement();
    assertThat(movement.getPushbackInFlight()).as("its recoil flies").isEqualTo(1);
    assertThat(movement.getChargeProgress()).isGreaterThanOrEqualTo(MovementState.CHARGE_COMPLETE);
    match.placeAreaEffect(
        match.getWorld().tick() + 1,
        "Zap",
        11,
        1,
        ram.getView().getX(),
        ram.getView().getY(),
        "zap");
    List<int[]> steps = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      match.getBattle().step();
      boolean stunned =
          ram.getBuffs().items().stream().anyMatch(b -> b.getBuff().speedMultiplier() <= -100);
      steps.add(
          new int[] {
            movement.getPushbackInFlight(),
            movement.getAttackPushback(),
            movement.getChargeProgress(),
            stunned ? 1 : 0
          });
    }
    return steps;
  }

  @Test
  @DisplayName(
      "under the published charge fix, a stun on the evolved Battle Ram while its recoil flies"
          + " takes its charge at once, before the recoil has flown")
  void aStunInTheRecoilTakesTheCharge(@TempDir Path folder) throws IOException {
    List<int[]> steps = zapInTheRecoil(withChargeFix(folder, true));
    // A step whose movement pass finds the stun listed and after which the recoil still flies:
    // the charge is back to 0, the ram's row having a charge range.
    assertThat(stunnedInTheRecoil(steps)).as("steps in the recoil under the stun").isNotEmpty();
    for (int[] s : stunnedInTheRecoil(steps)) {
      assertThat(s[2]).as("the charge in the recoil under the stun").isZero();
    }
  }

  /**
   * The steps whose movement pass found the stun listed - listed after the step before - and after
   * which the ram's own recoil still flies.
   */
  private static List<int[]> stunnedInTheRecoil(List<int[]> steps) {
    List<int[]> out = new ArrayList<>();
    for (int i = 1; i < steps.size(); i++) {
      int[] s = steps.get(i);
      if (steps.get(i - 1)[3] == 1 && s[3] == 1 && s[0] == 1 && s[1] == 1) {
        out.add(s);
      }
    }
    return out;
  }

  @Test
  @DisplayName(
      "without the charge fix published, or with it clear, a stun on the evolved Battle Ram while"
          + " its recoil flies leaves its charge until the recoil has flown")
  void aStunInTheRecoilKeepsTheChargeWithoutTheFix(@TempDir Path without, @TempDir Path clear)
      throws IOException {
    for (List<int[]> steps :
        List.of(
            zapInTheRecoil(withoutChargeFix(without)),
            zapInTheRecoil(withChargeFix(clear, false)))) {
      assertThat(stunnedInTheRecoil(steps)).as("steps in the recoil under the stun").isNotEmpty();
      for (int[] s : stunnedInTheRecoil(steps)) {
        assertThat(s[2])
            .as("the charge in the recoil under the stun")
            .isGreaterThanOrEqualTo(MovementState.CHARGE_COMPLETE);
      }
    }
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

  @Test
  @DisplayName(
      "a stun that switches off a jumping Prince's targeting clears its strike-now byte and"
          + " leaves its charge as it was")
  void aStunInTheJumpClearsTheStrike() {
    Standard1v1Battle match = passiveTowers();
    // Short of the river on its side: the Prince walks toward the left bridge and charges.
    CharacterEntity prince = match.deploy(0, GameData.unit("Prince"), 11, 0, 8000, 10500);
    TargetingState targeting = prince.getUnit().targeting();
    MovementState movement = prince.getUnit().movement();
    int jumped = -1;
    boolean placed = false;
    for (int tick = 0; tick < 400 && jumped < 0; tick++) {
      match.getBattle().step();
      if (!placed && movement.getChargeProgress() >= MovementState.CHARGE_COMPLETE) {
        // Charged: an enemy Knight straight ahead across the river, which it jumps the river for.
        placed = true;
        match.deploy(
            match.getWorld().tick() + 1,
            GameData.unit("Knight"),
            11,
            1,
            prince.getView().getX(),
            prince.getView().getY() + 5000);
      }
      if (prince.getView().getState() == GridEntityState.JUMPING) {
        jumped = match.getWorld().tick();
      }
    }
    assertThat(jumped).as("the Prince's jump over the river").isPositive();
    // It jumps charged, the strike-now byte set by its charged walk.
    assertThat(movement.getChargeProgress()).isGreaterThanOrEqualTo(MovementState.CHARGE_COMPLETE);
    assertThat(targeting.isChargeStrike()).isTrue();

    // An enemy Zap on it: the stun's combat gate switches its targeting component off.
    boolean[] switchedOff = new boolean[1];
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void combatComponentSwitched(
                  int t, WorldEntity entity, boolean on, int hitSpeed) {
                if (entity == prince && !on) {
                  switchedOff[0] = true;
                }
              }
            });
    match.placeAreaEffect(
        jumped + 1, "Zap", 11, 1, prince.getView().getX(), prince.getView().getY(), "zap");
    for (int i = 0; i < 5 && !switchedOff[0]; i++) {
      match.getBattle().step();
    }
    assertThat(switchedOff[0]).as("the stun switched the targeting component off").isTrue();
    assertThat(prince.getView().getState()).isEqualTo(GridEntityState.JUMPING);
    // The switch-off clears the strike-now byte; the jump leaves the charge as it was.
    assertThat(targeting.isChargeStrike()).isFalse();
    assertThat(movement.getChargeProgress()).isGreaterThanOrEqualTo(MovementState.CHARGE_COMPLETE);
  }
}
