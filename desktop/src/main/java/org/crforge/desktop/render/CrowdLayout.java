/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.util.GameUnits;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;

/**
 * Which troops are drawn as a crowd: troops of one side, one row and one level that stand close
 * together share one label ({@code Skeleton x15 Lv11}) and each keeps only a thin health bar, so a
 * swarm reads as one thing instead of a pile of labels and numbers.
 *
 * <p>Two such troops are close when the gap between their bodies is at most {@link #GAP}; a group
 * is every troop reached through such links. The inspected and the hovered unit never join a group:
 * they keep their full label and numbers. Presentation only: the battle never reads it.
 */
final class CrowdLayout {

  /** The largest gap between two bodies, in game units, that still links them: half a tile. */
  static final int GAP = GameUnits.UNITS_PER_TILE / 2;

  /**
   * One crowd: two or more troops of one side, row and level, linked by closeness.
   *
   * @param members the troops, in the frame's order
   */
  record Group(List<EntityView> members) {

    /** The label the group shares: the row name, how many, and the level. */
    String label(String displayName, String level) {
      return displayName + " x" + members.size() + " " + level;
    }
  }

  /** No crowds, before the first frame. */
  static final CrowdLayout NONE = new CrowdLayout(List.of());

  private final List<Group> groups;
  private final Map<Integer, Group> byMember;

  private CrowdLayout(List<Group> groups) {
    this.groups = groups;
    this.byMember = new HashMap<>();
    for (Group group : groups) {
      for (EntityView member : group.members()) {
        byMember.put(member.id(), group);
      }
    }
  }

  /**
   * The crowds of a frame.
   *
   * @param frame the frame drawn
   * @param inspected the inspected unit's id, or -1
   * @param hovered the hovered unit's id, or -1
   */
  static CrowdLayout of(BattleFrame frame, int inspected, int hovered) {
    List<EntityView> troops = new ArrayList<>();
    for (EntityView entity : frame.entities()) {
      if (entity.kind() == EntityView.Kind.TROOP
          && entity.id() != inspected
          && entity.id() != hovered) {
        troops.add(entity);
      }
    }
    // Union-find over the links; a frame has at most a few hundred troops, so every pair is tried.
    int[] parent = new int[troops.size()];
    for (int i = 0; i < parent.length; i++) parent[i] = i;
    for (int i = 0; i < troops.size(); i++) {
      for (int j = i + 1; j < troops.size(); j++) {
        if (linked(troops.get(i), troops.get(j))) {
          parent[root(parent, i)] = root(parent, j);
        }
      }
    }
    Map<Integer, List<EntityView>> sets = new HashMap<>();
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < troops.size(); i++) {
      int root = root(parent, i);
      List<EntityView> members = sets.get(root);
      if (members == null) {
        members = new ArrayList<>();
        sets.put(root, members);
        order.add(root);
      }
      members.add(troops.get(i));
    }
    List<Group> groups = new ArrayList<>();
    for (int root : order) {
      List<EntityView> members = sets.get(root);
      if (members.size() > 1) {
        groups.add(new Group(List.copyOf(members)));
      }
    }
    return new CrowdLayout(List.copyOf(groups));
  }

  /** Whether two troops belong in one crowd: one side, row and level, bodies close. */
  static boolean linked(EntityView a, EntityView b) {
    if (a.side() != b.side() || a.level() != b.level() || !a.name().equals(b.name())) {
      return false;
    }
    long reach = (long) a.radius() + b.radius() + GAP;
    long dx = a.x() - b.x();
    long dy = a.y() - b.y();
    return dx * dx + dy * dy <= reach * reach;
  }

  private static int root(int[] parent, int i) {
    while (parent[i] != i) {
      parent[i] = parent[parent[i]];
      i = parent[i];
    }
    return i;
  }

  /** Every crowd of the frame. */
  List<Group> groups() {
    return groups;
  }

  /** The crowd a unit is drawn in, or null for a unit drawn on its own. */
  Group groupOf(EntityView entity) {
    return byMember.get(entity.id());
  }
}
