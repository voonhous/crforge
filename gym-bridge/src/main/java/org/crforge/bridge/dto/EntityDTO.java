package org.crforge.bridge.dto;

/**
 * Entity snapshot for RL observation. Includes troops, buildings, and towers.
 *
 * <p>Combat state fields (A2):
 *
 * <ul>
 *   <li>attackCooldownFraction: 0.0 = just attacked, 1.0 = ready to attack
 *   <li>isAttacking: true while in attack windup animation
 *   <li>hasTarget: true if this entity has a live target locked
 * </ul>
 *
 * <p>Status effect flags (A3):
 *
 * <ul>
 *   <li>stunned, slowed, raged, frozen, poisoned: boolean flags for active effects
 * </ul>
 *
 * <p>Building lifetime (A4):
 *
 * <ul>
 *   <li>lifetimeFraction: remaining lifetime / total lifetime (0 for non-buildings or no lifetime)
 * </ul>
 */
public record EntityDTO(
    long id,
    String name,
    String team,
    String entityType,
    String movementType,
    float x,
    float y,
    int hp,
    int maxHp,
    int shield,
    // A2: Combat state
    float attackCooldownFraction,
    boolean isAttacking,
    boolean hasTarget,
    // A3: Status effects
    boolean stunned,
    boolean slowed,
    boolean raged,
    boolean frozen,
    boolean poisoned,
    // A4: Building lifetime
    float lifetimeFraction) {}
