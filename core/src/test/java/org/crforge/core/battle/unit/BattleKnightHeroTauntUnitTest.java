package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Knight's taunt on an enemy troop: the troop is forced onto the Knight for the row's
 * valid duration, and every step of that duration keeps it there. A reference that stays on the
 * Knight only locks the selector again; one lost to anything else is marked in the troop's
 * targeting queue and forced back, and the queue's flush on the next pre-hook takes the Knight
 * again; while the troop's targeting component is off, as under a freeze, the step forces nothing.
 */
class BattleKnightHeroTauntUnitTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String BUFF = "Knight_hero_IsTauntedBuff";

  /** A battle with the hero Knight and an enemy Knight walking at it, and every taunt line. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    final CharacterEntity knight =
        match.deploy(0, GameData.unit("KnightHero"), LEVEL, 0, 9000, 13000, "knight");
    final CharacterEntity enemy =
        match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 9000, 17000, "enemy");
    final List<String> taunts = new ArrayList<>();
    final List<String> flushes = new ArrayList<>();

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

                @Override
                public void targetQueueFlushed(int tick, CharacterEntity unit) {
                  flushes.add(unit.name() + " " + reference(unit));
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

  /**
   * Arms the taunt on the enemy once both have deployed; returns the scene with its lines cleared.
   */
  private static Scene armed() {
    Scene scene = new Scene();
    scene.step(30);
    scene.taunt();
    scene.step(1);
    assertThat(scene.taunts)
        .containsExactly(
            "enemy performed onto knight",
            "enemy 4000 [set_target knight 0 0 1, raise LOCK_TARGET, remaining 4000, apply_buff "
                + BUFF
                + " 4000 level %d source knight side 0]".formatted(scene.knight.getPackedLevel()));
    assertThat(reference(scene.enemy)).isEqualTo("knight");
    assertThat(scene.enemy.getBuffs().carries(BUFF)).isTrue();
    scene.taunts.clear();
    return scene;
  }

  @Test
  @DisplayName(
      "a taunted troop whose reference stays on the Knight has its selector locked again on every"
          + " step of the duration")
  void referenceKeptIsLockedAgain() {
    Scene scene = armed();
    scene.step(2);
    assertThat(scene.taunts)
        .containsExactly("enemy 3950 [raise LOCK_TARGET]", "enemy 3900 [raise LOCK_TARGET]");
    assertThat(reference(scene.enemy)).isEqualTo("knight");
    assertThat(scene.flushes).isEmpty();
  }

  @Test
  @DisplayName(
      "a taunted troop that loses its reference is marked onto the Knight and forced back, and the"
          + " flush on its next pre-hook takes the Knight again")
  void lostReferenceIsForcedBack() {
    Scene scene = armed();
    scene.step(1);
    scene.taunts.clear();
    scene.enemy.tauntDrop(false);
    scene.step(1);
    assertThat(scene.taunts)
        .containsExactly(
            "enemy 3900 [mark knight 1, set_target knight 0 0 1, raise LOCK_TARGET, remaining"
                + " 3900, raise LOCK_TARGET]");
    assertThat(reference(scene.enemy)).isEqualTo("knight");
    assertThat(scene.enemy.getTargeting().getRetargetCooldownMs()).isEqualTo(3900);

    scene.taunts.clear();
    scene.step(1);
    assertThat(scene.flushes).containsExactly("enemy knight");
    assertThat(scene.taunts).containsExactly("enemy 3850 [raise LOCK_TARGET]");
    assertThat(reference(scene.enemy)).isEqualTo("knight");
  }

  @Test
  @DisplayName(
      "while a frozen troop's targeting component is off, the taunt's step forces nothing back")
  void frozenTroopIsNotForced() {
    Scene scene = armed();
    scene.enemy.tauntBuff("Freeze", 1000, scene.knight);
    scene.step(1);
    assertThat(scene.enemy.isActive(CharacterEntity.TARGETING_SLOT)).isFalse();
    scene.enemy.tauntDrop(false);
    scene.taunts.clear();
    scene.step(1);
    assertThat(scene.taunts).containsExactly("enemy 3900 []");
    assertThat(reference(scene.enemy)).isNull();
  }
}
