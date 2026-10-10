/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import org.crforge.core.battle.BattleEntity;

/**
 * One hit that reached a reflecting unit's reflect, as the reflect left it.
 *
 * @param target the reflecting unit
 * @param attacker what dealt the hit, or null for nothing
 * @param source the character the reflect traced the hit to - the attacker, or a projectile's root
 *     - or null for none
 * @param struck the character the reflect strikes: the source, or the parent it rides on; null for
 *     none
 * @param hitSpeed the reflecting unit's hit-speed scale of 100, below 1 while it is stunned
 * @param buff the buff the struck unit took, or null when the reflect stopped before it
 * @param buffTimeMs how long the buff lasts
 * @param buffLevel the level it was applied at, packed
 * @param damage the reflected damage, or 0 when none was dealt
 * @param hitPointsBefore the struck unit's hit points before the reflected damage
 * @param hitPointsAfter the struck unit's hit points after it
 */
public record Reflection(
    WorldEntity target,
    BattleEntity attacker,
    WorldEntity source,
    WorldEntity struck,
    int hitSpeed,
    String buff,
    int buffTimeMs,
    int buffLevel,
    int damage,
    int hitPointsBefore,
    int hitPointsAfter) {}
