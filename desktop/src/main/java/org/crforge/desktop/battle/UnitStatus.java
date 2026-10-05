package org.crforge.desktop.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * An immutable snapshot of one active buff, or the unit's persistent clone flag. Times use
 * milliseconds, with -1 for an effect that has no expiry timer. The applied side is the buff's
 * side, which can outlive its source; -1 means no side applies to this status.
 */
public record UnitStatus(
    Kind kind, String name, int remainingMs, int totalMs, String source, int appliedSide) {
  /** Display priority: effects that stop actions precede clone identity and other buffs. */
  public enum Kind {
    FROZEN("Frozen"),
    STUNNED("Stunned"),
    CLONE("Clone"),
    OTHER("Effect");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  public boolean timed() {
    return remainingMs >= 0 && totalMs > 0;
  }

  public float remainingShare() {
    return timed() ? Math.max(0, Math.min(1, (float) remainingMs / totalMs)) : 1;
  }

  public String duration() {
    return remainingMs < 0 ? "ongoing" : String.format(Locale.ROOT, "%.1fs", remainingMs / 1000f);
  }

  /**
   * One badge per effect type, using its longest remaining instance. Independent instances and
   * their timers remain in the inspector. An ongoing instance takes precedence over timed ones.
   */
  public static List<UnitStatus> badges(List<UnitStatus> statuses) {
    var groups = new LinkedHashMap<String, UnitStatus>();
    for (UnitStatus status : statuses) {
      String key = status.kind == Kind.OTHER ? "OTHER:" + status.name : status.kind.name();
      groups.merge(
          key,
          status,
          (a, b) ->
              a.remainingMs < 0 || (b.remainingMs >= 0 && a.remainingMs >= b.remainingMs) ? a : b);
    }
    var result = new ArrayList<>(groups.values());
    result.sort(java.util.Comparator.comparing(UnitStatus::kind).thenComparing(UnitStatus::name));
    return List.copyOf(result);
  }
}
