package org.crforge.core.battle.action;

import java.util.List;
import java.util.function.Function;
import org.crforge.core.battle.filter.FilterSubject;

/**
 * An object's group chain as the group checks walk it: whether the object is in a group, and every
 * object of its chain from the chain's first, in chain order, the asking object among them.
 *
 * <p>A card that is a group links each unit it makes after the one made before it, and a spawn row
 * that adds its children to their source's group links each child right after the source. A unit
 * that leaves the battle is taken out of the chain; the units left keep their group mark, so the
 * last unit of a group is still in one.
 *
 * @param grouped whether the asking object is in a group; a check on an object in none does nothing
 * @param members the chain's objects from its first, in chain order
 * @param team the asking object's team, which the filter is asked for
 * @param rowName the asking object's row name, which the filter is asked for
 */
public record GroupChain(boolean grouped, List<Member> members, int team, String rowName) {

  public GroupChain {
    members = List.copyOf(members);
  }

  /**
   * One object of the chain.
   *
   * @param subject the object as a filter asks about it
   * @param holder its action holder
   * @param self true for the asking object
   * @param actions builds an action row for this object, its expressions compiled for it
   */
  public record Member(
      FilterSubject subject,
      ActionHolder holder,
      boolean self,
      Function<String, BattleAction> actions) {}
}
