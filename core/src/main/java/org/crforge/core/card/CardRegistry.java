package org.crforge.core.card;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.MovementType;
import org.crforge.core.entity.TargetType;

public class CardRegistry {

  private static final Map<String, Card> CARDS = new LinkedHashMap<>();

  static {
    registerStarterCards();
  }

  private static void registerStarterCards() {
    // Ground Troops
    register(
        Card.builder()
            .id("knight")
            .name("Knight")
            .description("A tough melee fighter")
            .type(CardType.TROOP)
            .cost(3)
            .troop(
                TroopStats.builder()
                    .name("Knight")
                    .health(1452)
                    .damage(167)
                    .speed(1.0f)
                    .mass(5.0f)
                    .size(0.8f)
                    .range(0.7f)
                    .attackCooldown(1.1f)
                    .movementType(MovementType.GROUND)
                    .build())
            .build());

    register(
        Card.builder()
            .id("giant")
            .name("Giant")
            .description("Slow but high health, only targets buildings")
            .type(CardType.TROOP)
            .cost(5)
            .troop(
                TroopStats.builder()
                    .name("Giant")
                    .health(4091)
                    .damage(211)
                    .speed(0.6f)
                    .mass(10.0f)
                    .size(1.2f)
                    .range(0.7f)
                    .attackCooldown(1.5f)
                    .targetType(TargetType.BUILDINGS)
                    .movementType(MovementType.GROUND)
                    .build())
            .build());

    register(
        Card.builder()
            .id("valkyrie")
            .name("Valkyrie")
            .description("Spins and deals area damage")
            .type(CardType.TROOP)
            .cost(4)
            .troop(
                TroopStats.builder()
                    .name("Valkyrie")
                    .health(1654)
                    .damage(221)
                    .speed(1.0f)
                    .mass(5.0f)
                    .size(0.8f)
                    .range(0.7f)
                    .aoeRadius(1.5f)
                    .attackCooldown(1.5f)
                    .movementType(MovementType.GROUND)
                    .build())
            .build());

    // Barbarians spawn 4 units
    register(
        Card.builder()
            .id("barbarians")
            .name("Barbarians")
            .description("Four melee fighters")
            .type(CardType.TROOP)
            .cost(5)
            .troop(
                TroopStats.builder()
                    .name("Barbarian")
                    .health(636)
                    .damage(125)
                    .speed(1.0f)
                    .mass(3.0f)
                    .size(0.6f)
                    .range(0.7f)
                    .attackCooldown(1.4f)
                    .offsetX(-0.5f)
                    .offsetY(-0.5f)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Barbarian")
                    .health(636)
                    .damage(125)
                    .speed(1.0f)
                    .mass(3.0f)
                    .size(0.6f)
                    .range(0.7f)
                    .attackCooldown(1.4f)
                    .offsetX(0.5f)
                    .offsetY(-0.5f)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Barbarian")
                    .health(636)
                    .damage(125)
                    .speed(1.0f)
                    .mass(3.0f)
                    .size(0.6f)
                    .range(0.7f)
                    .attackCooldown(1.4f)
                    .offsetX(-0.5f)
                    .offsetY(0.5f)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Barbarian")
                    .health(636)
                    .damage(125)
                    .speed(1.0f)
                    .mass(3.0f)
                    .size(0.6f)
                    .range(0.7f)
                    .attackCooldown(1.4f)
                    .offsetX(0.5f)
                    .offsetY(0.5f)
                    .build())
            .build());

    // Goblins spawn 3 units
    register(
        Card.builder()
            .id("goblins")
            .name("Goblins")
            .description("Three fast, weak fighters")
            .type(CardType.TROOP)
            .cost(2)
            .troop(
                TroopStats.builder()
                    .name("Goblin")
                    .health(202)
                    .damage(120)
                    .speed(1.3f)
                    .mass(1.0f)
                    .size(0.4f)
                    .range(0.7f)
                    .attackCooldown(1.1f)
                    .offsetX(-0.3f)
                    .offsetY(0f)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Goblin")
                    .health(202)
                    .damage(120)
                    .speed(1.3f)
                    .mass(1.0f)
                    .size(0.4f)
                    .range(0.7f)
                    .attackCooldown(1.1f)
                    .offsetX(0.3f)
                    .offsetY(0f)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Goblin")
                    .health(202)
                    .damage(120)
                    .speed(1.3f)
                    .mass(1.0f)
                    .size(0.4f)
                    .range(0.7f)
                    .attackCooldown(1.1f)
                    .offsetX(0f)
                    .offsetY(0.3f)
                    .build())
            .build());

    // Air Troops
    register(
        Card.builder()
            .id("minions")
            .name("Minions")
            .description("Three flying units")
            .type(CardType.TROOP)
            .cost(3)
            .troop(
                TroopStats.builder()
                    .name("Minion")
                    .health(252)
                    .damage(84)
                    .speed(1.3f)
                    .mass(1.0f)
                    .size(0.5f)
                    .range(2.0f)
                    .attackCooldown(1.0f)
                    .movementType(MovementType.AIR)
                    .ranged(true)
                    .offsetX(-0.3f)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Minion")
                    .health(252)
                    .damage(84)
                    .speed(1.3f)
                    .mass(1.0f)
                    .size(0.5f)
                    .range(2.0f)
                    .attackCooldown(1.0f)
                    .movementType(MovementType.AIR)
                    .ranged(true)
                    .offsetX(0.3f)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Minion")
                    .health(252)
                    .damage(84)
                    .speed(1.3f)
                    .mass(1.0f)
                    .size(0.5f)
                    .range(2.0f)
                    .attackCooldown(1.0f)
                    .movementType(MovementType.AIR)
                    .ranged(true)
                    .offsetY(0.3f)
                    .build())
            .build());

    register(
        Card.builder()
            .id("baby_dragon")
            .name("Baby Dragon")
            .description("Flying unit with area damage")
            .type(CardType.TROOP)
            .cost(4)
            .troop(
                TroopStats.builder()
                    .name("Baby Dragon")
                    .health(1152)
                    .damage(133)
                    .speed(1.0f)
                    .mass(3.0f)
                    .size(0.8f)
                    .range(3.5f)
                    .aoeRadius(1.0f)
                    .attackCooldown(1.5f)
                    .movementType(MovementType.AIR)
                    .ranged(true)
                    .build())
            .build());

    // Ranged Troops
    register(
        Card.builder()
            .id("musketeer")
            .name("Musketeer")
            .description("Ranged attacker with good damage")
            .type(CardType.TROOP)
            .cost(4)
            .troop(
                TroopStats.builder()
                    .name("Musketeer")
                    .health(720)
                    .damage(181)
                    .speed(1.0f)
                    .mass(3.0f)
                    .size(0.6f)
                    .range(6.0f)
                    .sightRange(6.5f)
                    .attackCooldown(1.0f)
                    .ranged(true)
                    .build())
            .build());

    register(
        Card.builder()
            .id("archers")
            .name("Archers")
            .description("Two ranged attackers")
            .type(CardType.TROOP)
            .cost(3)
            .troop(
                TroopStats.builder()
                    .name("Archer")
                    .health(304)
                    .damage(107)
                    .speed(1.0f)
                    .mass(1.5f)
                    .size(0.5f)
                    .range(5.0f)
                    .attackCooldown(1.1f)
                    .ranged(true)
                    .offsetX(-0.3f)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Archer")
                    .health(304)
                    .damage(107)
                    .speed(1.0f)
                    .mass(1.5f)
                    .size(0.5f)
                    .range(5.0f)
                    .attackCooldown(1.1f)
                    .ranged(true)
                    .offsetX(0.3f)
                    .build())
            .build());

    register(
        Card.builder()
            .id("bomber")
            .name("Bomber")
            .description("Ground splash damage")
            .type(CardType.TROOP)
            .cost(2)
            .troop(
                TroopStats.builder()
                    .name("Bomber")
                    .health(311)
                    .damage(193)
                    .speed(1.0f)
                    .mass(1.5f)
                    .size(0.5f)
                    .range(4.5f)
                    .aoeRadius(1.5f)
                    .attackCooldown(1.9f)
                    .targetType(TargetType.GROUND)
                    .ranged(true)
                    .build())
            .build());

    register(
        Card.builder()
            .id("ice_wizard")
            .name("Ice Wizard")
            .description("Shoots ice shards that slow enemies")
            .type(CardType.TROOP)
            .cost(3)
            .troop(
                TroopStats.builder()
                    .name("Ice Wizard")
                    .health(590)
                    .damage(75)
                    .speed(1.0f)
                    .mass(3.0f)
                    .size(0.6f)
                    .range(5.5f)
                    .sightRange(5.5f)
                    .attackCooldown(1.7f)
                    .ranged(true)
                    .hitEffects(List.of(
                        EffectStats.builder()
                            .type(StatusEffectType.SLOW)
                            .duration(2.5f)
                            .intensity(0.35f) // 35% slow
                            .build()
                    ))
                    .build())
            .build());

    // Spells
    register(
        Card.builder()
            .id("arrows")
            .name("Arrows")
            .description("Area damage spell")
            .type(CardType.SPELL)
            .cost(3)
            .spellDamage(303)
            .spellRadius(4.0f)
            .build());

    register(
        Card.builder()
            .id("fireball")
            .name("Fireball")
            .description("High damage area spell")
            .type(CardType.SPELL)
            .cost(4)
            .spellDamage(689)
            .spellRadius(2.5f)
            .build());

    register(
        Card.builder()
            .id("zap")
            .name("Zap")
            .description("Quick stun and damage")
            .type(CardType.SPELL)
            .cost(2)
            .spellDamage(192)
            .spellRadius(2.5f)
            .spellEffects(List.of(
                EffectStats.builder()
                    .type(StatusEffectType.STUN)
                    .duration(0.5f)
                    .build()
            ))
            .build());

    register(
        Card.builder()
            .id("freeze")
            .name("Freeze")
            .description("Freezes troops and buildings")
            .type(CardType.SPELL)
            .cost(4)
            .spellDamage(95)
            .spellRadius(3.0f)
            .spellEffects(List.of(
                EffectStats.builder()
                    .type(StatusEffectType.FREEZE)
                    .duration(4.0f)
                    .build()
            ))
            .build());

    register(
        Card.builder()
            .id("rage")
            .name("Rage")
            .description("Increases movement and attack speed")
            .type(CardType.SPELL)
            .cost(2)
            .spellRadius(5.0f)
            .spellEffects(List.of(
                EffectStats.builder()
                    .type(StatusEffectType.RAGE)
                    .duration(6.0f)
                    .intensity(0.35f) // 35% boost
                    .build()
            ))
            .build());

    // Buildings
    register(
        Card.builder()
            .id("cannon")
            .name("Cannon")
            .description("Defensive building that targets ground")
            .type(CardType.BUILDING)
            .cost(3)
            .buildingHealth(742)
            .buildingLifetime(30f)
            .troop(
                TroopStats.builder()
                    .name("Cannon")
                    .health(742)
                    .damage(137)
                    .size(2.0f)
                    .range(5.5f)
                    .attackCooldown(0.8f)
                    .targetType(TargetType.GROUND)
                    .movementType(MovementType.BUILDING)
                    .ranged(true)
                    .build())
            .build());

    register(
        Card.builder()
            .id("tombstone")
            .name("Tombstone")
            .description("Spawns Skeletons over time")
            .type(CardType.BUILDING)
            .cost(3)
            .buildingHealth(511)
            .buildingLifetime(40f)
            .spawnInterval(3.1f) // Spawn every 3.1s
            .deathSpawnCount(4) // 4 Skeletons on death
            .troop(
                TroopStats.builder()
                    .name("Tombstone")
                    .health(511)
                    .damage(0)
                    .size(2.0f)
                    .movementType(MovementType.BUILDING)
                    .build())
            .troop(
                TroopStats.builder()
                    .name("Skeleton")
                    .health(67)
                    .damage(67)
                    .speed(1.2f) // Fast
                    .mass(1.0f)
                    .size(0.4f)
                    .range(0.5f)
                    .attackCooldown(1.0f)
                    .movementType(MovementType.GROUND)
                    .build())
            .build());
  }

  private static void register(Card card) {
    CARDS.put(card.getId(), card);
  }

  public static Card get(String id) {
    return CARDS.get(id);
  }

  public static Collection<Card> getAll() {
    return Collections.unmodifiableCollection(CARDS.values());
  }

  public static List<String> getAllIds() {
    return new ArrayList<>(CARDS.keySet());
  }

  public static boolean exists(String id) {
    return CARDS.containsKey(id);
  }
}
