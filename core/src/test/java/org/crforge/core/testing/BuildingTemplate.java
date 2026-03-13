package org.crforge.core.testing;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.player.Team;

/** Fluent factory for creating test buildings with sensible defaults. */
public class BuildingTemplate {

  private String name;
  private Team team;
  private float x = 9;
  private float y = 16;
  private int hp = 500;
  private float lifetime = 30f;
  private float deployTime = 0f;
  private float collisionRadius = 0.5f;
  private float visualRadius = 0.5f;
  private Combat combat = null;
  private AbilityComponent ability = null;
  private SpawnerComponent spawner = null;

  private BuildingTemplate(String name, Team team) {
    this.name = name;
    this.team = team;
  }

  // -- Factory methods --

  /** Defensive building with 500 hp, 30s lifetime. */
  public static BuildingTemplate defense(String name, Team team) {
    return new BuildingTemplate(name, team);
  }

  // -- Chainable setters --

  public BuildingTemplate at(float x, float y) {
    this.x = x;
    this.y = y;
    return this;
  }

  public BuildingTemplate hp(int hp) {
    this.hp = hp;
    return this;
  }

  public BuildingTemplate lifetime(float lifetime) {
    this.lifetime = lifetime;
    return this;
  }

  public BuildingTemplate deployTime(float deployTime) {
    this.deployTime = deployTime;
    return this;
  }

  public BuildingTemplate collisionRadius(float r) {
    this.collisionRadius = r;
    return this;
  }

  public BuildingTemplate combat(Combat combat) {
    this.combat = combat;
    return this;
  }

  public BuildingTemplate ability(AbilityComponent ability) {
    this.ability = ability;
    return this;
  }

  public BuildingTemplate spawner(SpawnerComponent spawner) {
    this.spawner = spawner;
    return this;
  }

  // -- Build --

  /** Creates the Building entity from this template. */
  public Building build() {
    return Building.builder()
        .name(name)
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .movement(new Movement(0, 0, collisionRadius, visualRadius, MovementType.BUILDING))
        .lifetime(lifetime)
        .remainingLifetime(lifetime)
        .deployTime(deployTime)
        .combat(combat)
        .ability(ability)
        .spawner(spawner)
        .build();
  }
}
