package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.spawn.SpawnArguments;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Row columns the battle reads only to refuse them where they would act. */
class BattleRowRefusalTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  /** Steps the battle up to a number of ticks, or until a step throws. */
  private static void run(Standard1v1Battle match, int ticks) {
    for (int tick = 0; tick < ticks; tick++) {
      match.getBattle().step();
    }
  }

  @Test
  @DisplayName(
      "a unit whose BuffAfterHits buff is not modelled is made, and refused at the first hit it"
          + " counts")
  void aBuffAfterHitsIsRefusedAtTheFirstHit() {
    Standard1v1Battle match = passiveTowers();
    // The evolved Skeleton's own buff is modelled; one that shields its carrier is not.
    UnitData skeleton =
        GameData.unit("Skeleton_EV1").toBuilder().buffAfterHits(List.of("ShieldBoost")).build();
    assertThat(skeleton.unmodelledColumns()).isEmpty();
    // Before the enemy's princess tower, which does not fight back: a Knight would kill it first.
    match.deploy(0, skeleton, Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 22000, "Skeleton");

    assertThatThrownBy(() -> run(match, 200))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("the buff ShieldBoost sets columns not modelled");
  }

  @Test
  @DisplayName("a spawner whose child limits its spawn group is refused as it spawns")
  void aGroupLimitIsRefusedOnASpawner() {
    Standard1v1Battle match = passiveTowers();
    assertThat(GameData.unit("Skeleton_EV1").groupMaxSize()).isEqualTo(8);
    UnitData witch = GameData.unit("Witch").toBuilder().spawnCharacter("Skeleton_EV1").build();
    match.deploy(0, witch, Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Witch");

    assertThatThrownBy(() -> run(match, 400))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("spawning Skeleton_EV1 asks for a limit on its group");
  }

  @Test
  @DisplayName("an action spawning a group-limited row from a character of that row is refused")
  void aGroupLimitIsRefusedOnAnActionFromItsOwnRow() {
    Standard1v1Battle match = passiveTowers();
    UnitData row = GameData.unit("Skeleton_EV1");
    CharacterEntity host =
        match.deploy(0, row, Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Skeleton");
    // A starting action would be refused first on this path, so the child's row goes without one.
    UnitData child = row.toBuilder().onStartingAction(null).build();
    SpawnArguments arguments =
        new SpawnArguments(
            child, 3500, 10000, 1, null, false, 0, 0, false, false, false, false, false, 0, false,
            null, false, 0);

    assertThatThrownBy(() -> host.spawnCharacters(arguments))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("spawning Skeleton_EV1 asks for a limit on its group");
  }

  @Test
  @DisplayName("a tower whose row sets a column the battle does not model is refused")
  void aTowerWithAnUnmodelledColumnIsRefused() {
    Standard1v1Battle match = passiveTowers();
    UnitData tower =
        GameData.unit("PrincessTower").toBuilder().unmodelledColumns(List.of("Unknown")).build();

    assertThatThrownBy(
            () ->
                new TowerEntity(
                    match.getWorld(),
                    tower,
                    "Tower",
                    0,
                    3500,
                    6500,
                    Standard1v1Battle.DEFAULT_LEVEL))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("PrincessTower sets columns the battle does not model: [Unknown]");
  }
}
