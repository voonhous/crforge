package org.crforge.bridge.dto;

/** Entity snapshot for RL observation. Includes troops, buildings, and towers. */
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
    int shield) {}
