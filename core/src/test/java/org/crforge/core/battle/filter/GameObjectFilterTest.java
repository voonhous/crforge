package org.crforge.core.battle.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The game object filter test as its recorded cases pin it: the team gate, the type gate, the tag
 * exclusion, the slot exclusions in their order, the name comparison, the character-only block and
 * the two character lists.
 */
class GameObjectFilterTest {

  /** A subject whose every answer a case sets, which records the questions it was asked. */
  private static final class Subject implements FilterSubject {
    int type = FilterSubject.CHARACTER;
    int side;
    long tags;
    boolean crownTower;
    boolean building;
    boolean alive = true;
    boolean hidden;
    boolean underground;
    boolean summoner;
    boolean flying;
    boolean hasHitPoints = true;
    boolean princessTower;
    boolean clone;
    boolean attachedChild;
    String name = "Knight";
    int state;
    int dashImmuneMs;
    int invisible;
    boolean ignoresPushback;
    final List<String> asked = new ArrayList<>();

    private boolean ask(String question, boolean answer) {
      asked.add(question);
      return answer;
    }

    @Override
    public int objectType() {
      return type;
    }

    @Override
    public int team() {
      return side & 1;
    }

    @Override
    public long tags() {
      return tags;
    }

    @Override
    public boolean crownTower() {
      return ask("crownTower", crownTower);
    }

    @Override
    public boolean building() {
      return ask("building", building);
    }

    @Override
    public boolean alive() {
      return ask("alive", alive);
    }

    @Override
    public boolean hidden() {
      return ask("hidden", hidden);
    }

    @Override
    public boolean underground() {
      return ask("underground", underground);
    }

    @Override
    public boolean summoner() {
      return ask("summoner", summoner);
    }

    @Override
    public boolean flying() {
      return ask("flying", flying);
    }

    @Override
    public boolean hasHitPoints() {
      return ask("hasHitPoints", hasHitPoints);
    }

    @Override
    public boolean princessTower() {
      return ask("princessTower", princessTower);
    }

    @Override
    public boolean isClone() {
      return ask("clone", clone);
    }

    @Override
    public boolean attachedChild() {
      return attachedChild;
    }

    @Override
    public String rowName() {
      return name;
    }

    @Override
    public int state() {
      return state;
    }

    @Override
    public int dashImmuneMs() {
      return dashImmuneMs;
    }

    @Override
    public int invisibleCounter() {
      return invisible;
    }

    @Override
    public boolean ignoresPushback() {
      return ignoresPushback;
    }
  }

  private static Subject subject(UnaryOperator<Subject> setup) {
    return setup.apply(new Subject());
  }

  private static GameObjectFilter.GameObjectFilterBuilder own() {
    return GameObjectFilter.builder().matchTeamOwn(true).matchTypeCharacters(true);
  }

  private static int matches(GameObjectFilter filter, Subject subject) {
    return matches(filter, subject, 0, "Other");
  }

  private static int matches(GameObjectFilter filter, Subject subject, int team, String name) {
    subject.asked.clear();
    return filter.matches(subject, team, name) ? 1 : 0;
  }

  @Test
  @DisplayName("the team gate: own or enemy by the side's low bit, neither accepting nothing")
  void teamGate() {
    List<Integer> got = new ArrayList<>();
    for (boolean ownColumn : new boolean[] {true, false}) {
      for (int side : new int[] {0, 1}) {
        GameObjectFilter f =
            GameObjectFilter.builder()
                .matchTeamOwn(ownColumn)
                .matchTeamEnemy(!ownColumn)
                .matchTypeCharacters(true)
                .build();
        got.add(
            matches(
                f,
                subject(
                    s -> {
                      s.side = side;
                      return s;
                    })));
      }
    }
    assertThat(got).containsExactly(1, 0, 0, 1);
    assertThat(
            matches(
                own().build(),
                subject(
                    s -> {
                      s.side = 2;
                      return s;
                    })))
        .as("side 2 is own against team 0")
        .isEqualTo(1);
    for (int side : new int[] {0, 1}) {
      assertThat(
              matches(
                  GameObjectFilter.builder().matchTypeCharacters(true).build(),
                  subject(
                      s -> {
                        s.side = side;
                        return s;
                      })))
          .as("neither team column accepts nothing")
          .isZero();
    }
  }

  @Test
  @DisplayName(
      "the type gate: each kind by its own column, a character by three, other kinds never")
  void typeGate() {
    GameObjectFilter.GameObjectFilterBuilder both =
        GameObjectFilter.builder()
            .matchTeamOwn(true)
            .matchTeamEnemy(true)
            .matchTypeCharacters(true);
    int[] types = {FilterSubject.PROJECTILE, FilterSubject.AREA_EFFECT, FilterSubject.GOBLIN_REF};
    for (int type : types) {
      GameObjectFilter.GameObjectFilterBuilder with =
          GameObjectFilter.builder()
              .matchTeamOwn(true)
              .matchTeamEnemy(true)
              .matchTypeCharacters(true);
      if (type == FilterSubject.PROJECTILE) {
        with.matchTypeProjectiles(true);
      } else if (type == FilterSubject.AREA_EFFECT) {
        with.matchTypeAoe(true);
      } else {
        with.matchTypeGoblinRef(true);
      }
      assertThat(
              matches(
                  with.build(),
                  subject(
                      s -> {
                        s.type = type;
                        return s;
                      })))
          .as("type %d with its column", type)
          .isEqualTo(1);
      assertThat(
              matches(
                  both.build(),
                  subject(
                      s -> {
                        s.type = type;
                        return s;
                      })))
          .as("type %d without", type)
          .isZero();
    }
    GameObjectFilter everything =
        GameObjectFilter.builder()
            .matchTeamOwn(true)
            .matchTeamEnemy(true)
            .matchTypeCharacters(true)
            .matchTypeProjectiles(true)
            .matchTypeAoe(true)
            .matchTypeGoblinRef(true)
            .matchTowers(true)
            .matchTypeBuildings(true)
            .build();
    for (int type : new int[] {0, 1, 2, 7, 8}) {
      assertThat(
              matches(
                  everything,
                  subject(
                      s -> {
                        s.type = type;
                        return s;
                      })))
          .as("type %d is never accepted", type)
          .isZero();
    }

    GameObjectFilter towers =
        GameObjectFilter.builder().matchTeamOwn(true).matchTowers(true).build();
    GameObjectFilter buildings =
        GameObjectFilter.builder().matchTeamOwn(true).matchTypeBuildings(true).build();
    assertThat(
            List.of(
                matches(both.build(), subject(s -> s)),
                matches(
                    GameObjectFilter.builder().matchTeamOwn(true).matchTeamEnemy(true).build(),
                    subject(s -> s)),
                matches(
                    towers,
                    subject(
                        s -> {
                          s.crownTower = true;
                          return s;
                        })),
                matches(towers, subject(s -> s)),
                matches(
                    buildings,
                    subject(
                        s -> {
                          s.building = true;
                          return s;
                        })),
                matches(buildings, subject(s -> s))))
        .containsExactly(1, 0, 1, 0, 1, 0);
  }

  @Test
  @DisplayName("the tag mask rejects any shared bit; an empty mask rejects nothing")
  void filterTags() {
    GameObjectFilter f = own().filterTags(0b0101).build();
    List<Integer> got = new ArrayList<>();
    for (long tags : new long[] {0, 0b0001, 0b0100, 0b0101, 0b1010}) {
      got.add(
          matches(
              f,
              subject(
                  s -> {
                    s.tags = tags;
                    return s;
                  })));
    }
    assertThat(got).containsExactly(1, 0, 0, 0, 1);
    assertThat(
            matches(
                own().build(),
                subject(
                    s -> {
                      s.tags = -1L;
                      return s;
                    })))
        .isEqualTo(1);
  }

  /** One slot exclusion: its column, the question it asks and the answer that rejects. */
  private record Exclusion(
      String column,
      String question,
      boolean rejectsWhen,
      UnaryOperator<GameObjectFilter.GameObjectFilterBuilder> set,
      BiConsumer<Subject, Boolean> answer) {}

  private static final List<Exclusion> EXCLUSIONS =
      List.of(
          new Exclusion(
              "FilterDead", "alive", false, b -> b.filterDead(true), (s, v) -> s.alive = v),
          new Exclusion(
              "FilterBuildings",
              "building",
              true,
              b -> b.filterBuildings(true),
              (s, v) -> s.building = v),
          new Exclusion(
              "FilterHidden", "hidden", true, b -> b.filterHidden(true), (s, v) -> s.hidden = v),
          new Exclusion(
              "FilterUnderground",
              "underground",
              true,
              b -> b.filterUnderground(true),
              (s, v) -> s.underground = v),
          new Exclusion(
              "FilterTowers",
              "crownTower",
              true,
              b -> b.filterTowers(true),
              (s, v) -> s.crownTower = v),
          new Exclusion(
              "FilterSummoner",
              "summoner",
              true,
              b -> b.filterSummoner(true),
              (s, v) -> s.summoner = v),
          new Exclusion(
              "FilterFlying", "flying", true, b -> b.filterFlying(true), (s, v) -> s.flying = v),
          new Exclusion(
              "FilterIfNoHitpointComponent",
              "hasHitPoints",
              false,
              b -> b.filterIfNoHitpointComponent(true),
              (s, v) -> s.hasHitPoints = v),
          new Exclusion(
              "FilterPrincessTowers",
              "princessTower",
              true,
              b -> b.filterPrincessTowers(true),
              (s, v) -> s.princessTower = v),
          new Exclusion(
              "FilterClones", "clone", true, b -> b.filterClones(true), (s, v) -> s.clone = v));

  @Test
  @DisplayName("each slot exclusion asks only when set, rejects on its answer, in a fixed order")
  void exclusions() {
    for (Exclusion e : EXCLUSIONS) {
      GameObjectFilter on = e.set.apply(own().filterDead(false)).build();
      List<Integer> got = new ArrayList<>();
      for (boolean answer : new boolean[] {false, true}) {
        Subject s = new Subject();
        e.answer.accept(s, answer);
        got.add(matches(on, s));
        assertThat(s.asked).as("%s asks", e.column).contains(e.question);
      }
      assertThat(got).as(e.column).containsExactly(e.rejectsWhen ? 1 : 0, e.rejectsWhen ? 0 : 1);

      Subject clear = new Subject();
      e.answer.accept(clear, e.rejectsWhen);
      assertThat(matches(own().filterDead(false).build(), clear))
          .as("%s clear accepts", e.column)
          .isEqualTo(1);
      assertThat(clear.asked).as("%s clear asks nothing", e.column).doesNotContain(e.question);
    }

    GameObjectFilter.GameObjectFilterBuilder all = own();
    for (Exclusion e : EXCLUSIONS) {
      e.set.apply(all);
    }
    GameObjectFilter every = all.build();
    Subject failsAll = new Subject();
    for (Exclusion e : EXCLUSIONS) {
      e.answer.accept(failsAll, e.rejectsWhen);
    }
    assertThat(matches(every, failsAll)).isZero();
    assertThat(failsAll.asked).as("only the first is asked").containsExactly("alive");

    Subject passesAll = new Subject();
    for (Exclusion e : EXCLUSIONS) {
      e.answer.accept(passesAll, !e.rejectsWhen);
    }
    assertThat(matches(every, passesAll)).isEqualTo(1);
    assertThat(passesAll.asked)
        .as("in this order")
        .containsExactlyElementsOf(EXCLUSIONS.stream().map(Exclusion::question).toList());
  }

  @Test
  @DisplayName("FilterDead is the one column set by default")
  void filterDeadDefault() {
    assertThat(GameObjectFilter.builder().build().isFilterDead()).isTrue();
    assertThat(
            matches(
                own().build(),
                subject(
                    s -> {
                      s.alive = false;
                      return s;
                    })))
        .isZero();
  }

  @Test
  @DisplayName("FilterSameObjects compares the whole row name with the name given")
  void sameObjects() {
    GameObjectFilter on = own().filterSameObjects(true).build();
    assertThat(
            List.of(
                matches(on, subject(s -> s), 0, "Knight"),
                matches(on, subject(s -> s), 0, "Archer"),
                matches(own().build(), subject(s -> s), 0, "Knight"),
                matches(on, subject(s -> s), 0, "Knight_EV1")))
        .containsExactly(0, 1, 1, 1);
  }

  @Test
  @DisplayName("the character-only block: attached children, states, dash immunity, invisibility")
  void characterOnly() {
    assertThat(
            List.of(
                matches(own().build(), subject(s -> s)),
                matches(
                    own().build(),
                    subject(
                        s -> {
                          s.attachedChild = true;
                          return s;
                        })),
                matches(
                    own().matchAttachedChildren(true).build(),
                    subject(
                        s -> {
                          s.attachedChild = true;
                          return s;
                        }))))
        .containsExactly(1, 0, 1);

    int[] states = {0, 3, 5, 8, 12, 13, 14};
    Object[][] stateColumns = {
      {"FilterJumping", own().filterJumping(true).build(), Set.of(5)},
      {"FilterDragging", own().filterDragging(true).build(), Set.of(12, 13)},
      {"FilterCloning", own().filterCloning(true).build(), Set.of(8)}
    };
    for (Object[] c : stateColumns) {
      for (int state : states) {
        @SuppressWarnings("unchecked")
        Set<Integer> rejected = (Set<Integer>) c[2];
        assertThat(
                matches(
                    (GameObjectFilter) c[1],
                    subject(
                        s -> {
                          s.state = state;
                          return s;
                        })))
            .as("%s state %d", c[0], state)
            .isEqualTo(rejected.contains(state) ? 0 : 1);
      }
    }

    GameObjectFilter dash = own().filterDashImmune(true).build();
    assertThat(
            List.of(
                matches(
                    dash,
                    subject(
                        s -> {
                          s.state = 3;
                          s.dashImmuneMs = 1;
                          return s;
                        })),
                matches(
                    dash,
                    subject(
                        s -> {
                          s.state = 3;
                          return s;
                        })),
                matches(
                    dash,
                    subject(
                        s -> {
                          s.state = 5;
                          s.dashImmuneMs = 1;
                          return s;
                        }))))
        .containsExactly(0, 1, 1);

    GameObjectFilter invisible = own().filterInvisible(true).build();
    List<Integer> got = new ArrayList<>();
    for (int n : new int[] {0, 1, 5, -1}) {
      got.add(
          matches(
              invisible,
              subject(
                  s -> {
                    s.invisible = n;
                    return s;
                  })));
    }
    assertThat(got).containsExactly(1, 0, 0, 1);

    GameObjectFilter pushback = own().filterPushbackIgnore(true).build();
    assertThat(
            List.of(
                matches(pushback, subject(s -> s)),
                matches(
                    pushback,
                    subject(
                        s -> {
                          s.ignoresPushback = true;
                          return s;
                        }))))
        .containsExactly(1, 0);

    GameObjectFilter projectiles =
        GameObjectFilter.builder()
            .matchTeamOwn(true)
            .matchTypeProjectiles(true)
            .filterJumping(true)
            .filterInvisible(true)
            .filterPushbackIgnore(true)
            .build();
    assertThat(
            matches(
                projectiles,
                subject(
                    s -> {
                      s.type = FilterSubject.PROJECTILE;
                      s.state = 5;
                      s.attachedChild = true;
                      s.invisible = 1;
                      s.ignoresPushback = true;
                      return s;
                    })))
        .as("the character-only block is skipped for other kinds")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("the include list, then the exclude list, and never for a non-character")
  void characterLists() {
    assertThat(
            List.of(
                matches(own().includeCharactersWithData(Set.of("Knight")).build(), subject(s -> s)),
                matches(
                    own().includeCharactersWithData(Set.of("Archer", "Knight")).build(),
                    subject(s -> s)),
                matches(
                    own().includeCharactersWithData(Set.of("Archer")).build(), subject(s -> s))))
        .containsExactly(1, 1, 0);
    assertThat(
            List.of(
                matches(own().excludeCharactersWithData(Set.of("Knight")).build(), subject(s -> s)),
                matches(
                    own().excludeCharactersWithData(Set.of("Archer", "Knight")).build(),
                    subject(s -> s)),
                matches(
                    own().excludeCharactersWithData(Set.of("Archer")).build(), subject(s -> s))))
        .containsExactly(0, 0, 1);
    assertThat(
            matches(
                own()
                    .includeCharactersWithData(Set.of("Knight"))
                    .excludeCharactersWithData(Set.of("Knight"))
                    .build(),
                subject(s -> s)))
        .isZero();
    assertThat(
            matches(
                GameObjectFilter.builder()
                    .matchTeamOwn(true)
                    .matchTypeProjectiles(true)
                    .includeCharactersWithData(Set.of("Archer"))
                    .build(),
                subject(
                    s -> {
                      s.type = FilterSubject.PROJECTILE;
                      return s;
                    })))
        .isEqualTo(1);
  }
}
