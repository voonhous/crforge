package org.crforge.data.game;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One action row of the data: its name, its class, its ClassType and its fields as the data writes
 * them.
 *
 * @param name the action's name
 * @param className the class of its row
 * @param classType the ClassType the row names
 * @param fields every field the row sets, by the game's name
 */
public record GameAction(String name, String className, String classType, JsonNode fields) {}
