package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.Kill;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A kill action on a unit in the battle: the unit dies as from a hit of its whole hit points - its
 * movement switched off, observers told of the death, gone at the next cleanup - unless a shield is
 * up, which takes the kill.
 */
class BattleKillActionTest {

  private static DeployCard knight() {
    return GameData.card("Knight");
  }

  @Test
  @DisplayName("a killed Knight dies as from a hit, and a shield that is up takes the kill")
  void aKillActionKillsAUnit() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    List<String> deaths = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                deaths.add(target.name() + " " + damage + (result.died() ? " died" : " lived"));
              }
            });
    match.play(0, knight(), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Blue");
    match.play(0, knight(), Standard1v1Battle.DEFAULT_LEVEL, 0, 14500, 10000, "Shielded");
    for (int tick = 0; tick < 40; tick++) {
      battle.step();
    }
    CharacterEntity blue = match.getPlays().get(0).units().get(0);
    CharacterEntity shielded = match.getPlays().get(1).units().get(0);
    int full = blue.getHitPoints().getHitPoints();
    assertThat(blue.getView().isMovementActive()).isTrue();

    new ActionHolder(blue).start(new Kill(ActionRow.named("kill"), null));
    assertThat(blue.getHitPoints().getHitPoints()).isZero();
    assertThat(blue.getView().isMovementActive()).as("its death switches movement off").isFalse();
    assertThat(deaths).containsExactly("Blue_0 " + full + " died");
    assertThat(battle.getHolder().entities()).as("still listed until the cleanup").contains(blue);
    battle.step();
    assertThat(battle.getHolder().entities()).doesNotContain(blue);

    shielded.getHitPoints().setShield(300);
    new ActionHolder(shielded).start(new Kill(ActionRow.named("kill"), null));
    assertThat(shielded.getHitPoints().getShield()).isZero();
    assertThat(shielded.getHitPoints().getHitPoints()).isEqualTo(full);
    assertThat(shielded.getView().isAlive()).isTrue();
  }
}
