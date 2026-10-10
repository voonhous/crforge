/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.column;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.numbers;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.LinkedHashSet;
import java.util.Set;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.MusketeerSnipe;
import org.crforge.core.battle.action.SetAttackSequenceIndex;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.target.AttackRange;
import org.crforge.core.pathfinding.target.ReferenceSetter;
import org.crforge.core.pathfinding.target.TargetingOutcome;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Musketeer: an attack sequence of a normal and a snipe entry, the second with a range
 * of its own, and the snipe run its starting action leaves, which keeps the index at the normal
 * entry while no enemy stands in its lane beyond its minimum range, and otherwise takes that enemy
 * as its reference and shoots it with the snipe entry, a round a shot.
 */
class BattleMusketeerSnipeTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The snipe run's action row, which the Musketeer's starting group leaves. */
  private static final String SNIPE = "Musketeer_EV1_snipe_targeting";

  /** The snipe's minimum range: an enemy closer than this is no snipe target. */
  private static final int MIN_RANGE = number(SNIPE, "SnipeMinRange");

  /** The snipe's maximum range: the look's box is half as long. */
  private static final int MAX_RANGE = number(SNIPE, "SnipeMaxRange");

  @Test
  @DisplayName("the snipe entry's CustomRange is the attack range while the index selects it")
  void theSnipeEntrySetsTheRange() {
    GameRow shipped = unitRow("Musketeer_EV1");
    int snipeRange = column(shipped, "AttackSequenceList").get(1).path("CustomRange").asInt();
    assertThat(numbers(shipped, "AttackSequence")).containsExactly(0, 1);
    UnitData row = GameData.unit("Musketeer_EV1");
    assertThat(row.attackSequence().order()).containsExactly(0, 1);
    assertThat(row.attackSequence().entries().get(1).customRange()).isEqualTo(snipeRange);
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity musketeer =
        new CharacterEntity(match.getWorld(), row, "Musketeer_EV1", 0, 3500, 10000, LEVEL);
    ActionHolder holder = musketeer.actionHolder();
    int radius = musketeer.getView().getCollisionRadius();

    assertThat(AttackRange.attackRange(musketeer.getTargeting()))
        .as("the normal entry keeps the row's Range")
        .isEqualTo(row.range() + radius);
    holder.start(new SetAttackSequenceIndex(ActionRow.named("set"), 1, true, false), holder);
    assertThat(AttackRange.attackRange(musketeer.getTargeting()))
        .as("the snipe entry's own range, the radius still added")
        .isEqualTo(snipeRange + radius);
  }

  @Test
  @DisplayName(
      "the snipe run starts with the placement, its group's delay of 0, with the row's rounds and keeps"
          + " the index at 0 while the only enemy stands inside its minimum range")
  void theSnipeRunKeepsTheNormalEntry() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity musketeer =
        match.deploy(0, GameData.unit("Musketeer_EV1"), LEVEL, 0, 3500, 10000);
    // Half the minimum range ahead in the same lane: inside SnipeMinRange plus the Musketeer's
    // radius.
    match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 10000 + MIN_RANGE / 2);
    MusketeerSnipe.Run run = null;
    for (int tick = 0; tick < 60; tick++) {
      battle.step();
      if (run == null) {
        run = snipeRun(musketeer.actionHolder());
        assertThat(run).as("listed on the placement's step").isNotNull();
      }
      assertThat(musketeer.getTargeting().getAttackSequenceIndex()).as("tick %d", tick).isZero();
    }
    assertThat(run).as("the snipe run is listed").isNotNull();
    assertThat(run.getRounds()).as("the row's AmmoCount").isEqualTo(number(SNIPE, "AmmoCount"));
    assertThat(run.isFinished()).isFalse();
  }

  @Test
  @DisplayName(
      "an enemy in the lane beyond the minimum range is taken as the reference and shot with the"
          + " snipe entry's projectile from beyond the row's Range, one round a shot, until the"
          + " rounds are spent and the run ends")
  void aSnipeTargetIsShotUntilTheRoundsAreSpent() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity musketeer =
        match.deploy(0, GameData.unit("Musketeer_EV1"), LEVEL, 0, 3500, 9500);
    // Far up the same lane: beyond SnipeMinRange for the whole of the snipe, inside the look's box.
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 23000);
    String snipeShot =
        column(unitRow("Musketeer_EV1"), "AttackSequenceList").get(1).path("Projectile").asText();
    int ammo = number(SNIPE, "AmmoCount");
    UnitData row = GameData.unit("Musketeer_EV1");

    Set<Integer> shots = new LinkedHashSet<>();
    MusketeerSnipe.Run run = null;
    boolean taken = false;
    boolean ended = false;
    for (int tick = 0; tick < 200 && !ended; tick++) {
      battle.step();
      if (run == null) {
        run = snipeRun(musketeer.actionHolder());
      }
      if (run.getHeld() == knight.getId()) {
        taken = true;
        assertThat(musketeer.getTargeting().getReference())
            .as("tick %d: the target held is the reference", tick)
            .isSameAs(knight.getTargetView());
        assertThat(musketeer.getTargeting().getAttackSequenceIndex())
            .as("tick %d: the snipe entry", tick)
            .isEqualTo(1);
      }
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof ProjectileEntity shot
            && shot.getData().name().equals(snipeShot)
            && shots.add(shot.getId())) {
          assertThat(shot.getTarget()).as("tick %d: aimed at the Knight", tick).isSameAs(knight);
          int dx = knight.getView().getX() - musketeer.getView().getX();
          int dy = knight.getView().getY() - musketeer.getView().getY();
          int reach =
              row.range()
                  + musketeer.getView().getCollisionRadius()
                  + knight.getView().getCollisionRadius();
          assertThat((long) dx * dx + (long) dy * dy)
              .as("tick %d: fired from beyond the row's own reach", tick)
              .isGreaterThan((long) reach * reach);
          assertThat(run.getRounds())
              .as("tick %d: the shot spent a round", tick)
              .isEqualTo(ammo - shots.size());
        }
      }
      ended = !musketeer.actionHolder().running().contains(run);
    }
    assertThat(taken).as("the Knight was taken as the snipe target").isTrue();
    assertThat(shots).as("one snipe shot a round").hasSize(ammo);
    assertThat(ended).as("the run ends once its rounds are spent").isTrue();
    assertThat(run.getHeld()).isEqualTo(MusketeerSnipe.NONE);
    assertThat(musketeer.getTargeting().getAttackSequenceIndex())
        .as("back to the normal entry")
        .isZero();
  }

  @Test
  @DisplayName(
      "the reference setter storing anything but the target held puts the index back to the"
          + " normal entry at once")
  void aReferenceOtherThanTheTargetHeldSetsTheNormalEntry() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity musketeer =
        match.deploy(0, GameData.unit("Musketeer_EV1"), LEVEL, 0, 3500, 9500);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 23000);
    // Out of the lane: never a snipe candidate.
    CharacterEntity other = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 14500, 23000);
    MusketeerSnipe.Run run = null;
    for (int tick = 0; tick < 60 && (run == null || run.getHeld() != knight.getId()); tick++) {
      battle.step();
      run = snipeRun(musketeer.actionHolder());
    }
    assertThat(run.getHeld()).as("the Knight in the lane is held").isEqualTo(knight.getId());
    TargetingState targeting = musketeer.getTargeting();
    assertThat(targeting.getAttackSequenceIndex()).isEqualTo(1);

    ReferenceSetter.setReference(
        targeting, knight.getTargetView(), true, false, true, null, new TargetingOutcome());
    assertThat(targeting.getAttackSequenceIndex())
        .as("the target held stored again: the snipe entry stays")
        .isEqualTo(1);
    ReferenceSetter.setReference(
        targeting, other.getTargetView(), false, false, true, null, new TargetingOutcome());
    assertThat(targeting.getAttackSequenceIndex())
        .as("another reference: the normal entry")
        .isZero();
    assertThat(run.getHeld()).as("the run still holds its target").isEqualTo(knight.getId());
  }

  private static MusketeerSnipe.Run snipeRun(ActionHolder holder) {
    for (ActionInstance instance : holder.running()) {
      if (instance instanceof MusketeerSnipe.Run snipe) {
        return snipe;
      }
    }
    return null;
  }
}
