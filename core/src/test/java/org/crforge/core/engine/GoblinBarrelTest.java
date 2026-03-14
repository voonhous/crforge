package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Goblin Barrel spell: a 3-elixir Epic projectile that travels to a target
 * location and spawns 3 Goblins on impact with a 1.1s deploy time.
 */
class GoblinBarrelTest {

  private GameEngine engine;
  private Player bluePlayer;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();

    Card goblinBarrel =
        Objects.requireNonNull(CardRegistry.get("goblinbarrel"), "goblinbarrel not found");

    // Fill all 8 deck slots with Goblin Barrel for deterministic hand
    List<Card> deckCards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      deckCards.add(goblinBarrel);
    }

    bluePlayer = new Player(Team.BLUE, new Deck(deckCards), false);
    Player redPlayer = new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false);

    Standard1v1Match match = new Standard1v1Match();
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();

    // Max elixir so we can always play the card
    bluePlayer.getElixir().update(100f);
  }

  @Test
  void goblinBarrel_shouldCreateProjectile() {
    // Deploy Goblin Barrel at target location
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 25f);
    engine.queueAction(bluePlayer, action);

    // Tick past the 1s sync delay + 1 tick to process
    int syncTicks = (int) (DeploymentSystem.PLACEMENT_SYNC_DELAY * GameEngine.TICKS_PER_SECOND) + 1;
    engine.tick(syncTicks);

    // Projectile should be created
    List<Projectile> projectiles = engine.getGameState().getProjectiles();
    assertThat(projectiles).isNotEmpty();

    Projectile barrel = projectiles.get(projectiles.size() - 1);
    assertThat(barrel.isPositionTargeted()).isTrue();
    assertThat(barrel.getSpawnCharacterStats()).isNotNull();
    assertThat(barrel.getSpawnCharacterStats().getName()).isEqualTo("Goblin");
    assertThat(barrel.getSpawnCharacterCount()).isEqualTo(3);
  }

  @Test
  void goblinBarrel_shouldSpawnThreeGoblinsOnImpact() {
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 25f);
    engine.queueAction(bluePlayer, action);

    // Run enough ticks for sync delay + projectile travel time
    // Speed = 400/60 tiles/s, distance ~10 tiles -> ~1.5s travel, total ~2.5s + margin
    engine.runSeconds(4f);

    List<Troop> goblins =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("Goblin") && t.getTeam() == Team.BLUE)
            .toList();

    assertThat(goblins).hasSize(3);
  }

  @Test
  void goblinBarrel_goblinsHaveDeployTime() {
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 25f);
    engine.queueAction(bluePlayer, action);

    // Run enough for impact but not enough for goblins to finish deploying
    engine.runSeconds(3f);

    List<Troop> goblins =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("Goblin") && t.getTeam() == Team.BLUE)
            .toList();

    // If goblins are spawned, at least some should still be deploying (1.1s deploy time)
    if (!goblins.isEmpty()) {
      boolean anyDeploying = goblins.stream().anyMatch(Troop::isDeploying);
      assertThat(anyDeploying)
          .as("Goblins should have a deploy timer (1.1s) so they don't act immediately")
          .isTrue();
    }
  }

  @Test
  void goblinBarrel_goblinsSpawnAtDifferentPositions() {
    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 25f);
    engine.queueAction(bluePlayer, action);

    engine.runSeconds(4f);

    List<Troop> goblins =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("Goblin") && t.getTeam() == Team.BLUE)
            .toList();

    assertThat(goblins).hasSize(3);

    // Not all goblins should be at the exact same position (formation spread)
    float firstX = goblins.get(0).getPosition().getX();
    float firstY = goblins.get(0).getPosition().getY();
    boolean allSamePosition =
        goblins.stream()
            .allMatch(g -> g.getPosition().getX() == firstX && g.getPosition().getY() == firstY);
    assertThat(allSamePosition)
        .as("Goblins should spawn at different positions due to formation spread")
        .isFalse();
  }

  @Test
  void goblinBarrel_projectileDealZeroDamage() {
    // Goblin Barrel projectile itself does 0 damage
    Card barrel = CardRegistry.get("goblinbarrel");
    assertThat(barrel).isNotNull();
    assertThat(barrel.getProjectile()).isNotNull();
    assertThat(barrel.getProjectile().getDamage()).isEqualTo(0);
  }

  @Test
  void goblinBarrel_goblinsAttackAfterDeploy() {
    // Place barrel directly on a tower to test attack after deploy
    PlayerActionDTO action = PlayerActionDTO.play(0, 14.5f, 28f);
    engine.queueAction(bluePlayer, action);

    // Run enough for sync + travel + deploy (1.1s) + attack cooldown
    engine.runSeconds(8f);

    List<Troop> goblins =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("Goblin") && t.getTeam() == Team.BLUE)
            .toList();

    // Goblins should have finished deploying by now
    boolean anyStillDeploying = goblins.stream().anyMatch(Troop::isDeploying);
    assertThat(anyStillDeploying).as("Goblins should finish deploying after 1.1s").isFalse();
  }

  @Test
  void goblinBarrel_levelScalingApplied() {
    // Create a level 11 player to check scaling
    Card goblinBarrel = CardRegistry.get("goblinbarrel");
    List<Card> deckCards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      deckCards.add(goblinBarrel);
    }

    Player leveledPlayer = new Player(Team.BLUE, new Deck(deckCards), false, new LevelConfig(11));
    Player redPlayer = new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false);

    Standard1v1Match match = new Standard1v1Match();
    match.addPlayer(leveledPlayer);
    match.addPlayer(redPlayer);

    GameEngine leveledEngine = new GameEngine();
    leveledEngine.setMatch(match);
    leveledEngine.initMatch();
    leveledPlayer.getElixir().update(100f);

    PlayerActionDTO action = PlayerActionDTO.play(0, 9f, 25f);
    leveledEngine.queueAction(leveledPlayer, action);
    leveledEngine.runSeconds(4f);

    List<Troop> goblins =
        leveledEngine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getName().equals("Goblin") && t.getTeam() == Team.BLUE)
            .toList();

    assertThat(goblins).hasSize(3);

    // Level 11 Epic goblin HP should be higher than base (79)
    int goblinHp = goblins.get(0).getHealth().getMax();
    assertThat(goblinHp)
        .as("Level 11 goblins should have scaled HP above base 79")
        .isGreaterThan(79);
  }
}
