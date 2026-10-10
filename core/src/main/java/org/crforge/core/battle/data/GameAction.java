/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

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
