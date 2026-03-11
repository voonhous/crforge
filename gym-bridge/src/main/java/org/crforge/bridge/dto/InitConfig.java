package org.crforge.bridge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Configuration sent by the Python client to initialize a match. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InitConfig(
    List<String> blueDeck, List<String> redDeck, int level, int ticksPerStep, Long seed) {
  public InitConfig {
    if (level <= 0) {
      level = 11;
    }
    if (ticksPerStep <= 0) {
      ticksPerStep = 1;
    }
  }

  /** Constructor without seed for backwards compatibility. */
  public InitConfig(List<String> blueDeck, List<String> redDeck, int level, int ticksPerStep) {
    this(blueDeck, redDeck, level, ticksPerStep, null);
  }
}
