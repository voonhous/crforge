package org.crforge.bridge.dto;

/** Tower state in an observation snapshot. */
public record TowerDTO(long id, String type, int hp, int maxHp, float x, float y, boolean alive) {}
