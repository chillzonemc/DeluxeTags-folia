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

  private static Method globalExecute;
  private static Method globalRunAtFixedRate;
  private static Method asyncRunNow;
  private static Method asyncRunAtFixedRate;
  private static Method entityRun;

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
    invoke(globalMethod(scheduler, "execute", Plugin.class, Runnable.class), scheduler, plugin, task);
  }

  public static TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
    if (!REGIONIZED) {
      BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
      return scheduled::cancel;
    }
    Object scheduler = invoke(GET_GLOBAL_REGION_SCHEDULER, null);
    Object scheduled = invoke(
        globalTimerMethod(scheduler),
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
    invoke(asyncMethod(scheduler, "runNow", Plugin.class, Consumer.class), scheduler, plugin, callback(task));
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
        asyncTimerMethod(scheduler),
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
        entityRunMethod(scheduler),
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
    return () -> {
      try {
        task.getClass().getMethod("cancel").invoke(task);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Could not cancel scheduled task", e);
      }
    };
  }

  private static Method globalMethod(Object scheduler, String name, Class<?>... parameterTypes) {
    if (globalExecute == null) {
      globalExecute = requireMethod(scheduler.getClass(), name, parameterTypes);
    }
    return globalExecute;
  }

  private static Method globalTimerMethod(Object scheduler) {
    if (globalRunAtFixedRate == null) {
      globalRunAtFixedRate = requireMethod(
          scheduler.getClass(), "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class
      );
    }
    return globalRunAtFixedRate;
  }

  private static Method asyncMethod(Object scheduler, String name, Class<?>... parameterTypes) {
    if (asyncRunNow == null) {
      asyncRunNow = requireMethod(scheduler.getClass(), name, parameterTypes);
    }
    return asyncRunNow;
  }

  private static Method asyncTimerMethod(Object scheduler) {
    if (asyncRunAtFixedRate == null) {
      asyncRunAtFixedRate = requireMethod(
          scheduler.getClass(),
          "runAtFixedRate",
          Plugin.class,
          Consumer.class,
          long.class,
          long.class,
          TimeUnit.class
      );
    }
    return asyncRunAtFixedRate;
  }

  private static Method entityRunMethod(Object scheduler) {
    if (entityRun == null) {
      entityRun = requireMethod(
          scheduler.getClass(), "run", Plugin.class, Consumer.class, Runnable.class
      );
    }
    return entityRun;
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
