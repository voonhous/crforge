package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.GameTags;
import org.crforge.core.battle.spawn.SpawnCharacters;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The battle's actions built from the game's action rows: each class from its columns, the actions
 * a row names built in turn, game tags as their bits, expressions compiled for the entity, and
 * every row of the data either built or refused by name.
 */
class ActionRowsTest {

  /** A binding that compiles nothing: every expression answers 0, every variable its hash. */
  private static final ActionBinding INERT_BINDING =
      new ActionBinding() {
        @Override
        public IntSupplier expression(String text) {
          return () -> 0;
        }

        @Override
        public int variableKey(String name) {
          return name.hashCode();
        }

        @Override
        public LongSupplier tags() {
          return () -> 0;
        }
      };

  private static List<String> queue(ActionHolder holder) {
    return holder.queued().stream().map(q -> q.action().name() + " " + q.ticks()).toList();
  }

  @Test
  @DisplayName("a group row schedules its parts at their own delays, each built from its row")
  void aGroupOfSpawns() {
    BattleAction group = GameData.actions().build("SuspiciousBush_SpawnBushGoblin", INERT_BINDING);
    ActionHolder holder = new ActionHolder();
    holder.schedule(group, ActionHolder.OWN_DELAY);
    assertThat(queue(holder))
        .containsExactly(
            "SuspiciousBush_SpawnBushGoblin 0",
            "SuspiciousBush_SpawnBushGoblin1 13",
            "SuspiciousBush_SpawnBushGoblin2 12");
    assertThat(holder.queued().get(1).action()).isInstanceOf(SpawnCharacters.class);
  }

  @Test
  @DisplayName("game tags are the bits of their rows, a list of them the bits of all")
  void gameTags() {
    ActionRows rows = GameData.actions();
    assertThat(rows.tagMask("INACTIVE")).isEqualTo(GameTags.INACTIVE);
    assertThat(rows.tagMask("ACTIVATING")).isEqualTo(GameTags.ACTIVATING);
    assertThat(rows.tagMask("INACTIVE, ACTIVATING"))
        .isEqualTo(GameTags.INACTIVE | GameTags.ACTIVATING);
    assertThatThrownBy(() -> rows.tagMask("NO_SUCH_TAG"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NO_SUCH_TAG");
  }

  @Test
  @DisplayName("a set-variable row writes its value to the variable the battle declares")
  void aVariableRow() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    TowerEntity tower = (TowerEntity) match.getBattle().getHolder().entities().get(0);
    BattleAction row =
        GameData.actions()
            .build("MiniPekkaHero_set_ability_played", match.getWorld().binding(tower));
    tower.actionHolder().start(row);
    int key = match.getWorld().binding(tower).variableKey("MiniPekkaHero_ability_played");
    assertThat(tower.variable(key)).isEqualTo(1);
  }

  @Test
  @DisplayName("a row whose tree reaches a class the battle does not have is refused, naming it")
  void anUnmodelledClassIsRefused() {
    assertThatThrownBy(() -> GameData.actions().build("KingTower_StartingGroup", INERT_BINDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("ActionPlayEffect");
  }

  @Test
  @DisplayName("every row of the data is built or refused by name, never failed")
  void everyRowIsBuiltOrRefused() {
    ActionRows rows = GameData.actions();
    int built = 0;
    Map<String, Integer> refusals = new TreeMap<>();
    List<String> failures = new ArrayList<>();
    for (String name : GameData.tables().actionNames()) {
      try {
        rows.build(name, INERT_BINDING);
        built++;
      } catch (UnsupportedOperationException e) {
        String message = e.getMessage();
        String reason =
            message.contains(" is an ")
                ? "class"
                : message.contains(" sets ")
                    ? "column"
                    : message.contains(" spawns ") ? "spawn type" : "other";
        refusals.merge(reason, 1, Integer::sum);
        if (reason.equals("other")) {
          failures.add(name + ": " + message);
        }
      } catch (RuntimeException e) {
        failures.add(name + ": " + e);
      }
    }
    assertThat(failures).as("rows that fail instead of being built or refused").isEmpty();
    assertThat(built + refusals.values().stream().mapToInt(Integer::intValue).sum())
        .isEqualTo(GameData.tables().actionNames().size());
    // Pinned, so a change in what the battle builds shows here: of 946 rows, 207 are built; the
    // rest are refused for their class, a column the battle does not model or a spawn type other
    // than characters.
    assertThat(built).as("rows built").isEqualTo(207);
    assertThat(refusals)
        .containsExactlyInAnyOrderEntriesOf(Map.of("class", 506, "column", 51, "spawn type", 182));
  }
}
