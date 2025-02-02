/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.storage.layer1.replication;

import com.alibaba.fastjson.JSONObject;
import sirius.kernel.di.std.AutoRegister;

/**
 * Defines the database dependent storage repository.
 * <p>
 * This stores and schedules the replication tasks generated by the {@link ReplicationManager}.
 * <p>
 * The actual scheduling is a two step process. The {@link ReplicationBackgroundLoop} checks if the
 * {@link ReplicationTaskExecutor#REPLICATION_TASK_QUEUE} is empty. If so, it invokes {@link #emitBatches()}.
 * This will place a number of batches (each containing several replication tasks) in the queue using the
 * {@link sirius.biz.cluster.work.DistributedTasks} framework. These batches are picked up by the
 * {@link ReplicationTaskExecutor} and submitted back to {@link #executeBatch(JSONObject)} which will then invoke
 * {@link ReplicationManager#executeReplicationTask(String, String, long, boolean)} for each task in the batch.
 */
@AutoRegister
public interface ReplicationTaskStorage {

    /**
     * Creates a replication task which deletes the given object.
     * <p>
     * Note that all preceeding tasks for this object are cancelled.
     *
     * @param primarySpace the space of the original object
     * @param objectId     the id of the original object
     */
    void notifyAboutDelete(String primarySpace, String objectId);

    /**
     * Creates a replication task which copies the given object.
     * <p>
     * Note that all preceeding tasks for this object are cancelled.
     *
     * @param primarySpace  the space of the original object
     * @param objectId      the id of the original object
     * @param contentLength the expected content length
     */
    void notifyAboutUpdate(String primarySpace, String objectId, long contentLength);

    /**
     * Invoked by the {@link ReplicationBackgroundLoop} to fill the task queue of the {@link ReplicationTaskExecutor}.
     *
     * @return the number of tasks that have been scheduled
     */
    int emitBatches();

    /**
     * Executes a batch of replication tasks by evaluating the batch and submitting each task to
     * {@link ReplicationManager#executeReplicationTask(String, String, long, boolean)}.
     *
     * @param batch the batch description generated by {@link #emitBatches()}
     */
    void executeBatch(JSONObject batch);

    /**
     * Computes the total number of existing replication tasks.
     *
     * @return the number of existing replication tasks
     */
    int countTotalNumberOfTasks();

    /**
     * Computes the number of executable replication tasks.
     *
     * @return the number of executable replication tasks
     */
    int countNumberOfExecutableTasks();

    /**
     * Computes the number of tasks queued for execution.
     *
     * @return the number of tasks queued for execution
     */
    int countNumberOfScheduledTasks();
}
