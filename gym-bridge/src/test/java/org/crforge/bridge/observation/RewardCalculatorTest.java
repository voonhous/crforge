package org.crforge.bridge.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.List;
import org.crforge.bridge.dto.RewardDTO;
import org.crforge.core.card.Card;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RewardCalculatorTest {

  // The time penalty applied every step
  private static final float TIME_PENALTY = -0.0001f;

  private GameEngine engine;
  private GameState state;
  private RewardCalculator calculator;
  private Player bluePlayer;
  private Player redPlayer;

  @BeforeEach
  void setUp() {
    engine = new GameEngine();
    List<Card> deck =
        List.of(
            CardRegistry.get("knight"),
            CardRegistry.get("archer"),
            CardRegistry.get("fireball"),
            CardRegistry.get("arrows"),
            CardRegistry.get("giant"),
            CardRegistry.get("musketeer"),
            CardRegistry.get("minions"),
            CardRegistry.get("valkyrie"));

    LevelConfig levelConfig = new LevelConfig(11);
    bluePlayer = new Player(Team.BLUE, new Deck(deck), false, levelConfig);
    redPlayer = new Player(Team.RED, new Deck(deck), true, levelConfig);

    Standard1v1Match match = new Standard1v1Match(11);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine.setMatch(match);
    engine.initMatch();

    state = engine.getGameState();
    calculator = new RewardCalculator();
    calculator.reset(state, bluePlayer, redPlayer);
  }

  @Test
  void noChangeYieldsOnlyTimePenalty() {
    RewardDTO reward = calculator.computeReward(state);
    // Only the time penalty should be present (no tower damage, no crowns)
    assertThat(reward.blue()).isCloseTo(TIME_PENALTY, offset(1e-6f));
    assertThat(reward.red()).isCloseTo(TIME_PENALTY, offset(1e-6f));
  }

  @Test
  void towerDamageYieldsPositiveReward() {
    // Simulate damage to a red princess tower
    List<Tower> redTowers = state.getTowers().get(Team.RED);
    Tower princessTower =
        redTowers.stream().filter(Tower::isPrincessTower).findFirst().orElseThrow();

    int damageToDeal = 100;
    princessTower.getHealth().takeDamage(damageToDeal);

    RewardDTO reward = calculator.computeReward(state);
    // Blue damaged red -> tower damage reward outweighs time penalty
    assertThat(reward.blue()).isGreaterThan(0f);
    assertThat(reward.red()).isLessThan(0f);
  }

  @Test
  void secondCallWithNoDamageYieldsOnlyTimePenalty() {
    // Damage then compute
    List<Tower> redTowers = state.getTowers().get(Team.RED);
    Tower princessTower =
        redTowers.stream().filter(Tower::isPrincessTower).findFirst().orElseThrow();
    princessTower.getHealth().takeDamage(100);
    calculator.computeReward(state);

    // No additional damage -> only time penalty
    RewardDTO reward2 = calculator.computeReward(state);
    assertThat(reward2.blue()).isCloseTo(TIME_PENALTY, offset(1e-6f));
    assertThat(reward2.red()).isCloseTo(TIME_PENALTY, offset(1e-6f));
  }

  @Test
  void symmetricDamageYieldsOnlyTimePenalty() {
    // Both sides take equal damage
    Tower redTower =
        state.getTowers().get(Team.RED).stream()
            .filter(Tower::isPrincessTower)
            .findFirst()
            .orElseThrow();
    Tower blueTower =
        state.getTowers().get(Team.BLUE).stream()
            .filter(Tower::isPrincessTower)
            .findFirst()
            .orElseThrow();

    redTower.getHealth().takeDamage(100);
    blueTower.getHealth().takeDamage(100);

    RewardDTO reward = calculator.computeReward(state);
    // Symmetric tower damage cancels out, only time penalty remains
    assertThat(reward.blue()).isCloseTo(TIME_PENALTY, offset(1e-6f));
    assertThat(reward.red()).isCloseTo(TIME_PENALTY, offset(1e-6f));
  }

  @Test
  void elixirWastePenaltyWhenCapped() {
    // Simulate elixir at max by running enough ticks
    for (int i = 0; i < 500; i++) {
      bluePlayer.update(1.0f / 30.0f);
    }
    // Blue should be at 10 elixir now
    assertThat(bluePlayer.getElixir().getCurrent()).isGreaterThanOrEqualTo(10.0f);

    RewardDTO reward = calculator.computeReward(state);
    // Blue should have time penalty + elixir waste penalty
    assertThat(reward.blue()).isLessThan(TIME_PENALTY);
  }

  @Test
  void unitKillYieldsPositiveReward() {
    // Spawn a red troop and take snapshot
    Troop redTroop = createTestTroop(Team.RED, 9, 20, 50);
    state.spawnEntity(redTroop);
    state.processPending();

    // Reset calculator to capture the new entity count
    calculator.reset(state, bluePlayer, redPlayer);

    // Kill the red troop
    redTroop.getHealth().takeDamage(50);
    state.processDeaths();

    RewardDTO reward = calculator.computeReward(state);
    // Blue killed a red unit -> positive reward (beyond just time penalty)
    assertThat(reward.blue()).isGreaterThan(0f);
    assertThat(reward.red()).isLessThan(TIME_PENALTY);
  }

  @Test
  void unitDamageYieldsPositiveReward() {
    // Spawn a red troop
    Troop redTroop = createTestTroop(Team.RED, 9, 20, 200);
    state.spawnEntity(redTroop);
    state.processPending();

    calculator.reset(state, bluePlayer, redPlayer);

    // Damage but don't kill the red troop
    redTroop.getHealth().takeDamage(100);

    RewardDTO reward = calculator.computeReward(state);
    // Blue dealt unit damage to red -> positive reward
    assertThat(reward.blue()).isGreaterThan(0f);
  }

  private Troop createTestTroop(Team team, float x, float y, int hp) {
    return Troop.builder()
        .name("TestTroop")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .deployTime(0f)
        .combat(
            Combat.builder().damage(10).range(1.5f).sightRange(5.5f).attackCooldown(1.0f).build())
        .build();
  }
}
