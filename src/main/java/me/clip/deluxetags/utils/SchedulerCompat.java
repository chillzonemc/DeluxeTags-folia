package me.clip.deluxetags.utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Schedules work on Paper and Folia region threads, and on the Bukkit scheduler
 * when those threads are not available.
 */
public final class SchedulerCompat {

  public interface TaskHandle {
    void cancel();
  }

  private static final boolean REGIONIZED = hasMethod(Bukkit.class, "getGlobalRegionScheduler");
  private static final long MILLIS_PER_TICK = 50L;

  private static final Method IS_GLOBAL_TICK_THREAD = regionMethod(Bukkit.class, "isGlobalTickThread");
  private static final Method IS_OWNED_BY_CURRENT_REGION = regionMethod(
      Bukkit.class, "isOwnedByCurrentRegion", Entity.class
  );
  private static final Method GET_GLOBAL_REGION_SCHEDULER = regionMethod(
      Bukkit.class, "getGlobalRegionScheduler"
  );
  private static final Method GET_ASYNC_SCHEDULER = regionMethod(Bukkit.class, "getAsyncScheduler");
  private static final Method GET_ENTITY_SCHEDULER = regionMethod(Entity.class, "getScheduler");

  // Resolve against the public API interfaces (the getters' declared return types), never the
  // runtime implementation classes: Folia's task classes are private nested classes, so a Method
  // taken from them throws IllegalAccessException even though the method itself is public.
  private static final Method GLOBAL_EXECUTE = apiMethod(
      GET_GLOBAL_REGION_SCHEDULER, "execute", Plugin.class, Runnable.class
  );
  private static final Method GLOBAL_RUN_AT_FIXED_RATE = apiMethod(
      GET_GLOBAL_REGION_SCHEDULER, "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class
  );
  private static final Method ASYNC_RUN_NOW = apiMethod(
      GET_ASYNC_SCHEDULER, "runNow", Plugin.class, Consumer.class
  );
  private static final Method ASYNC_RUN_AT_FIXED_RATE = apiMethod(
      GET_ASYNC_SCHEDULER,
      "runAtFixedRate",
      Plugin.class,
      Consumer.class,
      long.class,
      long.class,
      TimeUnit.class
  );
  private static final Method ENTITY_RUN = apiMethod(
      GET_ENTITY_SCHEDULER, "run", Plugin.class, Consumer.class, Runnable.class
  );
  private static final Method TASK_CANCEL = apiMethod(GLOBAL_RUN_AT_FIXED_RATE, "cancel");

  private SchedulerCompat() {
  }

  public static boolean isGlobalThread() {
    if (!REGIONIZED) {
      return Bukkit.isPrimaryThread();
    }
    return (Boolean) invoke(IS_GLOBAL_TICK_THREAD, null);
  }

  public static boolean isPlayerThread(Player player) {
    if (!REGIONIZED) {
      return Bukkit.isPrimaryThread();
    }
    return (Boolean) invoke(IS_OWNED_BY_CURRENT_REGION, null, player);
  }

  public static void runGlobal(Plugin plugin, Runnable task) {
    if (!REGIONIZED) {
      Bukkit.getScheduler().runTask(plugin, task);
      return;
    }
    Object scheduler = invoke(GET_GLOBAL_REGION_SCHEDULER, null);
    invoke(GLOBAL_EXECUTE, scheduler, plugin, task);
  }

  public static TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
    if (!REGIONIZED) {
      BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
      return scheduled::cancel;
    }
    Object scheduler = invoke(GET_GLOBAL_REGION_SCHEDULER, null);
    Object scheduled = invoke(
        GLOBAL_RUN_AT_FIXED_RATE,
        scheduler,
        plugin,
        callback(task),
        delayTicks,
        periodTicks
    );
    return cancelHandle(scheduled);
  }

  public static void runAsync(Plugin plugin, Runnable task) {
    if (!REGIONIZED) {
      Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
      return;
    }
    Object scheduler = invoke(GET_ASYNC_SCHEDULER, null);
    invoke(ASYNC_RUN_NOW, scheduler, plugin, callback(task));
  }

  public static TaskHandle runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
    if (!REGIONIZED) {
      BukkitTask scheduled = Bukkit.getScheduler().runTaskTimerAsynchronously(
          plugin, task, delayTicks, periodTicks
      );
      return scheduled::cancel;
    }
    Object scheduler = invoke(GET_ASYNC_SCHEDULER, null);
    Object scheduled = invoke(
        ASYNC_RUN_AT_FIXED_RATE,
        scheduler,
        plugin,
        callback(task),
        ticksToMillis(delayTicks),
        ticksToMillis(periodTicks),
        TimeUnit.MILLISECONDS
    );
    return cancelHandle(scheduled);
  }

  public static void runOnPlayer(Plugin plugin, Player player, Runnable task) {
    if (!REGIONIZED) {
      Bukkit.getScheduler().runTask(plugin, task);
      return;
    }
    Object scheduler = invoke(GET_ENTITY_SCHEDULER, player);
    invoke(
        ENTITY_RUN,
        scheduler,
        new Object[] { plugin, callback(task), null }
    );
  }

  private static Consumer<Object> callback(Runnable task) {
    return ignored -> task.run();
  }

  private static long ticksToMillis(long ticks) {
    return ticks * MILLIS_PER_TICK;
  }

  private static TaskHandle cancelHandle(Object task) {
    if (task == null) {
      return () -> { };
    }
    return () -> invoke(TASK_CANCEL, task);
  }

  /** Looks up a method on the API type returned by {@code owner}, e.g. ScheduledTask#cancel. */
  private static Method apiMethod(Method owner, String name, Class<?>... parameterTypes) {
    if (owner == null) {
      return null;
    }
    return requireMethod(owner.getReturnType(), name, parameterTypes);
  }

  private static Method regionMethod(Class<?> type, String name, Class<?>... parameterTypes) {
    if (!REGIONIZED) {
      return null;
    }
    return requireMethod(type, name, parameterTypes);
  }

  private static Method requireMethod(Class<?> type, String name, Class<?>... parameterTypes) {
    try {
      return type.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("Missing scheduler method " + type.getName() + "#" + name, e);
    }
  }

  private static boolean hasMethod(Class<?> type, String name) {
    try {
      type.getMethod(name);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static Object invoke(Method method, Object target, Object... args) {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IllegalStateException(cause);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }
}
