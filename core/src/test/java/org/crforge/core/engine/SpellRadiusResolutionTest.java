package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies spell radius resolution when both card-level spellRadius and projectile-level radius are
 * present. Card-level radius should take priority because it represents the intended spell area,
 * while projectile radius is per-impact splash.
 */
class SpellRadiusResolutionTest {

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
  void travelingSpell_shouldUseCardSpellRadius_whenPresent() {
    // Arrows: card spellRadius=3.5 tiles, projectile radius=1.4 tiles
    // The card-level radius should win because it represents the actual spell area
    Card arrows =
        Card.builder()
            .id("test-arrows")
            .name("TestArrows")
            .type(CardType.SPELL)
            .rarity(Rarity.COMMON)
            .cost(3)
            .spellRadius(tiles(3.5))
            .projectile(
                ProjectileStats.builder()
                    .name("TestArrowsProj")
                    .damage(100)
                    .speed(tiles(8))
                    .radius(tiles(1.4))
                    .build())
            .build();

    castSpell(Team.BLUE, arrows, tiles(9), tiles(20));

    assertThat(engine.getGameState().getProjectiles()).hasSize(1);
    Projectile p = engine.getGameState().getProjectiles().get(0);
    assertThat(p.getAoeRadius()).isEqualTo(tiles(3.5));
  }

  @Test
  void travelingSpell_shouldUseProjectileRadius_whenNoCardSpellRadius() {
    // Fireball: no card spellRadius, projectile radius=2.5 tiles
    // Without a card-level override, the projectile radius should be used
    Card fireball =
        Card.builder()
            .id("test-fireball")
            .name("TestFireball")
            .type(CardType.SPELL)
            .rarity(Rarity.RARE)
            .cost(4)
            .projectile(
                ProjectileStats.builder()
                    .name("TestFireballProj")
                    .damage(200)
                    .speed(tiles(8))
                    .radius(tiles(2.5))
                    .build())
            .build();

    castSpell(Team.BLUE, fireball, tiles(9), tiles(20));

    assertThat(engine.getGameState().getProjectiles()).hasSize(1);
    Projectile p = engine.getGameState().getProjectiles().get(0);
    assertThat(p.getAoeRadius()).isEqualTo(tiles(2.5));
  }

  private void castSpell(Team team, Card card, int x, int y) {
    SpellFactory factory =
        new SpellFactory(
            engine.getGameState(), null, null, new AreaEffectFactory(engine.getGameState()));
    factory.castSpell(team, card, x, y, 1);
  }
}
