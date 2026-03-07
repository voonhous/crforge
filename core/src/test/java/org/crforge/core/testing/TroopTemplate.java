package org.crforge.core.testing;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.AbilityData;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Fluent factory for creating test troops with sensible defaults.
 * Avoids boilerplate builder code in test methods.
 */
public class TroopTemplate {

  private String name;
  private Team team;
  private float x = 5;
  private float y = 5;
  private int hp = 100;
  private int damage = 100;
  private float range = 1.5f;
  private float sightRange = 5.5f;
  private float attackCooldown = 1.0f;
  private float loadTime = 0f;
  private float speed = 1.0f;
  private float mass = 4f;
  private float collisionRadius = 0.5f;
  private float visualRadius = 0.5f;
  private MovementType movementType = MovementType.GROUND;
  private TargetType targetType = TargetType.ALL;
  private float deployTime = 1.0f;
  private float aoeRadius = 0f;
  private boolean targetOnlyBuildings = false;
  private AbilityData abilityData = null;

  private TroopTemplate(String name, Team team) {
    this.name = name;
    this.team = team;
  }

  // -- Factory methods --

  /** Melee troop with 100 damage, 1.5 range, 100 hp. */
  public static TroopTemplate melee(String name, Team team) {
    return new TroopTemplate(name, team);
  }

  /** Ranged troop with 50 damage, 6.0 range, 100 hp. */
  public static TroopTemplate ranged(String name, Team team) {
    return new TroopTemplate(name, team)
        .damage(50).range(6.0f).sightRange(6.0f);
  }

  /** Stationary target dummy with 1000 hp, no speed, no damage. */
  public static TroopTemplate target(String name, Team team) {
    return new TroopTemplate(name, team)
        .hp(1000).damage(0).speed(0f);
  }

  /** Air troop with AIR movement type. */
  public static TroopTemplate air(String name, Team team) {
    return new TroopTemplate(name, team)
        .movementType(MovementType.AIR);
  }

  // -- Chainable setters --

  public TroopTemplate at(float x, float y) {
    this.x = x;
    this.y = y;
    return this;
  }

  public TroopTemplate hp(int hp) {
    this.hp = hp;
    return this;
  }

  public TroopTemplate damage(int damage) {
    this.damage = damage;
    return this;
  }

  public TroopTemplate range(float range) {
    this.range = range;
    return this;
  }

  public TroopTemplate sightRange(float sightRange) {
    this.sightRange = sightRange;
    return this;
  }

  public TroopTemplate cooldown(float cooldown) {
    this.attackCooldown = cooldown;
    return this;
  }

  public TroopTemplate loadTime(float loadTime) {
    this.loadTime = loadTime;
    return this;
  }

  public TroopTemplate speed(float speed) {
    this.speed = speed;
    return this;
  }

  public TroopTemplate mass(float mass) {
    this.mass = mass;
    return this;
  }

  public TroopTemplate collisionRadius(float r) {
    this.collisionRadius = r;
    return this;
  }

  public TroopTemplate visualRadius(float r) {
    this.visualRadius = r;
    return this;
  }

  public TroopTemplate movementType(MovementType type) {
    this.movementType = type;
    return this;
  }

  public TroopTemplate targetType(TargetType type) {
    this.targetType = type;
    return this;
  }

  public TroopTemplate deployTime(float deployTime) {
    this.deployTime = deployTime;
    return this;
  }

  public TroopTemplate aoeRadius(float r) {
    this.aoeRadius = r;
    return this;
  }

  public TroopTemplate targetOnlyBuildings(boolean b) {
    this.targetOnlyBuildings = b;
    return this;
  }

  public TroopTemplate ability(AbilityData data) {
    this.abilityData = data;
    return this;
  }

  // -- Build --

  /** Creates the Troop entity from this template. */
  public Troop build() {
    AbilityComponent abilityComponent = abilityData != null
        ? new AbilityComponent(abilityData) : null;

    return Troop.builder()
        .name(name)
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .movement(new Movement(speed, mass, collisionRadius, visualRadius, movementType))
        .combat(Combat.builder()
            .damage(damage)
            .range(range)
            .sightRange(sightRange)
            .attackCooldown(attackCooldown)
            .loadTime(loadTime)
            .aoeRadius(aoeRadius)
            .targetType(targetType)
            .targetOnlyBuildings(targetOnlyBuildings)
            .build())
        .deployTime(deployTime)
        .deployTimer(deployTime)
        .ability(abilityComponent)
        .build();
  }
}
