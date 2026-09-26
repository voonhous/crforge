package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.DealDamage;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deal-damage action queues a typed hit on its owner, with the entity that caused it as the
 * source; the battle drains the queue once per tick, after the post-hooks, in the order the hits
 * were queued, and runs the damage type's actions on the source and on the target, which start in
 * the same tick's last pending pass.
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
    match.play(
        0,
        UnitDataMapper.toDeployCard(Objects.requireNonNull(CardRegistry.get("knight"))),
        Standard1v1Battle.DEFAULT_LEVEL,
        0,
        3500,
        10000,
        "Blue");
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
}
