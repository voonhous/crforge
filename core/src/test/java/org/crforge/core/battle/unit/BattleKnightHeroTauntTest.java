package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Knight's taunt: its ability's area effect reaches the enemy towers before it, and each
 * crown tower is taunted onto the Knight for the row's crown tower duration, its reach tested and
 * its reference forced again on every step, then let go as the duration runs out.
 */
class BattleKnightHeroTauntTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String BUFF = "Knight_hero_IsTauntedBuff";

  /** A point before the top side's left princess tower, inside its range. */
  private static final int X = 3269;

  private static final int Y = 22854;

  /** A battle with attacking towers, a hero Knight in it, and every taunt line it hears. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    final CharacterEntity knight =
        match.deploy(0, GameData.unit("KnightHero"), LEVEL, 0, X, Y, "knight");
    final List<String> taunts = new ArrayList<>();

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void tauntPerformed(
                    int tick,
                    WorldEntity unit,
                    String action,
                    int phase,
                    ActionOwner instigator,
                    WorldEntity forced) {
                  taunts.add(unit.name() + " performed onto " + forced.name());
                }

                @Override
                public void tauntStepped(
                    int tick,
                    WorldEntity unit,
                    WorldEntity forced,
                    int durationMs,
                    int falloffMs,
                    List<String> calls) {
                  taunts.add(unit.name() + " " + durationMs + " " + calls);
                }
              });
    }

    /** Runs the ability's spawn of the taunting area effect, the Knight its cause. */
    void taunt() {
      BattleAction spawn =
          GameData.actions().build("Knight_hero_CreateTauntAEO", match.getWorld().binding(knight));
      knight.actionHolder().start(spawn, knight.actionHolder());
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }
  }

  private static String reference(WorldEntity entity) {
    TargetView reference = entity.getTargeting().getReference();
    return reference == null ? null : reference.name();
  }

  @Test
  @DisplayName(
      "both crown towers in the circle are taunted onto the Knight for the crown tower duration;"
          + " each step forces the reference again, and the end lets a standing king's go")
  void bothTowersAreTaunted() {
    Scene scene = new Scene();
    TowerEntity princess =
        BattleMusketeerRunTest.towerNamed(scene.match.getBattle(), "PrincessTower_1_1");
    TowerEntity king = BattleMusketeerRunTest.towerNamed(scene.match.getBattle(), "KingTower_1_0");
    // The king stands passive, as a sleeping king does: it never takes the attacking state.
    king.holdFire();
    scene.step(40);
    assertThat(princess.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(reference(princess)).isEqualTo("knight");

    scene.taunt();
    scene.step(1);
    String armed =
        "4000 [set_target knight 0 0 0, raise LOCK_TARGET, remaining 4000, apply_buff %s 4000 level %d"
                .formatted(BUFF, scene.knight.getPackedLevel())
            + " source knight side 0]";
    assertThat(scene.taunts)
        .containsExactlyInAnyOrder(
            "PrincessTower_1_1 performed onto knight",
            "PrincessTower_1_1 " + armed,
            "KingTower_1_0 performed onto knight",
            "KingTower_1_0 " + armed);
    assertThat(reference(king)).isEqualTo("knight");
    assertThat(princess.getBuffs().carries(BUFF)).isTrue();
    assertThat(king.getBuffs().carries(BUFF)).isTrue();
    assertThat(princess.getTargeting().getRetargetCooldownMs()).isEqualTo(4000);

    scene.taunts.clear();
    scene.step(1);
    String stepped = "[set_target knight 0 0 1, raise LOCK_TARGET, raise LOCK_TARGET]";
    assertThat(scene.taunts)
        .containsExactlyInAnyOrder(
            "PrincessTower_1_1 3950 " + stepped, "KingTower_1_0 3950 " + stepped);

    scene.step(78);
    scene.taunts.clear();
    scene.step(1);
    assertThat(scene.taunts)
        .containsExactlyInAnyOrder(
            "PrincessTower_1_1 0 [remaining 0, finish, remove_buff " + BUFF + "]",
            "KingTower_1_0 0 [remaining 0, remaining 0, set_target null 0 1 0, finish, remove_buff "
                + BUFF
                + "]");
    assertThat(king.getView().getState()).isEqualTo(GridEntityState.STANDING);
    assertThat(reference(king)).isNull();
    assertThat(reference(princess)).isEqualTo("knight");
    assertThat(princess.getBuffs().carries(BUFF)).isFalse();
    assertThat(king.getBuffs().carries(BUFF)).isFalse();
  }
}
