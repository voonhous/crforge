package org.crforge.bridge.dto;

import java.util.List;

/** Per-player observation data. */
public record PlayerObsDTO(
    float elixir,
    List<HandCardDTO> hand,
    HandCardDTO nextCard,
    List<TowerDTO> towers,
    int crowns) {}
