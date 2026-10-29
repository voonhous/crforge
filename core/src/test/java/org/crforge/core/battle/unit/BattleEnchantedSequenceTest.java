/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.GiantBufferBuff;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enchanting buff, the Giant Buffer's, on a unit whose attack sequence replaces its attack.
 *
 * <p>The scene: the top side's Knight, given an attack sequence written by each test, walks to the
 * bottom side's left princess tower and attacks it, the towers passive. The buff, a hand-written
 * row that adds damage to every third landed hit, is started on the Knight as it is placed, the
 * Knight as its giver. The sequences:
 *
 * <ul>
 *   <li>a continuous-damage ramp as the Inferno Dragon's (its row's damage, then two more stages,
 *       each window 2000 of attack time) and a hit speed of 400: damage entries alone;
 *   <li>three entries that each launch the Archer's arrow, the index moved by no action, as the
 *       Little Prince's;
 *   <li>two entries that each run an action in place of the hit, a run on the hit's target of a
 *       damage-taking action, as the reworked Three Musketeers' bayonet.
 * </ul>
 */
class BattleEnchantedSequenceTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The unit the scene plays. */
  private static final String UNIT = "Knight";

  /** The hand-written enchanting buff. */
  private static final String ENCHANT = "TestEnchant";

  /** The hand-written action an acting entry runs: a run on its target of {@link #STAB_DAMAGE}. */
  private static final String STAB = "TestStab";

  /** The hand-written damage-taking action {@link #STAB} runs on the hit's target. */
  private static final String STAB_DAMAGE = "TestStabDamage";

  /** Long enough for the walk to the tower and a ramp through its three stages. */
  private static final int TICKS = 600;

  /** One landed hit or projectile impact on the tower: its tick and its damage. */
  private record Hit(int tick, int damage) {}

  @Test
  @DisplayName(
      "an enchanting buff on a unit whose sequence is a ramp of damage entries alone runs: every"
          + " third hit on the tower gains the same added damage, the hit ticks and the ramp"
          + " unchanged")
  void anEnchantedRampGainsEveryThirdHit(@TempDir Path folder) throws IOException {
    GameTables tables = tables(folder, BattleEnchantedSequenceTest::ramp);
    assertThat(new Standard1v1Battle(tables, LEVEL, false).getWorld().getRecords().unit(UNIT))
        .as("a sequence that replaces the attack")
        .extracting(unit -> unit.attackSequence().replacesAttack())
        .isEqualTo(true);

    List<Hit> plain = hits(tables, false);
    List<Hit> enchanted = hits(tables, true);

    // The added damage brings the tower down sooner, so the enchanted hits are the first of the
    // plain ones.
    assertThat(enchanted).as("enchanted hits on the tower").hasSizeGreaterThan(9);
    assertThat(plain.subList(0, enchanted.size()).stream().map(Hit::damage).distinct().count())
        .as("the ramp's stages")
        .isEqualTo(3);
    assertThat(enchanted.stream().map(Hit::tick).toList())
        .as("the same hit ticks")
        .isEqualTo(plain.subList(0, enchanted.size()).stream().map(Hit::tick).toList());
    Set<Integer> added = new HashSet<>();
    for (int i = 0; i < enchanted.size(); i++) {
      int gain = enchanted.get(i).damage() - plain.get(i).damage();
      if (i % 3 == 2) {
        added.add(gain);
      } else {
        assertThat(gain).as("hit %d, not a third", i + 1).isZero();
      }
    }
    assertThat(added).as("the added damage of every third hit").hasSize(1);
    assertThat(added.iterator().next()).isPositive();
  }

  @Test
  @DisplayName(
      "an enchanting buff on a unit whose sequence entries launch its projectiles runs: every"
          + " third arrow's impact on the tower gains the same added damage, the impact ticks"
          + " unchanged")
  void anEnchantedSequenceThatLaunchesGainsEveryThirdImpact(@TempDir Path folder)
      throws IOException {
    String arrow = Shipped.text(Shipped.unitRow("Archer"), "Projectile");
    GameTables tables =
        tables(
            folder,
            columns -> {
              columns.putArray("AttackSequence").add(0).add(1).add(2);
              columns.put("AttackSequenceMode", "None");
              columns.put("Projectile", arrow).put("Projectile2", arrow).put("Projectile3", arrow);
            });
    UnitData unit = new Standard1v1Battle(tables, LEVEL, false).getWorld().getRecords().unit(UNIT);
    assertThat(unit.attackSequence().replacesAttack()).as("a sequence that replaces").isTrue();
    assertThat(unit.attackSequence().entryAt(0).projectile())
        .as("the entry the index selects launches")
        .isNotNull();

    List<Hit> plain = hits(tables, false);
    List<Hit> enchanted = hits(tables, true);

    // The added damage brings the tower down sooner, so the enchanted impacts are the first of the
    // plain ones.
    assertThat(enchanted).as("enchanted impacts on the tower").hasSizeGreaterThan(5);
    assertThat(enchanted.stream().map(Hit::tick).toList())
        .as("the same impact ticks")
        .isEqualTo(plain.subList(0, enchanted.size()).stream().map(Hit::tick).toList());
    Set<Integer> added = new HashSet<>();
    for (int i = 0; i < enchanted.size(); i++) {
      int gain = enchanted.get(i).damage() - plain.get(i).damage();
      if (i % 3 == 2) {
        added.add(gain);
      } else {
        assertThat(gain).as("impact %d, not a third", i + 1).isZero();
      }
    }
    assertThat(added).as("the added damage of every third impact").hasSize(1);
    assertThat(added.iterator().next()).isPositive();
  }

  @Test
  @DisplayName(
      "an enchanting buff on a unit whose sequence entries run an action in place of the hit"
          + " runs: those hits are neither counted nor enchanted, the damage the action deals on"
          + " the tower unchanged")
  void anEnchantedSequenceThatActsIsNeitherCountedNorEnchanted(@TempDir Path folder)
      throws IOException {
    GameTables tables =
        tables(
            folder,
            columns -> {
              columns.putArray("AttackSequence").add(0).add(1);
              columns.put("AttackSequenceMode", "None");
              ArrayNode list = columns.putArray("AttackSequenceList");
              list.addObject().put("DoAttackAction", STAB);
              list.addObject().put("DoAttackAction", STAB);
            });
    assertThat(
            new Standard1v1Battle(tables, LEVEL, false)
                .getWorld()
                .getRecords()
                .unit(UNIT)
                .attackSequence()
                .replacesAttack())
        .as("a sequence that replaces")
        .isTrue();

    List<Hit> plain = hits(tables, false);
    List<GiantBufferBuff.Run> runs = new ArrayList<>();
    List<Hit> enchanted = hits(tables, true, runs);

    assertThat(plain).as("the action's hits on the tower").hasSizeGreaterThan(5);
    assertThat(enchanted).as("the same hits, nothing added").isEqualTo(plain);
    assertThat(runs).as("the buff still runs").hasSize(1);
    assertThat(runs.get(0).getCounter()).as("no hit counted").isZero();
  }

  /** The hits the Knight lands on the bottom side's left princess tower, enchanted or not. */
  private static List<Hit> hits(GameTables tables, boolean enchanted) {
    return hits(tables, enchanted, new ArrayList<>());
  }

  /**
   * The hits the Knight lands on the bottom side's left princess tower, enchanted or not.
   *
   * @param runs filled with the enchanting buff's runs still listed on the Knight at the end
   */
  private static List<Hit> hits(
      GameTables tables, boolean enchanted, List<GiantBufferBuff.Run> runs) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    TowerEntity tower = leftTower(match);
    List<Hit> hits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target == tower) {
                  hits.add(new Hit(tick, damage));
                }
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (target == tower) {
                  hits.add(new Hit(tick, damage));
                }
              }
            });
    CharacterEntity unit =
        match.deploy(0, match.getWorld().getRecords().unit(UNIT), LEVEL, 1, 3500, 11000, "K");
    if (enchanted) {
      BattleWorld world = match.getWorld();
      unit.actionHolder()
          .start(world.getActions().build(ENCHANT, world.binding(unit)), unit.actionHolder());
    }
    for (int step = 0; step < TICKS; step++) {
      match.getBattle().step();
    }
    for (ActionInstance instance : unit.actionHolder().running()) {
      if (instance instanceof GiantBufferBuff.Run run) {
        runs.add(run);
      }
    }
    return hits;
  }

  /** Side 0's left princess tower. */
  private static TowerEntity leftTower(Standard1v1Battle match) {
    return match.getWorld().getHolder().entities().stream()
        .filter(TowerEntity.class::isInstance)
        .map(TowerEntity.class::cast)
        .filter(t -> t.side() == 0 && !t.getData().king() && t.x() < 9000)
        .findFirst()
        .orElseThrow();
  }

  /** The Inferno Dragon-like ramp: damage entries alone. */
  private static void ramp(ObjectNode columns) {
    columns
        .put("Damage", 10)
        .put("VariableDamage2", 30)
        .put("VariableDamage3", 90)
        .put("VariableDamageTime1", 2000)
        .put("VariableDamageTime2", 2000)
        .put("HitSpeed", 400);
  }

  /**
   * The tables with the Knight's sequence, the enchanting buff and the acting entries' actions
   * written.
   *
   * @param sequence writes the Knight's sequence columns
   */
  private static GameTables tables(Path folder, Consumer<ObjectNode> sequence) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(folder, "characters", rows -> sequence.accept(GameData.columns(rows, UNIT)));
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
              .put("AddedDamage", 50)
              .put("AddedCrownTowerDamage", 70)
              .put("FinishIfInstigatorDies", -1);
          ObjectNode stab = rows.putObject(STAB);
          stab.put("class", "LogicActionRunOnInstigatorData");
          stab.put("ClassType", "ActionRunOnInstigator");
          ObjectNode stabFields =
              stab.putObject("fields").put("ClassType", "ActionRunOnInstigator");
          stabFields.putObject("ActionToExecute").put("action", STAB_DAMAGE);
          ObjectNode damage = rows.putObject(STAB_DAMAGE);
          damage.put("class", "LogicActionTakeDamageData");
          damage.put("ClassType", "ActionTakeDamage");
          ObjectNode damageFields = damage.putObject("fields").put("ClassType", "ActionTakeDamage");
          damageFields.putObject("Damage").put("BaseDamage", 40);
        });
    return GameTables.load(folder);
  }
}
