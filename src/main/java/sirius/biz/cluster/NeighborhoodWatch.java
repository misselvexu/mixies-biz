/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.cluster;

import com.alibaba.fastjson.JSONObject;
import sirius.biz.cluster.work.DistributedQueueInfo;
import sirius.biz.cluster.work.DistributedTasks;
import sirius.db.redis.Redis;
import sirius.kernel.Sirius;
import sirius.kernel.async.AsyncExecutor;
import sirius.kernel.async.BackgroundLoop;
import sirius.kernel.async.CallContext;
import sirius.kernel.async.Orchestration;
import sirius.kernel.async.Tasks;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Value;
import sirius.kernel.di.Initializable;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.Parts;
import sirius.kernel.di.std.Register;
import sirius.kernel.health.Exceptions;
import sirius.kernel.info.Product;
import sirius.kernel.nls.NLS;
import sirius.kernel.timer.EveryDay;
import sirius.kernel.timer.Timers;
import sirius.web.health.Cluster;
import sirius.web.http.WebContext;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Provides a {@link Orchestration} based on <tt>Redis</tt> and {@link Interconnect}.
 * <p>
 * This is used to distribute background tasks ({@link BackgroundLoop}, {@link EveryDay}) across all
 * cluster members while still ensuring that a single job only runs on one node concurrently.
 * <p>
 * Also the provides a possibility to globally or locally disable a certain background activity.
 * <p>
 * Locally the synchronization of background jobs can be controlled via the system configuration
 * providing a key like <tt>orchestration.job-name = SYNC-TYPE</tt> - use a name of {@link SynchronizeType} to specify
 * how to synchronize a job across the cluster.
 */
@Register(classes = {Orchestration.class, NeighborhoodWatch.class, Initializable.class, InterconnectHandler.class})
public class NeighborhoodWatch implements Orchestration, Initializable, InterconnectHandler {

    private static final String EXECUTION_TIMESTAMP_SUFFIX = "-timestamp";
    private static final String EXECUTION_ENABLED_SUFFIX = "-enabled";
    private static final String BACKGROUND_LOOP_PREFIX = "loop-";
    private static final String DAILY_TASK_PREFIX = "task-";
    private static final String QUEUE_PREFIX = "queue-";

    private static final String MESSAGE_TYPE = "type";
    private static final String TYPE_GLOBAL = "global";
    private static final String TYPE_LOCAL = "local";
    private static final String TYPE_BLEED = "bleed";
    private static final String MESSAGE_NAME = "name";
    private static final String MESSAGE_ENABLED = "enabled";
    private static final String MESSAGE_NODE = "node";
    private static final String STATE_ENABLED = "enabled";
    private static final String STATE_DISABLED = "disabled";

    private static final int MIN_WAIT_DAILY_TASK_HOURS = 10;

    @Part
    private Redis redis;

    @Part
    private InterconnectClusterManager clusterManager;

    @Parts(BackgroundLoop.class)
    private Collection<BackgroundLoop> loops;

    @Parts(EveryDay.class)
    private Collection<EveryDay> dailyTasks;

    @Part
    private Timers timers;

    @Part
    private Tasks tasks;

    @Part
    private DistributedTasks distributedTasks;

    @Part
    private Interconnect interconnect;

    private volatile boolean bleeding;

    private Map<String, SynchronizeType> syncSettings = Collections.emptyMap();
    private final Map<String, String> descriptions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> localOverwrite = new ConcurrentHashMap<>();
    private final Map<String, String> executionInfos = new ConcurrentHashMap<>();
    private final Map<String, Boolean> globallyEnabledState = new ConcurrentHashMap<>();

    @Nonnull
    @Override
    public String getName() {
        return "neighborhood-watch";
    }

    @Override
    public void handleEvent(JSONObject event) {
        String name = event.getString(MESSAGE_NAME);
        boolean enabled = event.getBooleanValue(MESSAGE_ENABLED);
        if (Strings.areEqual(event.getString(MESSAGE_TYPE), TYPE_GLOBAL)) {
            globallyEnabledState.put(name, enabled);
            return;
        }

        if (Strings.areEqual(event.getString(MESSAGE_TYPE), TYPE_LOCAL)
            && Strings.areEqual(event.getString(MESSAGE_NODE), CallContext.getNodeName())) {
            if (enabled) {
                localOverwrite.put(name, true);
            } else {
                localOverwrite.remove(name);
            }
        }

        if (Strings.areEqual(event.getString(MESSAGE_TYPE), TYPE_BLEED)
            && Strings.areEqual(event.getString(MESSAGE_NODE), CallContext.getNodeName())) {
            bleeding = enabled;
        }
    }

    private boolean isBackgroundJobGloballyEnabled(String name) {
        try {
            return globallyEnabledState.computeIfAbsent(name, this::readGlobalState);
        } catch (Exception e) {
            Exceptions.handle(Cluster.LOG, e);
            return true;
        }
    }

    private boolean readGlobalState(String name) {
        if (!redis.isConfigured()) {
            return true;
        }

        try {
            String value = redis.query(() -> "Check if " + name + " is enabled",
                                       db -> db.get(name + EXECUTION_ENABLED_SUFFIX));

            return !STATE_DISABLED.equals(value);
        } catch (Exception e) {
            Exceptions.handle(Cluster.LOG, e);
            return true;
        }
    }

    /**
     * Enables or Disables the given background job globally on all nodes.
     *
     * @param name    the name of the background job - provided by {@link #getLocalBackgroundInfo()}
     * @param enabled <tt>true</tt> to enable to job, <tt>false</tt> to disable it
     */
    public void changeGlobalEnabledFlag(String name, boolean enabled) {
        if (redis.isConfigured()) {
            redis.exec(() -> "Update global enabled flag for " + name,
                       db -> db.set(name + EXECUTION_ENABLED_SUFFIX, enabled ? STATE_ENABLED : STATE_DISABLED));
        }

        interconnect.dispatch(getName(),
                              new JSONObject().fluentPut(MESSAGE_TYPE, TYPE_GLOBAL)
                                              .fluentPut(MESSAGE_NAME, name)
                                              .fluentPut(MESSAGE_ENABLED, enabled));
    }

    /**
     * Disables the given bacckground job locally on the given node.
     *
     * @param node        the node to disable the job on
     * @param name        the name of the background job - provided by {@link #getLocalBackgroundInfo()}
     * @param overwritten <tt>true</tt> to overwrite (disable) the job, <tt>false</tt> otherwise
     */
    public void changeLocalOverwrite(String node, String name, boolean overwritten) {
        interconnect.dispatch(getName(),
                              new JSONObject().fluentPut(MESSAGE_TYPE, TYPE_LOCAL)
                                              .fluentPut(MESSAGE_NODE, node)
                                              .fluentPut(MESSAGE_NAME, name)
                                              .fluentPut(MESSAGE_ENABLED, overwritten));
    }

    /**
     * Start bleeding out the given node so that it can be restarted soon.
     * <p>
     * A node which starts bleeding out, will report itself as "unhealthy" to an upfront reverse proxy via
     * {@link ClusterController#ready(WebContext)}. Also, this node will stop picking up additional background work
     * (especially no distributed tasks will be started).
     * <p>
     * In order to check if a node is most probably fully bled out, {@link ClusterController#halted(WebContext)}
     * can be invoked.
     * <p>
     * If a restart or patch script is used to perform a rolling upgrade of a cluster, the process for a node would be
     * to first invoke {@link ClusterController#apiBleed(WebContext, String, String, String)} then wait some time and
     * regularly check {@link ClusterController#halted(WebContext)} until it reports "OK". The node can then be
     * restarted in a save manner.
     * <p>
     * In order for this to work, an upstream load balancer has to use {@link ClusterController#ready(WebContext)}
     * as health check for each node, as this will mark the node as unhealthy as soon as it starts bleeding out.
     *
     * @param node  the node to change
     * @param bleed <tt>true</tt> to start bleeding out (disable) the job, <tt>false</tt> to abort bleeding out
     * @see ClusterController#bleed(WebContext, String, String)
     * @see ClusterController#apiBleed(WebContext, String, String, String)
     * @see ClusterController#ready(WebContext)
     * @see ClusterController#halted(WebContext)
     */
    public void changeBleeding(String node, boolean bleed) {
        interconnect.dispatch(getName(),
                              new JSONObject().fluentPut(MESSAGE_TYPE, TYPE_BLEED)
                                              .fluentPut(MESSAGE_NODE, node)
                                              .fluentPut(MESSAGE_NAME, TYPE_BLEED)
                                              .fluentPut(MESSAGE_ENABLED, bleed));
    }

    @Override
    public boolean tryExecuteBackgroundLoop(String name) {
        String syncName = BACKGROUND_LOOP_PREFIX + name;

        if (localOverwrite.containsKey(syncName)) {
            return false;
        }

        SynchronizeType type = syncSettings.getOrDefault(syncName, SynchronizeType.LOCAL);
        if (type == SynchronizeType.DISABLED) {
            return false;
        }

        // When bleeding out, we only disable CLUSTERED background loops, as some
        // (e.g. DelayLine, AutoBatch) need to continue running to support a save
        // and sane shutdown of the node...
        if (type == SynchronizeType.CLUSTER && bleeding) {
            return false;
        }

        if (!isBackgroundJobGloballyEnabled(syncName)) {
            return false;
        }

        if (redis.isConfigured() && type == SynchronizeType.CLUSTER) {
            return redis.tryLock(syncName, null, Duration.ofMinutes(30));
        } else {
            return true;
        }
    }

    @Override
    public void backgroundLoopCompleted(String name, String executionInfo) {
        try {
            String syncName = BACKGROUND_LOOP_PREFIX + name;
            SynchronizeType type = syncSettings.getOrDefault(syncName, SynchronizeType.LOCAL);
            if (type == SynchronizeType.CLUSTER
                && redis.isConfigured()) {
                redis.unlock(syncName);
            }

            executionInfos.put(syncName, executionInfo);
        } catch (Exception e) {
            Exceptions.handle(Cluster.LOG, e);
        }
    }

    @Override
    public boolean shouldRunDailyTask(String name) {
        String syncName = DAILY_TASK_PREFIX + name;

        if (bleeding) {
            return false;
        }

        if (localOverwrite.containsKey(syncName)) {
            return false;
        }

        SynchronizeType type = syncSettings.getOrDefault(syncName, SynchronizeType.LOCAL);
        if (type == SynchronizeType.DISABLED) {
            return false;
        }

        if (!redis.isConfigured()) {
            executionInfos.put(syncName, "Executed: " + NLS.toUserString(LocalDateTime.now()));

            return true;
        }

        if (!isBackgroundJobGloballyEnabled(syncName)) {
            return false;
        }

        if (type == SynchronizeType.LOCAL || shouldRunClusteredDailyTask(syncName)) {
            executionInfos.put(syncName, "Executed: " + NLS.toUserString(LocalDateTime.now()));
            return true;
        } else {
            return false;
        }
    }

    private boolean shouldRunClusteredDailyTask(String syncName) {
        try {
            String lastExecution = redis.query(() -> "Read last execution of " + syncName,
                                               db -> db.get(syncName + EXECUTION_TIMESTAMP_SUFFIX));
            long lastExecutionMillis = Value.of(lastExecution).asLong(0);
            if (Duration.ofMillis(System.currentTimeMillis() - lastExecutionMillis).toHours()
                < MIN_WAIT_DAILY_TASK_HOURS) {
                return false;
            }

            return redis.query(() -> "Write last execution of " + syncName, db -> {
                long update =
                        db.setnx(syncName + EXECUTION_TIMESTAMP_SUFFIX, String.valueOf(System.currentTimeMillis()));
                if (update == 1L) {
                    db.expire(syncName + EXECUTION_TIMESTAMP_SUFFIX,
                              TimeUnit.HOURS.toSeconds(MIN_WAIT_DAILY_TASK_HOURS));
                    return true;
                } else {
                    return false;
                }
            });
        } catch (Exception e) {
            Exceptions.handle(Cluster.LOG, e);
            return false;
        }
    }

    /**
     * Determines if the given queue is enabled.
     * <p>
     * Note that this only ensures that the queue isn't <tt>DISABLED</tt>. Both, <tt>LOCAL</tt> and <tt>CLUSTER</tt>
     * behave the same as queues are inherent capable of clustering.
     *
     * @param queue the queue to check
     * @return <tt>true</tt> if the queue is enabled, <tt>false</tt> otherwise
     */
    public boolean isDistributedTaskQueueEnabled(String queue) {
        String syncName = QUEUE_PREFIX + queue;

        if (bleeding) {
            return false;
        }

        if (localOverwrite.containsKey(syncName)) {
            return false;
        }

        SynchronizeType type = syncSettings.getOrDefault(syncName, SynchronizeType.LOCAL);
        if (type == SynchronizeType.DISABLED) {
            return false;
        }

        return isBackgroundJobGloballyEnabled(syncName);
    }

    @Override
    public void initialize() throws Exception {
        Map<String, SynchronizeType> targetMap = new HashMap<>();

        for (BackgroundLoop loop : loops) {
            String key = BACKGROUND_LOOP_PREFIX + loop.getName();
            loadAndStoreSetting(key, targetMap, true);
            descriptions.put(key, Strings.apply("BackgroundLoop running at %1.2f Hz", loop.maxCallFrequency()));
        }

        for (EveryDay task : dailyTasks) {
            timers.getExecutionHour(task).ifPresent(executionHour -> {
                String key = DAILY_TASK_PREFIX + task.getConfigKeyName();
                loadAndStoreSetting(key, targetMap, true);
                descriptions.put(key,
                                 Strings.apply("EveryDay task executing between %2d:00 and %2d:59",
                                               executionHour,
                                               executionHour));
            });
        }

        for (DistributedQueueInfo queue : distributedTasks.getQueues()) {
            String key = QUEUE_PREFIX + queue.getName();
            loadAndStoreSetting(key, targetMap, false);
            descriptions.put(key,
                             Strings.apply("DistributedTasks queue synchronized via %s", queue.getConcurrencyToken()));
        }

        syncSettings = targetMap;
    }

    private void loadAndStoreSetting(String key, Map<String, SynchronizeType> targetMap, boolean warnIfMissing) {
        if (!Sirius.getSettings().getConfig().hasPath("orchestration." + key)) {
            if (warnIfMissing) {
                Cluster.LOG.WARN("No configuration found for orchestration." + key);
            }
            targetMap.put(key, SynchronizeType.LOCAL);
            return;
        }

        Value setting = Sirius.getSettings().get("orchestration." + key);
        try {
            targetMap.put(key, SynchronizeType.valueOf(setting.toUpperCase()));
        } catch (IllegalArgumentException e) {
            Cluster.LOG.WARN("Invalid configuration found for orchestration." + key + ": " + setting);
            targetMap.put(key, SynchronizeType.LOCAL);
        }
    }

    /**
     * Returns all background infos for all known cluster members.
     *
     * @return a list of background infos for all known cluster members
     */
    public List<BackgroundInfo> getClusterBackgroundInfo() {
        List<BackgroundInfo> result = new ArrayList<>();
        result.add(getLocalBackgroundInfo());
        clusterManager.callEachNode("/system/cluster/background/" + clusterManager.getClusterAPIToken())
                      .map(this::parseBackgroundInfos)
                      .forEach(result::add);

        return result;
    }

    public boolean isBleeding() {
        return bleeding;
    }

    public int getActiveBackgroundTasks() {
        AsyncExecutor backgroundExecutor = tasks.executorService("background");
        AsyncExecutor distributedTasksExecutor = tasks.executorService("distributed-tasks");
        return backgroundExecutor.getActiveCount()
               + backgroundExecutor.getQueue().size()
               + distributedTasksExecutor.getActiveCount()
               + distributedTasksExecutor.getQueue().size();
    }

    /**
     * Returns a report of all local background activities.
     *
     * @return all background infos for this node
     */
    public BackgroundInfo getLocalBackgroundInfo() {
        BackgroundInfo result = new BackgroundInfo(CallContext.getNodeName(),
                                                   isBleeding(),
                                                   getActiveBackgroundTasks(),
                                                   NLS.convertDuration(Sirius.getUptimeInMilliseconds(), true, false),
                                                   Product.getProduct().getVersion(),
                                                   Product.getProduct().getDetails());

        for (Map.Entry<String, SynchronizeType> job : syncSettings.entrySet()) {
            result.jobs.put(job.getKey(),
                            new BackgroundJobInfo(job.getKey(),
                                                  descriptions.get(job.getKey()),
                                                  job.getValue(),
                                                  localOverwrite.containsKey(job.getKey()),
                                                  isBackgroundJobGloballyEnabled(job.getKey()),
                                                  executionInfos.getOrDefault(job.getKey(), "")));
        }
        return result;
    }

    private BackgroundInfo parseBackgroundInfos(JSONObject jsonObject) {
        if (jsonObject.getBooleanValue(InterconnectClusterManager.RESPONSE_ERROR)) {
            return new BackgroundInfo(jsonObject.getString(InterconnectClusterManager.RESPONSE_NODE_NAME),
                                      false,
                                      0,
                                      "-",
                                      "-",
                                      "-");
        }

        BackgroundInfo result = new BackgroundInfo(jsonObject.getString(InterconnectClusterManager.RESPONSE_NODE_NAME),
                                                   jsonObject.getBooleanValue(ClusterController.RESPONSE_BLEEDING),
                                                   jsonObject.getIntValue(ClusterController.RESPONSE_ACTIVE_BACKGROUND_TASKS),
                                                   jsonObject.getString(ClusterController.RESPONSE_UPTIME),
                                                   jsonObject.getString(ClusterController.RESPONSE_VERSION),
                                                   jsonObject.getString(ClusterController.RESPONSE_DETAILED_VERSION));
        jsonObject.getJSONArray(ClusterController.RESPONSE_JOBS).forEach(job -> {
            try {
                JSONObject jobJson = (JSONObject) job;
                String name = jobJson.getString(ClusterController.RESPONSE_NAME);
                result.jobs.put(name,
                                new BackgroundJobInfo(name,
                                                      jobJson.getString(ClusterController.RESPONSE_DESCRIPTION),
                                                      SynchronizeType.valueOf(jobJson.getString(ClusterController.RESPONSE_LOCAL)),
                                                      jobJson.getBooleanValue(ClusterController.RESPONSE_LOCAL_OVERWRITE),
                                                      jobJson.getBooleanValue(ClusterController.RESPONSE_GLOBALLY_ENABLED),
                                                      jobJson.getString(ClusterController.RESPONSE_EXECUTION_INFO)));
            } catch (Exception e) {
                Exceptions.handle(Cluster.LOG, e);
            }
        });

        return result;
    }
}
