package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.battle.action.RunIfGameObjectExists;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check whether objects exist, on the battle's own units, one rule at a time: it counts the
 * owner's battle's live objects through its filter, the owner among them; one excluded object
 * vetoes it; the named rows count up to the number needed; without names the filtered objects
 * themselves count; and the branch it takes runs on the owner, with the owner as its cause.
 */
class BattleRunIfExistsTest {

  private final List<String> log = new ArrayList<>();

  /** A branch that records the owner it ran on and its cause's owner. */
  private BattleAction branch(String name) {
    return new RowAction(ActionRow.named(name)) {
      @Override
      public ActionInstance start(ActionHolder holder) {
        return start(holder, null);
      }

      @Override
      public ActionInstance start(ActionHolder holder, ActionHolder instigator) {
        WorldEntity owner = (WorldEntity) holder.getOwner();
        WorldEntity cause = instigator == null ? null : (WorldEntity) instigator.getOwner();
        log.add(name + " on " + owner.name() + " by " + (cause == null ? null : cause.name()));
        return null;
      }
    };
  }

  /**
   * A bottom-side Knight, a RascalGirl and a Firecracker, and a top-side Assassin, all admitted.
   */
  private static final class Scene {
    final Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    final Battle battle = match.getBattle();
    final CharacterEntity knight;

    Scene(boolean withFirecracker) {
      knight = match.deploy(0, GameData.unit("Knight"), 11, 0, 3500, 10000, "Knight");
      match.deploy(0, GameData.unit("RascalGirl"), 11, 0, 4500, 10000, "RascalGirl");
      match.deploy(0, GameData.unit("Assassin"), 11, 1, 3500, 22000, "Assassin");
      if (withFirecracker) {
        match.deploy(0, GameData.unit("Firecracker"), 11, 0, 2500, 10000, "Firecracker");
      }
      // An action owner and a projectile-free battle: the live list holds more than characters.
      match.addActionOwner("Owner", 0, 9000, 16000, 10);
      battle.step();
    }
  }

  private void check(
      Scene scene, List<String> match, List<String> exclude, int needed, boolean filtered) {
    GameObjectFilter filter =
        filtered ? GameData.records().filter("friendly_troop_no_buildings") : null;
    RunIfGameObjectExists check =
        new RunIfGameObjectExists(
            ActionRow.named("check"),
            filter,
            ids(match),
            ids(exclude),
            needed,
            branch("found"),
            branch("not_found"));
    // Outside a pending pass the branch is queued; the next step's phase-1 pass runs it.
    scene.knight.actionHolder().start(check, null);
    scene.battle.step();
  }

  /** Row names as the check compares them: their global ids. */
  private static List<Integer> ids(List<String> names) {
    return names.stream().map(name -> GameData.records().unitGlobalId(name)).toList();
  }

  @Test
  @DisplayName("a named row among the filtered objects is a match; the owner counts too")
  void aNamedRowMatches() {
    Scene scene = new Scene(false);
    check(scene, List.of("RascalBoy", "RascalGirl"), List.of(), 1, true);
    check(scene, List.of("Knight"), List.of(), 1, true);
    check(scene, List.of("Assassin"), List.of(), 1, true);

    assertThat(log)
        .containsExactly(
            "found on Knight by Knight",
            "found on Knight by Knight",
            "not_found on Knight by Knight");
  }

  @Test
  @DisplayName("one excluded object among the filtered ones vetoes the whole check")
  void anExcludedObjectVetoes() {
    Scene scene = new Scene(true);
    check(scene, List.of("RascalGirl"), List.of("Assassin", "Firecracker"), 1, true);
    // The Assassin is on the other side, so the filter never shows it: it vetoes nothing.
    check(scene, List.of("RascalGirl"), List.of("Assassin"), 1, true);

    assertThat(log).containsExactly("not_found on Knight by Knight", "found on Knight by Knight");
  }

  @Test
  @DisplayName("named rows count up to the number needed, each object once")
  void theNumberNeeded() {
    Scene scene = new Scene(true);
    check(scene, List.of("RascalGirl", "Firecracker"), List.of(), 2, true);
    check(scene, List.of("RascalGirl", "Firecracker"), List.of(), 3, true);
    check(scene, List.of("RascalGirl", "RascalGirl"), List.of(), 2, true);
    // The count is tested after every object: none needed matches on the first, whatever it is.
    check(scene, List.of("Assassin"), List.of(), 0, true);

    assertThat(log)
        .containsExactly(
            "found on Knight by Knight",
            "not_found on Knight by Knight",
            "not_found on Knight by Knight",
            "found on Knight by Knight");
  }

  @Test
  @DisplayName("without names the filtered objects count, at least one of them")
  void withoutNames() {
    Scene scene = new Scene(true);
    // Knight, RascalGirl and Firecracker pass the filter.
    check(scene, List.of(), List.of(), 3, true);
    check(scene, List.of(), List.of(), 4, true);
    check(scene, List.of(), List.of(), 0, true);

    assertThat(log)
        .containsExactly(
            "found on Knight by Knight",
            "not_found on Knight by Knight",
            "found on Knight by Knight");
  }

  @Test
  @DisplayName("without a filter the check does nothing at all")
  void withoutAFilter() {
    Scene scene = new Scene(true);
    check(scene, List.of("Knight"), List.of(), 1, false);

    assertThat(log).isEmpty();
  }
}
