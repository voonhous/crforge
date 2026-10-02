package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Goblinstein where the reference runs do not take it: a listener's count below its cost, a variant
 * card's play it hears, the tether over units, the ability's run off an area effect that follows,
 * and a Mirror of the card, whose doctor is a champion.
 */
class BattleGoblinsteinTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A Goblinstein and a Merge Maiden, then the Mirrors the shuffle holds back for the refills. */
  private static final List<String> GOBLINSTEIN_MAIDEN =
      List.of(
          "Goblinstein", "MergeMaiden", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  /** A Goblinstein and an Archer, then the Mirrors the shuffle holds back for the refills. */
  private static final List<String> GOBLINSTEIN_MIRRORS =
      List.of("Goblinstein", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  @Test
  @DisplayName(
      "a listener whose count is below its cost adds the cost of each play of its side in its"
          + " group, and the play that finds it reached schedules the action and takes the cost"
          + " off: 0, 5, 10, then 2")
  void aCountBelowTheCostAddsThePlaysCost(@TempDir Path folder) throws IOException {
    // The monster's listener given a cost of 8, which no shipped listener of a modelled card has.
    GameTables tables =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("goblinstein_listen_to_new_deploy").get("fields"))
                    .put("ElixirCost", 8));
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    List<String> heard = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void cardPlayHeard(
                  int tick,
                  WorldEntity owner,
                  String action,
                  int side,
                  String played,
                  String deployed,
                  int total,
                  String scheduled) {
                if (owner.name().equals("g_0")) {
                  heard.add(played + " " + total + " " + scheduled);
                }
              }
            });
    battle.play(0, records.card("Goblinstein"), LEVEL, 0, 3500, 12000, "g");
    battle.play(40, records.card("Goblinstein"), LEVEL, 1, 3500, 22000, "e");
    battle.play(60, records.card("Knight"), LEVEL, 0, 14500, 8000, "k");
    battle.play(80, records.card("Goblinstein"), LEVEL, 0, 14500, 12000, "h");
    battle.play(100, records.card("Goblinstein"), LEVEL, 0, 14500, 8000, "i");
    battle.play(120, records.card("Goblinstein"), LEVEL, 0, 3500, 8000, "j");
    run(battle, 120);

    int cost = records.matchCard("Goblinstein").cost();
    assertThat(cost).isEqualTo(5);
    assertThat(heard)
        .containsExactly(
            "Goblinstein 0 null",
            "Knight 0 null",
            "Goblinstein 5 null",
            "Goblinstein 10 null",
            "Goblinstein 2 goblinstein_set_custom_tag");
  }

  @Test
  @DisplayName("a variant card's play that a listener of its side hears is refused")
  void aVariantPlayHeardIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(GOBLINSTEIN_MAIDEN, KNIGHTS, 0, 0);
    battle.play(21, GameData.card("Goblinstein"), LEVEL, 0, 3500, 10000, "g");
    battle.playVariant(400, "MergeMaiden", LEVEL, 0, 14500, 10000, "m");

    assertThatThrownBy(() -> run(battle, 400))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "MergeMaiden played as MergeMaiden_Mounted while g_0's goblinstein_listen_to_new_deploy"
                + " listens for its side's card plays: a variant card's play, whose kind the"
                + " group's lists answer, is not modelled");
  }

  @Test
  @DisplayName(
      "the tether hits each enemy within its width of the segment from the doctor to the monster"
          + " for 107 at every pass, and spares one farther off")
  void theTetherHitsTheEnemiesAlongIt() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<String> passes = new ArrayList<>();
    List<String> hits = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void tetherDamagePass(
                  int tick,
                  AreaEffectEntity owner,
                  int ax,
                  int ay,
                  int bx,
                  int by,
                  List<WorldEntity> found) {
                passes.add(tick + " " + found.stream().map(WorldEntity::name).toList());
              }

              @Override
              public void tetherHit(
                  int tick,
                  AreaEffectEntity owner,
                  WorldEntity target,
                  int damage,
                  int directionX,
                  int directionY,
                  DamageResult result) {
                hits.add(target.name() + " " + damage);
              }
            });
    battle.play(0, GameData.card("Goblinstein"), LEVEL, 0, 3500, 12000, "g");
    run(battle, 30);
    CharacterEntity monster = battle.getPlays().get(0).units().get(0);
    CharacterEntity doctor = battle.getPlays().get(0).units().get(1);
    // Neither end moves or fights, so the segment stays where it is.
    for (CharacterEntity end : List.of(monster, doctor)) {
      end.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    }
    int ax = doctor.getView().getX();
    int ay = doctor.getView().getY();
    int bx = monster.getView().getX();
    int by = monster.getView().getY();
    // Two Golems, which walk only for buildings: one on the segment's middle, one 4000 off it.
    int tick = battle.getBattle().getTick();
    CharacterEntity near =
        battle.deploy(tick, GameData.unit("Golem"), LEVEL, 1, (ax + bx) / 2, (ay + by) / 2, "near");
    CharacterEntity far =
        battle.deploy(
            tick, GameData.unit("Golem"), LEVEL, 1, (ax + bx) / 2 + 4000, (ay + by) / 2, "far");
    near.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    far.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    run(battle, 70);
    doctor.requestAbility();
    run(battle, 200);

    assertThat(passes).hasSize(8);
    assertThat(passes).allMatch(pass -> pass.endsWith(" [near]"));
    assertThat(hits).hasSize(8).allMatch(hit -> hit.equals("near 107"));
    assertThat(far.getHitPoints().getHitPoints()).isEqualTo(far.getHitPoints().getMaximum());
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
