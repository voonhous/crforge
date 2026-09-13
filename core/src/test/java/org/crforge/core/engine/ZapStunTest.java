package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.List;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.card.EffectStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZapStunTest {

  private GameEngine engine;
  private AoeDamageService aoeDamageService;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    engine = new GameEngine();
    engine.setMatch(new Standard1v1Match());
    engine.initMatch();
    aoeDamageService =
        new AoeDamageService(engine.getGameState(), new DefaultCombatAbilityBridge());
  }

  @Test
  void stunShouldPreventMovement() {
    // 1. Spawn a unit that moves fast
    Troop runner =
        Troop.builder()
            .name("Runner")
            .team(Team.BLUE)
            .position(new Position(tiles(10), tiles(10)))
            .movement(new Movement(tiles(5.0), 1.0f, tiles(0.5), tiles(0.5), MovementType.GROUND))
            .combat(Combat.builder().build())
            .deployTime(0)
            .build();

    runner.onSpawn();
    engine.spawn(runner);
    engine.tick(); // Process spawn

    // 2. Verify it moves
    Position startPos = runner.getPosition().copy();
    engine.tick();
    assertThat(runner.getPosition().getY()).isGreaterThan(startPos.getY());

    // 3. Apply Zap (Stun)
    // Manually apply spell damage/effect logic
    aoeDamageService.applySpellDamage(
        Team.RED, // Enemy team casts it
        tiles(10),
        tiles(10), // Center
        0, // No damage for this test to focus on stun
        tiles(2.5), // Radius
        List.of(EffectStats.builder().type(StatusEffectType.STUN).duration(1.0f).build()));

    // 4. Run loop while stunned
    Position stunnedPos = runner.getPosition().copy();
    engine.tick(); // Effect applied, flags set by StatusEffectSystem

    // Check if movement disabled flag is set (it should be by StatusEffectSystem)
    assertThat(runner.getMovement().isMovementDisabled()).isTrue();

    // Check actual movement (The Bug: this will fail if movement logic ignores the flag)
    Position postTickPos = runner.getPosition().copy();

    // If bug exists, y will be greater (moved). If fixed, y should be same.
    assertThat(postTickPos.getY())
        .as("Unit should not move while stunned")
        .isEqualTo(stunnedPos.getY());
  }
}
