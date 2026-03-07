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

  // Troop specifics
  private List<UnitConfigDTO> units;

  // Projectile specifics (For Spells)
  private ProjectileConfigDTO projectile;

  // Area effects (spells like Zap, Freeze, Poison)
  private AreaEffectConfigDTO areaEffect;

  // Deploy effects (e.g. ElectroWizard entry stun)
  private AreaEffectConfigDTO deployEffect;

  // Summon character for spells (e.g. Rage -> RageBottle, Heal -> HealSpirit)
  private String summonCharacter;

  // Raw CSV summonRadius for troop deploy formation
  private float summonRadius;
}
