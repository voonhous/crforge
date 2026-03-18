package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that spell projectiles originate from the player's crown tower, not from the target
 * position. This ensures spells like Fireball, Rocket, and Arrows fly diagonally across the arena.
 */
class SpellProjectileOriginTest {

  private GameEngine engine;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();
    engine.setMatch(new Standard1v1Match());
    engine.initMatch();
  }

  @Test
  void spellProjectile_shouldOriginateFromBlueCrownTower() {
    Tower blueCrown = engine.getGameState().getCrownTower(Team.BLUE);
    assertThat(blueCrown).isNotNull();
    float crownX = blueCrown.getPosition().getX();
    float crownY = blueCrown.getPosition().getY();

    // Target off-center so we can verify X differs from crown tower X
    float targetX = 3f;
    float targetY = 20f;

    Card spell = buildProjectileSpell();
    castSpell(Team.BLUE, spell, targetX, targetY);

    assertThat(engine.getGameState().getProjectiles()).hasSize(1);
    Projectile p = engine.getGameState().getProjectiles().get(0);

    // Projectile should start at crown tower position, not at target position
    assertThat(p.getOriginX()).isEqualTo(crownX);
    assertThat(p.getOriginY()).isEqualTo(crownY);

    // Destination should be the target position
    assertThat(p.getTargetX()).isEqualTo(targetX);
    assertThat(p.getTargetY()).isEqualTo(targetY);
  }

  @Test
  void spellProjectile_redTeam_shouldOriginateFromRedCrownTower() {
    Tower redCrown = engine.getGameState().getCrownTower(Team.RED);
    assertThat(redCrown).isNotNull();
    float crownX = redCrown.getPosition().getX();
    float crownY = redCrown.getPosition().getY();

    float targetX = 15f;
    float targetY = 10f;

    Card spell = buildProjectileSpell();
    castSpell(Team.RED, spell, targetX, targetY);

    assertThat(engine.getGameState().getProjectiles()).hasSize(1);
    Projectile p = engine.getGameState().getProjectiles().get(0);

    assertThat(p.getOriginX()).isEqualTo(crownX);
    assertThat(p.getOriginY()).isEqualTo(crownY);
    assertThat(p.getTargetX()).isEqualTo(targetX);
    assertThat(p.getTargetY()).isEqualTo(targetY);
  }

  @Test
  void spellAsDeploy_shouldStartAtDeployPoint() {
    float targetX = 5f;
    float targetY = 16f;

    Card logSpell =
        Card.builder()
            .id("test-log")
            .name("TestLog")
            .type(CardType.SPELL)
            .rarity(Rarity.LEGENDARY)
            .cost(2)
            .spellAsDeploy(true)
            .projectile(ProjectileStats.builder().name("TestLogProj").damage(100).speed(5f).build())
            .build();

    castSpell(Team.BLUE, logSpell, targetX, targetY);

    assertThat(engine.getGameState().getProjectiles()).hasSize(1);
    Projectile p = engine.getGameState().getProjectiles().get(0);

    // spellAsDeploy projectile should start at the deploy point, not the crown tower
    assertThat(p.getOriginX()).isEqualTo(targetX);
    assertThat(p.getOriginY()).isEqualTo(targetY);
  }

  private Card buildProjectileSpell() {
    return Card.builder()
        .id("test-fireball")
        .name("TestFireball")
        .type(CardType.SPELL)
        .rarity(Rarity.RARE)
        .cost(4)
        .projectile(
            ProjectileStats.builder().name("TestFireballProj").damage(200).speed(8f).build())
        .build();
  }

  /**
   * Constructs a SpellFactory wired to the engine's GameState and casts the spell. SpellFactory is
   * package-private, so the test must live in the same package.
   */
  private void castSpell(Team team, Card card, float x, float y) {
    SpellFactory factory =
        new SpellFactory(
            engine.getGameState(), null, null, new AreaEffectFactory(engine.getGameState()));
    factory.castSpell(team, card, x, y, 1);
  }
}
