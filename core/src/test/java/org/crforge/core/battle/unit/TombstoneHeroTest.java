package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.Version16Tables;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Tombstone's hero form (data version 16.402.18): a building card in the hero slot, played as
 * its hero row, which lists three characters - a dummy, the Tombstone building and the passive
 * monster that holds the ability. The passive monster has no speed, so no movement component, and
 * stands. The ability's tap swaps it onto its active row, with a speed and no lifetime: it is given
 * a movement component and walks, and its drain ends. The building's kill check finds the active
 * monster and kills the building, at the damage drain, so the building still pushes the monster on
 * the tick it is killed; the dummy breaks into its broken row.
 *
 * <p>The scene is the recorded hero Tombstone battle's opening: side 0's hero Tombstone placed at
 * (3500, 14000), its ability tapped 60 ticks after the play.
 */
class TombstoneHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The elixir side 0 waits for before the play: the play's 3 and the ability's 5. */
  private static final int ELIXIR_BEFORE_PLAY = 8;

  /** The Tombstone first, in the hero slot, and seven other cards. */
  private static final List<String> DECK =
      List.of(
          "Tombstone", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  private static final String DUMMY = "TombstoneHero_visual_dummy";
  private static final String BUILDING = "TombstoneHero";
  private static final String PASSIVE = "TombstoneHero_Monster_Passive";
  private static final String ACTIVE = "TombstoneHero_Monster_Active";

  /** The battle and its match, with the Tombstone in side 0's hand. */
  private record Scene(Standard1v1Battle battle, LadderMatch match, BattleRecords records) {}

  /** A Ladder match of 16.402.18 whose side 0 holds the hero Tombstone in its hand. */
  private static Scene scene() {
    GameTables tables = Version16Tables.load();
    BattleRecords records = new BattleRecords(tables);
    int[] heroFirst = new int[8];
    heroFirst[0] = MatchSide.HERO_SLOT;
    for (int word = 0; ; word++) {
      Standard1v1Battle battle = new Standard1v1Battle(tables);
      LadderMatch match = battle.startLadderMatch(DECK, KNIGHTS, word, 0, heroFirst, new int[8]);
      if (inHand(match, "Tombstone")) {
        return new Scene(battle, match, records);
      }
    }
  }

  /** Plays the hero Tombstone at the recorded point and steps the play's tick. */
  private static List<CharacterEntity> play(Scene scene) {
    Standard1v1Battle battle = scene.battle();
    // Enough for the play and, after it, the ability's cost.
    while (scene.match().side(0).wholeElixir() < ELIXIR_BEFORE_PLAY) {
      battle.getBattle().step();
    }
    battle.play(
        battle.getBattle().getTick(),
        scene.records().card("Tombstone"),
        LEVEL,
        0,
        3500,
        14000,
        "t");
    battle.getBattle().step();
    return battle.getPlays().get(battle.getPlays().size() - 1).units();
  }

  @Test
  @DisplayName(
      "a hero slot's Tombstone places its hero row's three characters - the dummy, the building"
          + " and the passive monster - in the list's order at the placed point")
  void theHeroPlayPlacesTheListedCharacters() {
    List<CharacterEntity> units = play(scene());
    assertThat(units)
        .extracting(unit -> unit.getData().name())
        .containsExactly(DUMMY, BUILDING, PASSIVE);
    assertThat(units.get(0).getId()).isLessThan(units.get(1).getId());
    assertThat(units.get(1).getId()).isLessThan(units.get(2).getId());
    for (CharacterEntity unit : units) {
      assertThat(unit.getView().getX()).isEqualTo(3500);
      assertThat(unit.getView().getY()).isEqualTo(13500);
      assertThat(unit.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    }
  }

  @Test
  @DisplayName(
      "the passive monster, a character row without a speed, has no movement component and"
          + " stands once it has deployed")
  void thePassiveMonsterStands() {
    Scene scene = scene();
    CharacterEntity passive = play(scene).get(2);
    assertThat(passive.getView().isMovementComponent()).isFalse();
    for (int i = 0; i < 25; i++) {
      scene.battle().getBattle().step();
    }
    assertThat(passive.getView().getState()).isEqualTo(GridEntityState.STANDING);
    assertThat(passive.getView().getX()).isEqualTo(3500);
    assertThat(passive.getView().getY()).isEqualTo(13500);
  }

  @Test
  @DisplayName(
      "the tap swaps the passive monster onto its active row, which walks with a new movement"
          + " component; the building it kills dies at the damage drain, so it pushes the monster"
          + " once more on that tick, and the dummy breaks")
  void theTapWakesTheMonster() {
    Scene scene = scene();
    Standard1v1Battle battle = scene.battle();
    List<CharacterEntity> units = play(scene);
    CharacterEntity dummy = units.get(0);
    CharacterEntity building = units.get(1);
    CharacterEntity monster = units.get(2);
    int passiveMaximum = monster.getHitPoints().getMaximum();
    int tap = battle.getBattle().getTick() + 59;
    battle.useAbility(tap, 0, monster.name(), "Tombstone_hero_Ability");
    // The tap's step runs the activation group, whose first action swaps the row.
    while (battle.getBattle().getTick() <= tap) {
      battle.getBattle().step();
    }
    assertThat(monster.getData().name()).isEqualTo(ACTIVE);
    assertThat(monster.getView().isMovementComponent()).isTrue();
    assertThat(monster.getView().getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(monster.getHitPoints().getMaximum()).isGreaterThan(passiveMaximum);
    assertThat(monster.getHitPoints().getHitPoints())
        .isEqualTo(monster.getHitPoints().getMaximum());
    assertThat(monster.getHitPoints().getDecayStep()).as("the drain ended").isZero();

    battle.getBattle().step();
    assertThat(dummy.getData().name()).isEqualTo("TombstoneHero_broken_visual_dummy");
    assertThat(HitPoints.alive(building.getHitPoints())).isTrue();
    assertThat(monster.getView().getX()).isEqualTo(3394);
    assertThat(monster.getView().getY()).isEqualTo(13394);

    // The building's kill lands at the drain: it pushes the monster once more on the tick of its
    // death, and leaves the battle at the next cleanup.
    battle.getBattle().step();
    assertThat(HitPoints.alive(building.getHitPoints())).isFalse();
    assertThat(monster.getView().getX()).isEqualTo(3288);
    assertThat(monster.getView().getY()).isEqualTo(13288);
    battle.getBattle().step();
    assertThat(monster.getView().getX()).isEqualTo(3288);
    assertThat(monster.getView().getY()).isEqualTo(13288);
  }

  /** True when side 0's hand holds the card. */
  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }
}
