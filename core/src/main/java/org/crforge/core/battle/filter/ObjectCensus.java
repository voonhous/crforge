package org.crforge.core.battle.filter;

import java.util.List;

/**
 * What a check that counts the battle's objects asks of the object it runs on: every object of that
 * object's battle as a filter asks about it, and the team and row name the filter is asked for.
 *
 * @param objects the battle's live objects in the holder's order, ascending id, the asking object
 *     among them; objects still waiting to be admitted are not
 * @param team the asking object's team: its side's low bit
 * @param rowName the asking object's row name, which the same-objects exclusion compares with
 */
public record ObjectCensus(List<FilterSubject> objects, int team, String rowName) {

  public ObjectCensus {
    objects = List.copyOf(objects);
  }
}
