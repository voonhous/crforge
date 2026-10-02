package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Goblinstein where the reference runs do not take it: a later card play its monster's listener
 * would hear, the doctor casting its ability, the ability's run off an area effect that follows,
 * and a Mirror of the card, whose doctor is a champion.
 */
class BattleGoblinsteinTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A Goblinstein and an Archer, then the Mirrors the shuffle holds back for the refills. */
  private static final List<String> GOBLINSTEIN_MIRRORS =
      List.of("Goblinstein", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "a later card play of the monster's side, which its listener would hear, is refused; the"
          + " other side's is played")
  void aLaterPlayOfTheMonstersSideIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.play(0, GameData.card("Goblinstein"), LEVEL, 0, 3500, 12000, "g");
    battle.play(10, GameData.card("Knight"), LEVEL, 1, 3500, 22000, "k1");
    battle.play(20, GameData.card("Knight"), LEVEL, 0, 14500, 10000, "k0");
    run(battle, 19);
    assertThat(battle.getPlays().get(1).units()).hasSize(1);

    assertThatThrownBy(() -> run(battle, 20))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "Knight played while g_0's goblinstein_listen_to_new_deploy listens for its side's"
                + " card plays of Goblinstein, whose cards are not read, not modelled");
  }

  @Test
  @DisplayName(
      "the ability's run connects the doctor to the monster, and the doctor casting, after which"
          + " the tether would start, is refused")
  void theDoctorCastingIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.play(0, GameData.card("Goblinstein"), LEVEL, 0, 3500, 12000, "g");
    run(battle, 5);
    CharacterEntity monster = battle.getPlays().get(0).units().get(0);
    CharacterEntity doctor = battle.getPlays().get(0).units().get(1);
    assertThat(doctor.chainHead()).isSameAs(monster);
    assertThat(monster.chainNext()).isSameAs(doctor);

    doctor.getView().setState(GridEntityState.CASTING);
    assertThatThrownBy(() -> battle.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "g_1 casts its ability under goblinstein_ability_action, whose tether after the cast is"
                + " not modelled");
  }

  @Test
  @DisplayName(
      "a monster that leaves before the ability's first step leaves the doctor first in its chain,"
          + " so the run connects to nothing, and the doctor's death makes no death area")
  void aDoctorAtTheHeadConnectsToNothing() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    List<String> log = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void goblinsteinConnected(
                  int tick, AreaEffectEntity owner, BattleEntity connected) {
                log.add("connect " + connected);
              }

              @Override
              public void goblinsteinDeathAreaMade(
                  int tick,
                  AreaEffectEntity owner,
                  WorldEntity left,
                  AreaEffectEntity deathArea,
                  int x,
                  int y) {
                log.add("death_area " + left.name());
              }
            });
    battle.play(0, GameData.card("Goblinstein"), LEVEL, 0, 3500, 12000, "g");
    run(battle, 0);
    CharacterEntity monster = battle.getPlays().get(0).units().get(0);
    CharacterEntity doctor = battle.getPlays().get(0).units().get(1);
    // The opening cleanup of tick 1 removes the monster, before the run's first step.
    monster.killBy(null);
    run(battle, 1);
    assertThat(doctor.chainHead()).isSameAs(doctor);
    assertThat(log).containsExactly("connect null");

    doctor.killBy(null);
    run(battle, 3);
    assertThat(log).containsExactly("connect null");
  }

  @Test
  @DisplayName("the ability's run on an owner other than an area effect that follows is refused")
  void theAbilityOffAFollowingAreaEffectIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    ActionOwnerEntity owner = battle.addActionOwner("o", 0, 9000, 10000, LEVEL - 1);
    BattleAction ability = GameData.actions().build("goblinstein_ability_action", owner.binding());
    battle.scheduleAction(1, owner, ability);

    assertThatThrownBy(() -> run(battle, 1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "goblinstein_ability_action on an owner other than an area effect that follows, not"
                + " modelled");
  }

  @Test
  @DisplayName("a Mirror of Goblinstein, whose doctor is its champion, is refused")
  void aMirrorOfGoblinsteinIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(GOBLINSTEIN_MIRRORS, KNIGHTS, 0, 0);
    battle.play(21, GameData.card("Goblinstein"), LEVEL, 0, 3500, 10000, "g");
    battle.playMirror(300, "Mirror", LEVEL, 0, 14500, 10000, "m");

    assertThatThrownBy(() -> run(battle, 300))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("m: a Mirror of the champion Goblinstein, which no reference holds");
  }

  /** Steps the battle until it has run the given tick. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
