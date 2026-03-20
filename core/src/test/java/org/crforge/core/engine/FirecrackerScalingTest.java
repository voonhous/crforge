package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.Rarity;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
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
 * Verifies that Firecracker shrapnel (fan sub-projectile) damage is scaled by the attacker's level,
 * not stuck at base level-1 values.
 */
class FirecrackerScalingTest {

  private static final int TEST_LEVEL = 11;

  private GameEngine engine;
  private Player bluePlayer;
  private Player redPlayer;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
  }

  @Test
  void firecrackerShrapnel_shouldHaveScaledDamage() {
    Card firecracker =
        Objects.requireNonNull(CardRegistry.get("firecracker"), "firecracker not found");
    List<Card> blueDeck = new ArrayList<>(Collections.nCopies(8, firecracker));

    // Use Giant as a tanky target that the Firecracker will shoot at
    Card giant = Objects.requireNonNull(CardRegistry.get("giant"), "giant not found");
    List<Card> redDeck = new ArrayList<>(Collections.nCopies(8, giant));

    bluePlayer = new Player(Team.BLUE, new Deck(blueDeck), false, new LevelConfig(TEST_LEVEL));
    bluePlayer.getElixir().update(100f);

    redPlayer = new Player(Team.RED, new Deck(redDeck), false, new LevelConfig(TEST_LEVEL));
    redPlayer.getElixir().update(100f);

    Standard1v1Match match = new Standard1v1Match();
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();

    // Deploy Firecracker for blue near the center
    engine.queueAction(bluePlayer, PlayerActionDTO.play(0, 9f, 14f));
    // Deploy Giant for red slightly ahead so Firecracker attacks it
    engine.queueAction(redPlayer, PlayerActionDTO.play(0, 9f, 18f));

    int syncTicks = (int) (DeploymentSystem.PLACEMENT_SYNC_DELAY * GameEngine.TICKS_PER_SECOND);
    engine.tick(syncTicks + 1);

    // Verify our units deployed
    Troop firecrackerTroop =
        engine.getGameState().getEntities().stream()
            .filter(e -> e.getTeam() == Team.BLUE && e instanceof Troop && !(e instanceof Tower))
            .map(Troop.class::cast)
            .findFirst()
            .orElse(null);
    assertThat(firecrackerTroop).as("Firecracker should be deployed").isNotNull();
    assertThat(firecrackerTroop.getLevel()).isEqualTo(TEST_LEVEL);

    // Tick until the Firecracker fires its initial projectile (which spawns shrapnel on impact).
    // Firecracker has a ranged attack; we need enough ticks for it to acquire a target, fire,
    // and for the firework to hit and spawn shrapnel sub-projectiles.
    // Tick generously to cover load time + attack cooldown + projectile travel + shrapnel spawn.
    for (int i = 0; i < 300; i++) {
      engine.tick(1);

      // Check if any fan/shrapnel projectiles have been spawned.
      // Shrapnel projectiles are piercing, team BLUE, and have no source entity.
      List<Projectile> shrapnelList =
          engine.getGameState().getProjectiles().stream()
              .filter(p -> p.getTeam() == Team.BLUE)
              .filter(Projectile::isPiercing)
              .filter(p -> p.getSource() == null) // fan sub-projectiles have no source entity
              .toList();

      if (!shrapnelList.isEmpty()) {
        // Firecracker's base shrapnel damage at level 1
        int baseDamage =
            firecracker.getUnitStats().getProjectile().getSpawnProjectile().getDamage();
        int expectedScaled = LevelScaling.scaleCard(baseDamage, Rarity.COMMON, TEST_LEVEL);

        // Each shrapnel should deal scaled damage, not base damage
        for (Projectile shrapnel : shrapnelList) {
          assertThat(shrapnel.getDamage())
              .as(
                  "Shrapnel damage should be scaled from %d to %d at level %d",
                  baseDamage, expectedScaled, TEST_LEVEL)
              .isEqualTo(expectedScaled);
          // Scaled must be strictly greater than base
          assertThat(shrapnel.getDamage()).isGreaterThan(baseDamage);
        }
        return; // test passed
      }
    }

    // If we get here, no shrapnel was ever spawned -- fail the test
    fail("Firecracker never spawned shrapnel sub-projectiles within 300 ticks");
  }

  @Test
  void attackProjectileWithSpawnProjectile_shouldHaveSpellLevelSet() {
    Card firecracker =
        Objects.requireNonNull(CardRegistry.get("firecracker"), "firecracker not found");
    List<Card> blueDeck = new ArrayList<>(Collections.nCopies(8, firecracker));

    Card giant = Objects.requireNonNull(CardRegistry.get("giant"), "giant not found");
    List<Card> redDeck = new ArrayList<>(Collections.nCopies(8, giant));

    bluePlayer = new Player(Team.BLUE, new Deck(blueDeck), false, new LevelConfig(TEST_LEVEL));
    bluePlayer.getElixir().update(100f);

    redPlayer = new Player(Team.RED, new Deck(redDeck), false, new LevelConfig(TEST_LEVEL));
    redPlayer.getElixir().update(100f);

    Standard1v1Match match = new Standard1v1Match();
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();

    engine.queueAction(bluePlayer, PlayerActionDTO.play(0, 9f, 14f));
    engine.queueAction(redPlayer, PlayerActionDTO.play(0, 9f, 18f));

    int syncTicks = (int) (DeploymentSystem.PLACEMENT_SYNC_DELAY * GameEngine.TICKS_PER_SECOND);
    engine.tick(syncTicks + 1);

    // Tick until a firework projectile from the Firecracker is in flight
    for (int i = 0; i < 300; i++) {
      engine.tick(1);

      // The firework is an entity-targeted projectile from a BLUE source with spawnProjectile set
      List<Projectile> fireworks =
          engine.getGameState().getProjectiles().stream()
              .filter(p -> p.getTeam() == Team.BLUE)
              .filter(p -> p.getSpawnProjectile() != null)
              .toList();

      if (!fireworks.isEmpty()) {
        for (Projectile firework : fireworks) {
          assertThat(firework.getSpellLevel())
              .as("Attack projectile with spawnProjectile should carry attacker level")
              .isEqualTo(TEST_LEVEL);
          assertThat(firework.getSpellRarity())
              .as("Attack projectile with spawnProjectile should carry rarity")
              .isNotNull();
        }
        return; // test passed
      }
    }

    fail("Firecracker never fired a firework projectile within 300 ticks");
  }
}
