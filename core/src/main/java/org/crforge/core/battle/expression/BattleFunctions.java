package org.crforge.core.battle.expression;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The names of the battle's expression functions: the id the compiled code calls each by and the
 * number of arguments each takes. Names are matched without regard to case.
 *
 * <p>What each function answers is the battle's to say; this table only fixes the names, so that
 * every expression of the data compiles to the same code whatever the battle can answer yet.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the 47 names, their ids 0 to 46, their argument counts and case-insensitive"
            + " matching.")
public final class BattleFunctions {

  /** One function: its name, its id and the fewest and most arguments it takes. */
  public record Entry(String name, int id, int minArguments, int maxArguments) {}

  /** Every function, in id order. */
  public static final List<Entry> ALL =
      List.of(
          new Entry("x", 0, 0, 0),
          new Entry("y", 1, 0, 0),
          new Entry("team_index", 2, 0, 0),
          new Entry("hp", 3, 0, 0),
          new Entry("max_hp", 4, 0, 1),
          new Entry("target_in_range", 5, 1, 1),
          new Entry("target_hp", 6, 0, 0),
          new Entry("target_max_hp", 7, 0, 1),
          new Entry("target_is_ground", 8, 0, 0),
          new Entry("is_moving", 9, 0, 0),
          new Entry("hit_timer", 10, 0, 0),
          new Entry("attack_count", 11, 0, 0),
          new Entry("is_combat_enabled", 12, 0, 0),
          new Entry("should_hide", 13, 0, 0),
          new Entry("timeline_index", 14, 0, 0),
          new Entry("timeline_current_ms", 15, 0, 0),
          new Entry("timeline_total_ms", 16, 0, 0),
          new Entry("is_damaged", 17, 0, 0),
          new Entry("king_tower_damaged", 18, 0, 0),
          new Entry("coop_king_tower_damaged", 19, 0, 0),
          new Entry("tower_destroyed", 20, 0, 0),
          new Entry("coop_tower_destroyed", 21, 0, 0),
          new Entry("is_npc_battle", 22, 0, 0),
          new Entry("is_npc_avatar", 23, 0, 0),
          new Entry("random_chance", 24, 1, 1),
          new Entry("rand", 25, 1, 1),
          new Entry("set_context_to_aoe_follow_object", 26, 0, 0),
          new Entry("timer", 27, 1, 1),
          new Entry("team_y_direction", 28, 1, 1),
          new Entry("has_data", 29, 1, 1),
          new Entry("get_radius", 30, 0, 0),
          new Entry("get_ping_pong_projectile_distance", 31, 0, 0),
          new Entry("is_kamikazing", 32, 0, 0),
          new Entry("is_clone", 33, 0, 0),
          new Entry("get_speed", 34, 0, 0),
          new Entry("is_active_or_secondary_champion", 35, 0, 0),
          new Entry("map_width", 36, 0, 0),
          new Entry("map_height", 37, 0, 0),
          new Entry("map_tile_width", 38, 0, 0),
          new Entry("map_tile_height", 39, 0, 0),
          new Entry("is_deploying", 40, 0, 0),
          new Entry("is_flying", 41, 0, 0),
          new Entry("is_grounded", 42, 0, 0),
          new Entry("is_champion", 43, 0, 0),
          new Entry("has_crown_tower_in_range", 44, 1, 1),
          new Entry("group_count", 45, 0, 0),
          new Entry("target_is_crown_tower", 46, 0, 0));

  private static final Map<String, Entry> BY_NAME = new HashMap<>();

  static {
    for (Entry entry : ALL) {
      BY_NAME.put(entry.name().toLowerCase(Locale.ROOT), entry);
    }
  }

  private BattleFunctions() {
    // Utility class
  }

  /** The function a name names, or null. */
  public static Entry byName(String name) {
    return BY_NAME.get(name.toLowerCase(Locale.ROOT));
  }

  /** The function of an id. */
  public static Entry byId(int id) {
    return ALL.get(id);
  }

  /** The id of a function by its name, for code that calls one it knows. */
  public static int id(String name) {
    Entry entry = byName(name);
    if (entry == null) {
      throw new IllegalArgumentException("no battle function " + name);
    }
    return entry.id();
  }
}
