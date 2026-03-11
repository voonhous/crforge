package org.crforge.bridge.dto;

import java.util.List;

/** Full game state observation sent to the RL agent. */
public record ObservationDTO(
    int frame,
    float gameTimeSeconds,
    boolean isOvertime,
    PlayerObsDTO bluePlayer,
    PlayerObsDTO redPlayer,
    List<EntityDTO> entities) {}
