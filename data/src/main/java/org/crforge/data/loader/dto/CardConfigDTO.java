package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CardConfigDTO {

  private String id;
  private String name;
  private String description;
  private CardType type;
  private int cost;
  private Rarity rarity;

  // Unit reference (string name into units.json)
  private String unit;

  // How many copies to spawn (default 1)
  private int count;

  // Projectile reference for spells (string name into projectiles.json)
  private String projectile;

  // Area effects (spells like Zap, Freeze, Poison)
  private AreaEffectConfigDTO areaEffect;

  // Deploy effects (e.g. ElectroWizard entry stun)
  private AreaEffectConfigDTO deployEffect;

  // Summon character for spells (e.g. Rage -> RageBottle, Heal -> HealSpirit)
  private String summonCharacter;

  // Raw CSV summonRadius for troop deploy formation
  private float summonRadius;

  // Pre-computed formation offsets in tile units: [[x1,y1], [x2,y2], ...]
  private List<List<Float>> formationOffsets;

  // Projectile spawned on card deployment (e.g. MegaKnight landing damage)
  private String spawnProjectile;

  // Secondary unit reference (string name into units.json)
  private String secondaryUnit;

  // How many secondary units to spawn (default 0)
  private int secondaryCount;

  // Whether this card can be deployed on the enemy's side of the arena (e.g. Miner, GoblinDrill)
  private boolean canDeployOnEnemySide;

  // Spell placement/targeting radius in tile units
  private float radius;

  // Number of projectiles per wave (cosmetic for simulation)
  private int multipleProjectiles;

  // Number of projectile waves (e.g. Arrows fires 3 waves)
  private int projectileWaves;

  // Interval between projectile waves in seconds
  private float projectileWaveInterval;

  // Spell deploys at placement location instead of traveling from behind (e.g. Log)
  private boolean spellAsDeploy;
}
