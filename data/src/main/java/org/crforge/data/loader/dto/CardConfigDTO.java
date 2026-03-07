package org.crforge.data.loader.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
}
