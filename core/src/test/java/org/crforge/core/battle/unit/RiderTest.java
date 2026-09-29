package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.move.ContactRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The riders a Goblin Giant carries, and what the battle refuses of a row that attaches them. */
class RiderTest {

  private static final int LEVEL_11 = 10;

  /** A Goblin Giant played for the bottom side on tick 0, after one step. */
  private static CharacterEntity playedGoblinGiant(Standard1v1Battle match) {
    match.play(0, GameData.card("GoblinGiant"), 11, 0, 3500, 10000, "GoblinGiant");
    match.getBattle().step();
    return match.getPlays().get(0).units().get(0);
  }

  @Test
  @DisplayName(
      "a played Goblin Giant makes its two riders first, with the lower ids, deploying with it")
  void theRidersComeFirst() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity giant = playedGoblinGiant(match);

    List<CharacterEntity> riders = giant.riders();
    assertThat(riders)
        .extracting(CharacterEntity::name)
        .containsExactly("GoblinGiant_0_0", "GoblinGiant_0_1");
    for (CharacterEntity rider : riders) {
      assertThat(rider.getId()).isLessThan(giant.getId());
      assertThat(rider.getParent()).isSameAs(giant);
      assertThat(rider.getView().getDeployCountdown())
          .isEqualTo(giant.getView().getDeployCountdown());
    }
  }

  @Test
  @DisplayName("a rider is untouchable, answers no attacker and takes no part in collision")
  void aRiderIsOutOfReach() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity giant = playedGoblinGiant(match);
    CharacterEntity rider = giant.riders().get(0);

    assertThat(rider.untouchable()).isTrue();
    assertThat(rider.getTargetView().acceptsAttacker(new GridEntity(), false)).isFalse();
    assertThat(ContactRule.collides(rider.getView())).isZero();
    assertThat(rider.filterSubject().attachedChild()).isTrue();
    assertThat(giant.untouchable()).isFalse();
    assertThat(ContactRule.collides(giant.getView())).isEqualTo(1);
  }

  @Test
  @DisplayName("a rider rides at its parent's radius behind its heading, turned by its own shift")
  void aRiderSitsBehindItsParent() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity giant = playedGoblinGiant(match);
    // Facing up (heading 90), the first rider's ring angle 180 gives a share of 45 of its 90 arc,
    // turned by -22: a base of 113, at 900 from the Giant.
    CharacterEntity rider = giant.riders().get(0);
    assertThat(rider.getView().getX()).isEqualTo(3850);
    assertThat(rider.getView().getY()).isEqualTo(9672);
    assertThat(rider.getView().getZ()).isEqualTo(4000);
  }

  @Test
  @DisplayName(
      "the cleanup that removes a parent lets its riders go and removes them too, their death"
          + " spawns folded in by that same cleanup")
  void theRidersLeaveWithTheirParent() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity giant = playedGoblinGiant(match);
    List<CharacterEntity> riders = List.copyOf(giant.riders());
    for (int tick = 1; tick <= 30; tick++) {
      match.getBattle().step();
    }
    List<String> presentAfter = new ArrayList<>();
    List<String> letGo = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void parentLeft(int tick, CharacterEntity rider, CharacterEntity parent) {
                letGo.add(rider.name());
              }

              @Override
              public void afterPrePass(int tick, List<WorldEntity> present) {
                if (presentAfter.isEmpty()) {
                  present.forEach(e -> presentAfter.add(e.name()));
                }
              }
            });
    // Killed between two steps, the Giant leaves at the next step's opening cleanup.
    match.getWorld().kill(giant, null);
    match.getBattle().step();

    assertThat(letGo).containsExactly("GoblinGiant_0_0", "GoblinGiant_0_1");
    assertThat(presentAfter)
        .doesNotContain("GoblinGiant_0", "GoblinGiant_0_0", "GoblinGiant_0_1")
        .contains("GoblinGiant_0_0_0", "GoblinGiant_0_1_0");
    for (CharacterEntity rider : riders) {
      assertThat(rider.getParent()).isNull();
    }
  }

  @Test
  @DisplayName("a row that attaches riders is refused when placed directly, not by its card")
  void aDirectPlacementIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    assertThatThrownBy(() -> match.deploy(0, GameData.unit("GoblinGiant"), 11, 0, 3500, 10000))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("riders");
  }

  @Test
  @DisplayName("a buff on a parent or a rider is refused, as the parent hands it to its riders")
  void aBuffIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    CharacterEntity giant = playedGoblinGiant(match);
    assertThatThrownBy(
            () ->
                giant
                    .getBuffs()
                    .apply(GameData.records().buff("ZapFreeze"), 500, LEVEL_11, null, 1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("riders");
    assertThatThrownBy(
            () ->
                giant
                    .riders()
                    .get(0)
                    .getBuffs()
                    .apply(GameData.records().buff("ZapFreeze"), 500, LEVEL_11, null, 1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("riders");
  }

  @Test
  @DisplayName("a Ram Rider's rider takes no building as its target, and fires its bola at a troop")
  void theRamRidersRiderTargetsTroopsOnly() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.play(0, GameData.card("RamRider"), 11, 0, 3500, 10000, "RamRider");
    match.getBattle().step();
    CharacterEntity rider = match.getPlays().get(0).units().get(0).riders().get(0);
    for (int tick = 1; tick <= 40; tick++) {
      match.getBattle().step();
    }
    assertThat(rider.getTargeting().getReference()).isNull();

    // A red Knight ahead of it: the rider takes it, and its bola snares it.
    CharacterEntity knight = match.deploy(41, GameData.unit("Knight"), 11, 1, 3500, 16000);
    boolean snared = false;
    for (int tick = 41; tick <= 200 && !snared; tick++) {
      match.getBattle().step();
      snared = knight.getBuffs().carries("BolaSnare");
    }
    assertThat(snared).isTrue();
  }

  @Test
  @DisplayName("the rider ranks a carrier of the buff it names lower")
  void theRiderRanksASnaredTargetLower() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    match.play(0, GameData.card("RamRider"), 11, 0, 3500, 10000, "RamRider");
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), 11, 1, 3500, 20000);
    match.getBattle().step();
    CharacterEntity rider = match.getPlays().get(0).units().get(0).riders().get(0);
    assertThat(rider.getSelection().carriesDeprioritizingBuff(knight.getTargetView())).isFalse();

    knight.getBuffs().apply(GameData.records().buff("BolaSnare"), 2000, LEVEL_11, null, 0);
    assertThat(rider.getSelection().carriesDeprioritizingBuff(knight.getTargetView())).isTrue();
  }

  @Test
  @DisplayName("the buff a character's targeting passes over outright is refused as it is applied")
  void aBuffTheTargetingPassesOverIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables());
    // No row passes over a buff without ranking its carriers lower instead; this one is made to.
    match.deploy(
        0,
        GameData.unit("RamRider").toBuilder().deprioritizeTargetsWithBuff(false).build(),
        11,
        0,
        3500,
        10000);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), 11, 1, 3500, 20000);
    match.getBattle().step();

    assertThatThrownBy(
            () ->
                knight
                    .getBuffs()
                    .apply(GameData.records().buff("BolaSnare"), 2000, LEVEL_11, null, 0))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("targeting reads");
  }
}
