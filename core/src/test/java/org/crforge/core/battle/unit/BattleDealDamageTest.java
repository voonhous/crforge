package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.DealDamage;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deal-damage action queues a typed hit on its owner, with the entity that caused it as the
 * source; the battle drains the queue once per tick, after the post-hooks, in the order the hits
 * were queued, and runs the damage type's actions on the source and on the target, which start in
 * the same tick's last pending pass. The hit's amount is scaled by its source's own rarity row and
 * level, and by the Common row at the level the source had once the source has left the battle.
 */
class BattleDealDamageTest {

  private final List<String> performed = new ArrayList<>();

  private BattleAction leaf(String name) {
    return new BattleAction() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ActionInstance start(ActionHolder holder) {
        performed.add(name);
        return null;
      }
    };
  }

  @Test
  @DisplayName("typed hits land at the next drain, in order, and run the type's two actions")
  void typedHitsLandAtTheDrain() {
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    List<String> hits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                hits.add(tick + " " + target.name() + " " + damage + " " + result.applied());
              }
            });
    match.play(0, GameData.card("Knight"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Blue");
    for (int tick = 0; tick < 30; tick++) {
      battle.step();
    }
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    TowerEntity tower = null;
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof TowerEntity t && t.name().equals("PrincessTower_1_1")) {
        tower = t;
      }
    }
    int full = knight.getHitPoints().getHitPoints();
    DamageType type =
        DamageType.builder()
            .name("D")
            .enableLevelScaling(false)
            .actionOnSource(leaf("on_source"))
            .actionOnTarget(leaf("on_target"))
            .build();

    knight
        .actionHolder()
        .start(new DealDamage(ActionRow.named("deal"), 100, type), tower.actionHolder());
    knight
        .actionHolder()
        .start(new DealDamage(ActionRow.named("deal"), 30, type), tower.actionHolder());
    assertThat(knight.getHitPoints().getHitPoints()).as("queued, not dealt").isEqualTo(full);

    battle.step();
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(full - 130);
    assertThat(hits).containsExactly("30 Blue_0 100 100", "30 Blue_0 30 30");
    // Scheduled with no delay by the drain, both actions start in the same tick's phase 3.
    assertThat(performed)
        .containsExactlyInAnyOrder("on_source", "on_source", "on_target", "on_target");
    assertThat(knight.actionHolder().queued()).isEmpty();
  }

  private static DeployCard knight() {
    return GameData.card("Knight");
  }

  /** The card with its unit on another rarity row. */
  private static DeployCard withRarity(DeployCard card, RarityTable rarity) {
    return new DeployCard(
        card.name(),
        card.unit().toBuilder().rarity(rarity).build(),
        card.count(),
        card.secondary(),
        card.secondaryCount(),
        card.summonRadius(),
        card.summonWidth(),
        card.summonDeployDelayMs(),
        card.summonDeployDelaySecondMs(),
        card.canDeployOnEnemySide(),
        card.canPlaceOnBuildings(),
        card.canPlaceOnWater(),
        card.fullLaneDeploy(),
        card.touchdownLimitedDeploy(),
        card.deployWTileMargin(),
        card.deployStartY(),
        card.deployEndY());
  }

  @Test
  @DisplayName(
      "a typed hit scales by its source's row and level, by the Common row once the source left")
  void typedHitsScaleByTheirSource() {
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    List<String> hits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target.name().equals("Blue_0")) {
                  hits.add(tick + " " + damage);
                }
              }
            });
    // A row of its own whose multipliers differ from the Common row's at the same step.
    RarityTable own = new RarityTable("Own", 4, 6, List.of(159, 196, 224, 240, 262, 286));
    match.play(0, knight(), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Blue");
    match.play(0, knight(), 5, 1, 14500, 22000, "Low");
    match.play(
        0, withRarity(knight(), own), Standard1v1Battle.DEFAULT_LEVEL, 1, 3500, 22000, "Own");
    for (int tick = 0; tick < 40; tick++) {
      battle.step();
    }
    CharacterEntity blue = match.getPlays().get(0).units().get(0);
    CharacterEntity low = match.getPlays().get(1).units().get(0);
    CharacterEntity ownRow = match.getPlays().get(2).units().get(0);
    DamageType type = DamageType.builder().name("D").build();

    blue.actionHolder()
        .start(new DealDamage(ActionRow.named("deal"), 76, type), low.actionHolder());
    blue.actionHolder()
        .start(new DealDamage(ActionRow.named("deal"), 76, type), ownRow.actionHolder());
    battle.step();
    // Level 5 on the Common row is step 4 (146); level 11 on the row of its own is step 6 (286).
    assertThat(hits).containsExactly("40 110", "40 217");

    match.getWorld().kill(ownRow, null);
    battle.step();
    assertThat(battle.getHolder().entities()).as("the source has left").doesNotContain(ownRow);
    hits.clear();
    blue.actionHolder()
        .start(new DealDamage(ActionRow.named("deal"), 76, type), ownRow.actionHolder());
    battle.step();
    // The level it had, step 6, now on the Common row (176).
    assertThat(hits).containsExactly("42 133");
  }

  @Test
  @DisplayName("a scaling typed hit with no source at all is refused: its level is not established")
  void aScalingHitWithoutASourceIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    match.play(0, knight(), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Blue");
    for (int tick = 0; tick < 30; tick++) {
      battle.step();
    }
    CharacterEntity blue = match.getPlays().get(0).units().get(0);
    blue.actionHolder()
        .start(new DealDamage(ActionRow.named("deal"), 76, DamageType.builder().name("D").build()));
    assertThatThrownBy(battle::step).isInstanceOf(UnsupportedOperationException.class);
  }
}
