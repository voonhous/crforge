package org.crforge.desktop.battle;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.TrajectoryRecorder;
import org.crforge.core.battle.unit.WorldObserver;

/**
 * Records the run of every unit a card play makes, each with the battle core's own {@link
 * TrajectoryRecorder}, and writes them out on request.
 *
 * <p>The hub is attached to the battle's world once, before the first step, as a single observer. A
 * recorder is made for a unit as the play makes it (the world's {@code characterPlayed} call, in
 * the command pass at the head of the play's step, before the entity tick), so it sees the unit
 * from its first tick in the holder, as the recorder counts ticks. Every observer call the hub
 * receives is handed on to each recorder made so far, so a recorder hears exactly what it would
 * hear attached to the world itself from that moment on. Units made by a spawner, a death spawn or
 * an ability are not played and are not recorded.
 */
public final class TrajectoryHub {

  /** The recorders, by the name of the unit they follow, in the order the units were played. */
  private final Map<String, TrajectoryRecorder> recorders = new LinkedHashMap<>();

  /** The observer attached to the world, which hands every call to the recorders. */
  private final WorldObserver observer;

  public TrajectoryHub() {
    InvocationHandler handler = this::forward;
    this.observer =
        (WorldObserver)
            Proxy.newProxyInstance(
                WorldObserver.class.getClassLoader(),
                new Class<?>[] {WorldObserver.class},
                handler);
  }

  /** Attaches the hub to a world; call once, before the battle's first step. */
  public void attach(BattleWorld world) {
    world.addObserver(observer);
  }

  /**
   * Starts recording a unit that is not made by a card play, such as a golden scenario's unit
   * placed directly. Call it before the step the unit is first visited in.
   */
  public void follow(CharacterEntity unit) {
    recorders.putIfAbsent(unit.name(), new TrajectoryRecorder(unit));
  }

  /** How many units are being recorded. */
  public int size() {
    return recorders.size();
  }

  /**
   * Writes one file per recorded unit, named after its unit, into a folder, creating it.
   *
   * @return the files written, in the order the units were played
   */
  public List<Path> export(Path folder) throws IOException {
    List<Path> written = new ArrayList<>();
    if (recorders.isEmpty()) {
      return written;
    }
    Files.createDirectories(folder);
    for (Map.Entry<String, TrajectoryRecorder> entry : recorders.entrySet()) {
      Path file = folder.resolve(entry.getKey() + ".json");
      entry.getValue().writeTo(file);
      written.add(file);
    }
    return written;
  }

  /** The proxy's handler: every observer call reaches every recorder made before it. */
  private Object forward(Object proxy, Method method, Object[] args) throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return switch (method.getName()) {
        case "equals" -> proxy == args[0];
        case "hashCode" -> System.identityHashCode(proxy);
        default -> "TrajectoryHub" + recorders.keySet();
      };
    }
    for (TrajectoryRecorder recorder : List.copyOf(recorders.values())) {
      try {
        method.invoke(recorder, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
    // A played unit is followed from the call that announces it; its own recorder does not hear
    // the announcement, which no recorder reads.
    if (method.getName().equals("characterPlayed") && args[1] instanceof CharacterEntity unit) {
      follow(unit);
    }
    // Every observer call returns nothing.
    return null;
  }
}
