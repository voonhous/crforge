package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action a unit's row runs on itself as it attacks (OnAttackSelfAction): scheduled by every hit
 * that is not cancelled for distance, after the launch, with the unit as its own cause, so it runs
 * in the unit's pending pass of the hit's tick and reaches the following hit, not this one. Only
 * the hero Elite Archer's row sets it, to put its attack sequence back on the arrow after each
 * attack.
 */
class BattleAttackSelfActionTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** One launch of the archer: the tick and the projectile row. */
  private record Launch(int tick, String projectile) {}

  /** The hero Elite Archer facing a Golem far enough away that it fires several times at it. */
  private static CharacterEntity archerFacingGolem(Standard1v1Battle battle) {
    CharacterEntity archer =
        battle.deploy(0, unit(battle, "EliteArcherHero"), LEVEL, 0, 3500, 14000, "a");
    battle.deploy(0, unit(battle, "Golem"), LEVEL, 1, 3500, 22000, "g");
    return archer;
  }

  private static UnitData unit(Standard1v1Battle battle, String row) {
    return battle.getWorld().getRecords().unit(row);
  }

  /** Records every projectile the given unit launches. */
  private static List<Launch> launches(Standard1v1Battle battle, CharacterEntity unit) {
    List<Launch> out = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (projectile.getOwner() == unit) {
                  out.add(new Launch(tick, projectile.getData().name()));
                }
              }
            });
    return out;
  }

  @Test
  @DisplayName("the hero Elite Archer builds and fires its arrow, its sequence index staying on 0")
  void theHeroEliteArcherFiresItsArrow() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    CharacterEntity archer = archerFacingGolem(battle);
    List<Launch> launched = launches(battle, archer);
    while (battle.getBattle().getTick() <= 200) {
      battle.getBattle().step();
      assertThat(archer.attackSequenceIndex()).isZero();
    }

    assertThat(launched).hasSizeGreaterThan(2);
    assertThat(launched)
        .extracting(Launch::projectile)
        .containsOnly("EliteArcherHero_arrow_projectile");
  }

  @Test
  @DisplayName(
      "an index set between two hits picks the next hit's entry, and that hit's own action puts it"
          + " back on 0 in the pending pass of the same tick, for the hit after it")
  void theOwnAttackActionResetsTheIndexForTheFollowingHit(@TempDir Path folder) throws IOException {
    // Entry 1 is the ability's shot, which no hit reaches without the ability; a plain Archer arrow
    // stands in for it so the hit it picks is seen.
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              ArrayNode list =
                  (ArrayNode) GameData.columns(rows, "EliteArcherHero").get("AttackSequenceList");
              ((ObjectNode) list.get(1)).put("Projectile", "ArcherArrow");
            });
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity archer = archerFacingGolem(battle);
    List<Launch> launched = launches(battle, archer);
    List<Integer> indexAfter = new ArrayList<>();
    int setOn = -1;
    while (battle.getBattle().getTick() <= 300) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      indexAfter.add(archer.attackSequenceIndex());
      if (setOn < 0 && launched.size() == 2 && launched.get(1).tick() == tick) {
        // After the second launch, as the ability's group would.
        archer.setAttackSequenceIndex(1, true);
        setOn = tick;
      }
    }

    assertThat(setOn).isPositive();
    assertThat(launched).hasSizeGreaterThan(4);
    assertThat(launched.get(2).projectile()).isEqualTo("ArcherArrow");
    int third = launched.get(2).tick();
    // The index stays 1 until the step of the third launch, which ends with it back on 0.
    assertThat(indexAfter.subList(setOn + 1, third)).containsOnly(1);
    assertThat(indexAfter.get(third)).isZero();
    assertThat(launched.subList(3, launched.size()))
        .extracting(Launch::projectile)
        .containsOnly("EliteArcherHero_arrow_projectile");
    assertThat(launched.subList(0, 2))
        .extracting(Launch::projectile)
        .containsOnly("EliteArcherHero_arrow_projectile");
  }

  @Test
  @DisplayName(
      "the arrow's first flight step hits an enemy character between the archer and where the arrow"
          + " was launched, which no pass of the arrow reaches")
  void theFirstStepHitsWhatStandsBetweenTheArcherAndItsArrow(@TempDir Path folder)
      throws IOException {
    // Aimed at buildings only, the archer shoots at the tower past the Bat hovering just in front
    // of it, nearer than the arrow's start and out of reach of the pass the arrow makes there.
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows -> GameData.columns(rows, "EliteArcherHero").put("TargetOnlyBuildings", true));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity archer =
        battle.deploy(0, unit(battle, "EliteArcherHero"), LEVEL, 0, 3500, 18000, "a");
    CharacterEntity bat = battle.deploy(0, unit(battle, "Bat"), LEVEL, 1, 3500, 18400, "b");
    List<Launch> launched = launches(battle, archer);
    List<int[]> batHits = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (target == bat && projectile.getOwner() == archer) {
                  batHits.add(new int[] {tick, damage, projectile.getId()});
                }
              }
            });
    while (battle.getBattle().getTick() <= 200) {
      battle.getBattle().step();
    }
    // The first arrow's first flight step, on the tick after its launch, hits the Bat and kills it.
    assertThat(launched).isNotEmpty();
    assertThat(batHits).hasSize(1);
    assertThat(batHits.get(0)[0]).isEqualTo(launched.get(0).tick() + 1);
    assertThat(bat.getHitPoints().getHitPoints()).isNotPositive();
  }
}
