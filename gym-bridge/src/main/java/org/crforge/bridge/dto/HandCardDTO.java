package org.crforge.bridge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A card in a player's hand, as seen by the RL agent.
 *
 * @param cardIndex 0-based index into CardRegistry vocabulary (for embedding/one-hot encoding)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandCardDTO(String id, String name, String type, int cost, int cardIndex) {}
