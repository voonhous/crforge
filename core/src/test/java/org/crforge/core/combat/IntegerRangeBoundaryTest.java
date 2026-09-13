package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.List;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Range and radius checks use exact integer squared distances in game units. These tests pin the
 * inclusive/exclusive boundaries at single-unit resolution.
 */
class IntegerRangeBoundaryTest {

  private static final int RADIUS = 500;
  private static final int RANGE = tiles(1.2);
  // Center-to-center distance at which the edge-to-edge gap equals the attack range
  private static final int EDGE_RANGE = RANGE + RADIUS + RADIUS;

  private GameState gameState;
  private CombatSystem combatSystem;
  private AoeDamageService aoeDamageService;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    ProjectileSystem projectileSystem =
        new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
  }

  private static Troop troop(Team team, int x, int y, int range, int sightRange) {
    Troop troop =
        Troop.builder()
            .name(team + "Troop")
            .team(team)
            .position(new Position(x, y))
            .health(new Health(1000))
            .movement(new Movement(0f, 4f, RADIUS, RADIUS, MovementType.GROUND))
            .combat(Combat.builder().damage(10).range(range).sightRange(sightRange).build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    troop.onSpawn();
    return troop;
  }

  @Test
  void attackRange_isInclusiveAtExactEdgeDistance() {
    Troop attacker = troop(Team.BLUE, tiles(9), tiles(10), RANGE, tiles(5.5));
    Troop target = troop(Team.RED, tiles(9) + EDGE_RANGE, tiles(10), RANGE, tiles(5.5));
    attacker.getCombat().setCurrentTarget(target);

    assertThat(attacker.isInAttackRange()).isTrue();
    assertThat(combatSystem.canAttack(attacker, target)).isTrue();
  }

  @Test
  void attackRange_excludesTargetOneUnitBeyondEdgeDistance() {
    Troop attacker = troop(Team.BLUE, tiles(9), tiles(10), RANGE, tiles(5.5));
    Troop target = troop(Team.RED, tiles(9) + EDGE_RANGE + 1, tiles(10), RANGE, tiles(5.5));
    attacker.getCombat().setCurrentTarget(target);

    assertThat(attacker.isInAttackRange()).isFalse();
    assertThat(combatSystem.canAttack(attacker, target)).isFalse();
  }

  @Test
  void attackRange_diagonalBoundaryUsesExactSquaredDistance() {
    // 3-4-5 triangle scaled so the center distance is exactly EDGE_RANGE (2200 = 5 * 440)
    int dx = 3 * 440;
    int dy = 4 * 440;
    Troop attacker = troop(Team.BLUE, tiles(9), tiles(10), RANGE, tiles(5.5));
    Troop inside = troop(Team.RED, tiles(9) + dx, tiles(10) + dy, RANGE, tiles(5.5));
    Troop outside = troop(Team.RED, tiles(9) + dx, tiles(10) + dy + 1, RANGE, tiles(5.5));

    assertThat(combatSystem.canAttack(attacker, inside)).isTrue();
    assertThat(combatSystem.canAttack(attacker, outside)).isFalse();
  }

  @Test
  void minimumRange_blindSpotIsExclusiveAtBoundary() {
    int minimumRange = tiles(3.5);
    int blindEdge = minimumRange + RADIUS + RADIUS;
    Troop mortar =
        Troop.builder()
            .name("Mortar")
            .team(Team.BLUE)
            .position(new Position(tiles(9), tiles(5)))
            .health(new Health(1000))
            .movement(new Movement(0f, 4f, RADIUS, RADIUS, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(10)
                    .range(tiles(11.5))
                    .sightRange(tiles(11.5))
                    .minimumRange(minimumRange)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    mortar.onSpawn();

    Troop atEdge = troop(Team.RED, tiles(9), tiles(5) + blindEdge, RANGE, tiles(5.5));
    Troop insideBlindSpot = troop(Team.RED, tiles(9), tiles(5) + blindEdge - 1, RANGE, tiles(5.5));

    assertThat(combatSystem.canAttack(mortar, atEdge)).isTrue();
    assertThat(combatSystem.canAttack(mortar, insideBlindSpot)).isFalse();
  }

  @Test
  void sightRange_acquiresTargetAtExactEdgeDistanceOnly() {
    int sight = tiles(5.5);
    int sightEdge = sight + RADIUS + RADIUS;
    TargetingSystem targeting = new TargetingSystem();

    Troop seer = troop(Team.BLUE, tiles(9), tiles(10), RANGE, sight);
    Troop atEdge = troop(Team.RED, tiles(9), tiles(10) + sightEdge, RANGE, sight);
    targeting.updateTargets(List.<Entity>of(seer, atEdge));
    assertThat(seer.getCombat().getCurrentTarget()).isSameAs(atEdge);

    Troop blind = troop(Team.BLUE, tiles(3), tiles(10), RANGE, sight);
    Troop beyond = troop(Team.RED, tiles(3), tiles(10) + sightEdge + 1, RANGE, sight);
    targeting.updateTargets(List.<Entity>of(blind, beyond));
    assertThat(blind.getCombat().getCurrentTarget()).isNull();
  }

  @Test
  void spellRadius_includesEntityWhoseEdgeTouchesTheRadius() {
    int spellRadius = tiles(2.5);
    Troop touching = troop(Team.RED, tiles(9) + spellRadius + RADIUS, tiles(10), RANGE, 0);
    Troop outside = troop(Team.RED, tiles(9) - spellRadius - RADIUS - 1, tiles(10), RANGE, 0);
    gameState.spawnEntity(touching);
    gameState.spawnEntity(outside);
    gameState.processPending();

    aoeDamageService.applySpellDamage(Team.BLUE, tiles(9), tiles(10), 100, spellRadius, List.of());

    assertThat(touching.getHealth().getCurrent()).isEqualTo(900);
    assertThat(outside.getHealth().getCurrent()).isEqualTo(1000);
  }

  @Test
  void rangeChecks_workAtOppositeArenaCorners_withoutOverflow() {
    // Longest possible separation inside the arena; squared distance exceeds 1.3e9
    Troop attacker =
        troop(Team.BLUE, RADIUS, RADIUS, tiles(40), tiles(40)); // range larger than the diagonal
    Troop target = troop(Team.RED, tiles(18) - RADIUS, tiles(32) - RADIUS, RANGE, tiles(5.5));

    assertThat(attacker.getPosition().distanceSquaredTo(target.getPosition()))
        .isEqualTo(
            (long) (tiles(18) - 1000) * (tiles(18) - 1000)
                + (long) (tiles(32) - 1000) * (tiles(32) - 1000));
    assertThat(combatSystem.canAttack(attacker, target)).isTrue();
  }
}
