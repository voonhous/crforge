/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.Arrays;
import org.crforge.core.battle.BattleEntity;

/**
 * The ids a seat's observation names its entities by: each seat numbers the battle's entities 0, 1,
 * 2, ... in the order it is first shown them, so an id says nothing of the entities the seat was
 * not shown. The battle's own ids are global and in creation order, so a gap between two of them
 * would tell a seat that an entity it does not see (an enemy unit hidden, tunnelling or waiting its
 * turn to deploy) was made between the two.
 *
 * <p>A seat's id is given the first time the seat's observation names the entity (its row, a row's
 * target, a champion slot's unit), stays with the entity for its life and is never given to another
 * one within the episode; the two seats number apart, and a new episode starts both afresh ({@link
 * #clear}). Ids are given in the order the observation is written, so an entity hidden at its
 * making and shown later takes the next free id then.
 */
final class SeatIds {

  /**
   * Per seat and entity kind, by the battle id's count within its kind ({@link
   * BattleEntity#IDS_PER_KIND}): the seat's id plus 1, 0 for an entity the seat was not shown yet.
   */
  private final int[][][] ids = new int[2][0][];

  /** Per seat, by the seat's id: the battle id it stands for. */
  private final int[][] battleIds = {new int[64], new int[64]};

  /** Per seat: the ids given, so the next one. */
  private final int[] given = new int[2];

  /** Forgets every id given, for a new episode. */
  void clear() {
    for (int seat = 0; seat < 2; seat++) {
      for (int[] kind : ids[seat]) {
        if (kind != null) {
          Arrays.fill(kind, 0);
        }
      }
      given[seat] = 0;
    }
  }

  /**
   * A seat's id of an entity, given now when the seat was not shown the entity before.
   *
   * @param seat the seat, 0 or 1
   * @param battleId the entity's id in the battle
   */
  int of(int seat, int battleId) {
    int kind = battleId / BattleEntity.IDS_PER_KIND;
    int n = battleId % BattleEntity.IDS_PER_KIND;
    int[][] byKind = ids[seat];
    if (kind >= byKind.length) {
      ids[seat] = byKind = Arrays.copyOf(byKind, kind + 1);
    }
    int[] of = byKind[kind];
    if (of == null || n >= of.length) {
      int size = Math.max(n + 1, of == null ? 64 : of.length * 2);
      byKind[kind] = of = of == null ? new int[size] : Arrays.copyOf(of, size);
    }
    if (of[n] == 0) {
      int id = given[seat]++;
      if (id == battleIds[seat].length) {
        battleIds[seat] = Arrays.copyOf(battleIds[seat], 2 * id);
      }
      battleIds[seat][id] = battleId;
      of[n] = id + 1;
    }
    return of[n] - 1;
  }

  /**
   * The battle id a seat's id stands for, or -1 for an id not given in the episode.
   *
   * @param seat the seat, 0 or 1
   * @param id the seat's id
   */
  int battleId(int seat, int id) {
    return id >= 0 && id < given[seat] ? battleIds[seat][id] : -1;
  }
}
