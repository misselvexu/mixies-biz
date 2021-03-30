/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.process;

import sirius.biz.jobs.params.Parameter;
import sirius.biz.process.logs.ProcessLog;
import sirius.biz.process.output.ChartOutput;
import sirius.biz.process.output.ChartProcessOutputType;
import sirius.biz.process.output.LogsProcessOutputType;
import sirius.biz.process.output.ProcessOutput;
import sirius.biz.process.output.TableOutput;
import sirius.biz.process.output.TableProcessOutputType;
import sirius.db.mixing.types.StringMap;
import sirius.kernel.async.CombinedFuture;
import sirius.kernel.async.Future;
import sirius.kernel.async.Promise;
import sirius.kernel.async.TaskContext;
import sirius.kernel.async.Tasks;
import sirius.kernel.commons.Producer;
import sirius.kernel.commons.RateLimit;
import sirius.kernel.commons.Tuple;
import sirius.kernel.commons.UnitOfWork;
import sirius.kernel.commons.Value;
import sirius.kernel.di.std.Part;
import sirius.kernel.health.Average;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.HandledException;
import sirius.kernel.health.Log;
import sirius.kernel.nls.NLS;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Provides the implementation for {@link ProcessContext}.
 */
class ProcessEnvironment implements ProcessContext {

    private final String processId;

    private final RateLimit logLimiter = RateLimit.timeInterval(10, TimeUnit.SECONDS);
    private final RateLimit timingLimiter = RateLimit.timeInterval(10, TimeUnit.SECONDS);
    private final CombinedFuture barrier = new CombinedFuture();
    private final RateLimit stateUpdate = RateLimit.timeInterval(5, TimeUnit.SECONDS);
    private Map<String, Average> timings;
    private Map<String, Average> adminTimings;

    @Part
    @Nullable
    private static Processes processes;

    @Part
    private static Tasks tasks;

    protected ProcessEnvironment(String processId) {
        this.processId = processId;
    }

    @Override
    public void logLimited(Object message) {
        if (logLimiter.check()) {
            log(NLS.toUserString(message));
        }
    }

    @Override
    public void smartLogLimited(Supplier<Object> messageSupplier) {
        if (logLimiter.check()) {
            log(NLS.toUserString(messageSupplier.get()));
        }
    }

    @Override
    public void addTiming(String counter, long millis) {
        addTiming(counter, millis, false);
    }

    @Override
    public void addTiming(String counter, long millis, boolean adminOnly) {
        if (adminOnly) {
            getAdminTimings().computeIfAbsent(counter, ignored -> new Average()).addValue(millis);
        } else {
            getTimings().computeIfAbsent(counter, ignored -> new Average()).addValue(millis);
        }

        if (timingLimiter.check()) {
            processes.addTimings(processId, getTimings(), getAdminTimings());
        }
    }

    @Override
    public void addDebugTiming(String counter, long millis) {
        addDebugTiming(counter, millis, false);
    }

    @Override
    public void addDebugTiming(String counter, long millis, boolean adminOnly) {
        if (isDebugging()) {
            addTiming(counter, millis, adminOnly);
        }
    }

    @Override
    public void incCounter(String counter) {
        incCounter(counter, false);
    }

    @Override
    public void incCounter(String counter, boolean adminOnly) {
        addTiming(counter, -1L, adminOnly);
    }

    private Map<String, Average> getTimings() {
        if (timings == null) {
            initializeTimings();
        }

        return timings;
    }

    private Map<String, Average> getAdminTimings() {
        if (adminTimings == null) {
            initializeTimings();
        }

        return adminTimings;
    }

    private synchronized void initializeTimings() {
        if (timings != null) {
            return;
        }

        timings = new ConcurrentHashMap<>();
        adminTimings = new ConcurrentHashMap<>();
        processes.fetchProcess(processId).ifPresent(process -> {
            process.getPerformanceCounters().data().keySet().forEach(key -> {
                int counter = process.getPerformanceCounters().get(key).orElse(0);
                int timing = process.getTimings().get(key).orElse(0);

                Average average = new Average();
                average.addValues(counter, (double) counter * timing);
                timings.put(key, average);
            });

            process.getAdminPerformanceCounters().data().keySet().forEach(key -> {
                int counter = process.getAdminPerformanceCounters().get(key).orElse(0);
                int timing = process.getAdminTimings().get(key).orElse(0);

                Average average = new Average();
                average.addValues(counter, (double) counter * timing);
                adminTimings.put(key, average);
            });
        });
    }

    @Override
    public String getProcessId() {
        return processId;
    }

    @Override
    public String getTitle() {
        return processes.fetchProcess(processId).map(Process::getTitle).orElse(null);
    }

    @Override
    public void updateTitle(String newTitle) {
        processes.updateTitle(processId, newTitle);
    }

    @Override
    public void log(ProcessLog logEntry) {
        processes.log(processId, logEntry);
    }

    @Override
    public void debug(ProcessLog logEntry) {
        if (isDebugging()) {
            log(logEntry);
        }
    }

    @Override
    public HandledException handle(Exception e) {
        HandledException handledException = Exceptions.handle(Log.BACKGROUND, e);
        log(ProcessLog.error().withMessage(handledException.getMessage()));
        return handledException;
    }

    @Override
    public boolean isDebugging() {
        return processes.fetchProcess(processId).map(Process::isDebugging).orElse(true);
    }

    @Override
    public boolean isErroneous() {
        return processes.fetchProcess(processId).map(Process::isErrorneous).orElse(true);
    }

    @Override
    public void markCompleted() {
        processes.markCompleted(processId, timings, adminTimings);
    }

    /**
     * Flushes all timings for a partial execution.
     */
    protected void flushTimings() {
        if (timings != null) {
            processes.addTimings(processId, getTimings(), getAdminTimings());
        }
    }

    @Override
    public void log(String message) {
        log(ProcessLog.info().withMessage(message));
    }

    @Override
    public void trace(String s) {
        // ignored
    }

    /**
     * Invoked if {@link sirius.kernel.async.TaskContext#setState(String, Object...)} is called in the attached
     * context.
     *
     * @param message the message to set as state
     * @deprecated Use either {@link #forceUpdateState(String)} or {@link #tryUpdateState(String)}
     */
    @Override
    @Deprecated
    public void setState(String message) {
        processes.setStateMessage(processId, message);
    }

    /**
     * Updates the "current state" message of the process.
     * <p>
     * Note that this doesn't perform any rate limiting etc. Therefore {@link TaskContext#shouldUpdateState()}
     * along with {@link TaskContext#setState(String, Object...)} is most probably a better choice.
     *
     * @param state the new state message to show
     * @deprecated Use either {@link #tryUpdateState(String)} or {@link #forceUpdateState(String)}.
     */
    @Override
    @Deprecated
    public void setCurrentStateMessage(String state) {
        processes.setStateMessage(processId, state);
    }

    @Override
    public RateLimit shouldUpdateState() {
        return stateUpdate;
    }

    @Override
    public void tryUpdateState(String message) {
        if (shouldUpdateState().check()) {
            forceUpdateState(message);
        }
    }

    @Override
    public void forceUpdateState(String message) {
        processes.setStateMessage(processId, message);
    }

    @Override
    public void markErroneous() {
        processes.markErrorneous(processId);
    }

    @Override
    public void cancel() {
        processes.markCanceled(processId);
    }

    @Override
    public boolean isActive() {
        return processes.fetchProcess(processId)
                        .map(proc -> proc.getState() == ProcessState.RUNNING || proc.getState() == ProcessState.STANDBY)
                        .orElse(false) && tasks.isRunning();
    }

    @Nullable
    public String getUserId() {
        return processes.fetchProcess(processId).map(Process::getUserId).orElse(null);
    }

    @Nullable
    public String getTenantId() {
        return processes.fetchProcess(processId).map(Process::getTenantId).orElse(null);
    }

    @Override
    public Map<String, String> getContext() {
        return processes.fetchProcess(processId)
                        .map(Process::getContext)
                        .map(StringMap::data)
                        .orElse(Collections.emptyMap());
    }

    @Override
    public Value get(String key) {
        return Value.of(getContext().get(key));
    }

    @Override
    public <V> Optional<V> getParameter(Parameter<V> parameter) {
        return parameter.get(getContext());
    }

    @Override
    public <V> V require(Parameter<V> parameter) {
        return parameter.require(getContext());
    }

    @Override
    public void addLink(ProcessLink link) {
        processes.addLink(processId, link);
    }

    @Override
    public void addReference(String reference) {
        processes.addReference(processId, reference);
    }

    @Override
    public void addOutput(ProcessOutput output) {
        processes.addOutput(processId, output);
    }

    @Override
    public void addLogOutput(String name, String label) {
        addOutput(new ProcessOutput().withType(LogsProcessOutputType.TYPE).withName(name).withLabel(label));
    }

    @Override
    public ChartOutput addCharts(String name, String label) {
        addOutput(new ProcessOutput().withType(ChartProcessOutputType.TYPE).withName(name).withLabel(label));
        return new ChartOutput(name, this);
    }

    @Override
    public TableOutput addTable(String name, String label, List<Tuple<String, String>> columns) {
        ProcessOutput output =
                new ProcessOutput().withType(TableProcessOutputType.TYPE).withName(name).withLabel(label);

        // Store the metadata (column names and labels) in the context of the output...
        output.getContext()
              .modify()
              .put(TableProcessOutputType.CONTEXT_KEY_COLUMNS,
                   columns.stream().map(Tuple::getFirst).collect(Collectors.joining("|")));
        columns.forEach(column -> output.getContext().modify().put(column.getFirst(), column.getSecond()));

        addOutput(output);
        return new TableOutput(name, this, columns.stream().map(Tuple::getFirst).collect(Collectors.toList()));
    }

    @Override
    public void addFile(String filename, File data) {
        processes.addFile(processId, filename, data);
    }

    @Override
    public OutputStream addFile(String filename) throws IOException {
        return processes.addFile(processId, filename);
    }

    @Override
    public TableOutput.ColumnBuilder addTable(String name, String label) {
        return new TableOutput.ColumnBuilder(this, name, label);
    }

    @Override
    public <P> Promise<P> computeInSideTask(Producer<P> parallelTask) {
        Promise<P> promise = new Promise<>();
        performInSideTask(() -> promise.success(parallelTask.create())).onFailure(promise::fail);

        return promise;
    }

    @Override
    public Future performInSideTask(UnitOfWork parallelTask) {
        Future future = new Future();
        tasks.executor("process-sidetask").fork(() -> {
            try {
                parallelTask.execute();
                future.success();
            } catch (Exception e) {
                future.fail(e);
            }
        }).onFailure(future::fail);

        barrier.add(future);

        return future;
    }

    protected void awaitCompletion() {
        Future completionFuture = barrier.asFuture();
        if (!completionFuture.isCompleted()) {
            log(ProcessLog.info().withNLSKey("Process.awaitingSideTaskCompletion"));
            while (TaskContext.get().isActive()) {
                if (completionFuture.await(Duration.ofSeconds(1))) {
                    return;
                }
            }
        }
    }
}
