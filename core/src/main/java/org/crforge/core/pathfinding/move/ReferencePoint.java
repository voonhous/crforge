/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.move;

/**
 * The position of the entity a moving unit is heading for, as the movement pass sees it.
 *
 * <p>Only the position is needed: route preparation turns it into a goal cell, and the
 * route-beyond-reference predicate measures every route node from it.
 *
 * @param x position along the arena's width, in game units
 * @param y position along the arena's length, in game units
 */
public record ReferencePoint(int x, int y) {}
