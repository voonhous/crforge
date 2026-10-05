package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The kinds a game object filter's Filters list may name that no switch column tests, asked by a
 * battle's death listener: IgnoreResurrect leaves out a character whose row sets IgnoreResurrect.
 * The shipped filters here write switches, so the test rewrites the evolved Witch's skeleton filter
 * as the newer data version writes its rows, a Filters list, and adds the kind to it.
 */
class BattleFilterKindsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * The tables with friendly_skeletons_can_be_dead written as a Filters list that also names
   * IgnoreResurrect, and the Skeleton row's IgnoreResurrect set as given.
   */
  private static GameTables ignoreResurrect(Path folder, boolean skeletonIgnores)
      throws IOException {
    GameData.altered(
        folder,
        "game_object_filters",
        rows -> {
          ObjectNode filter = GameData.columns(rows, "friendly_skeletons_can_be_dead");
          for (String column :
              List.of(
                  "FilterClones",
                  "FilterIfNoHitpointComponent",
                  "FilterPrincessTowers",
                  "FilterSummoner")) {
            filter.remove(column);
          }
          ArrayNode kinds = filter.putArray("Filters");
          kinds.add("Clones").add("NoHitpointComponent").add("PrincessTowers").add("Summoner");
          kinds.add("IgnoreResurrect");
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Skeleton").put("IgnoreResurrect", skeletonIgnores));
    return GameTables.load(folder);
  }

  @ParameterizedTest(name = "Skeleton IgnoreResurrect {0}")
  @CsvSource({"false, true", "true, false"})
  @DisplayName(
      "a death listener whose filter lists IgnoreResurrect hears no death of a character whose row"
          + " sets IgnoreResurrect")
  void ignoreResurrectLeavesOutItsCharacters(
      boolean skeletonIgnores, boolean heals, @TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(ignoreResurrect(folder, skeletonIgnores), LEVEL, false);
    CharacterEntity witch = match.deploy(0, GameData.unit("Witch_EV1"), LEVEL, 0, 14500, 14000);
    match.deploy(50, GameData.unit("Musketeer"), LEVEL, 1, 14500, 21500);
    List<CharacterEntity> hers = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int t, SpawnHost source, CharacterEntity child, int x, int y) {
                if (source == witch) {
                  hers.add(child);
                }
              }
            });
    Set<CharacterEntity> dead = new HashSet<>();
    int healed = 0;
    int hp = witch.getHitPoints().getHitPoints();
    for (int tick = 0; tick < 1200 && !witch.isRemovable(); tick++) {
      match.getBattle().step();
      for (CharacterEntity skeleton : hers) {
        if (skeleton.getHitPoints().getHitPoints() <= 0) {
          dead.add(skeleton);
        }
      }
      int now = witch.getHitPoints().getHitPoints();
      if (now > hp) {
        healed++;
      }
      hp = now;
    }

    assertThat(dead).as("some of her skeletons die while she lives").isNotEmpty();
    assertThat(healed > 0).as("a soul of a dead skeleton heals her").isEqualTo(heals);
  }
}
