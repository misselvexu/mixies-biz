/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.jobs.scheduler;

import sirius.db.mixing.BaseEntity;
import sirius.db.mixing.Mapping;

/**
 * Describes entities which are handled by the {@link SchedulerController} as well as the {@link JobSchedulerLoop}.
 */
public interface SchedulerEntry {

    /**
     * Contains the scheduler data composite which describes when the job is to be executed.
     */
    Mapping SCHEDULER_DATA = Mapping.named("schedulerData");

    /**
     * Contains the job configuration composite which describes the job to execute along with its parameters.
     */
    Mapping JOB_CONFIG_DATA = Mapping.named("jobConfigData");

    /**
     * Makes {@link BaseEntity#getIdAsString()} visible so that it can be used in Tagliatelle templates.
     *
     * @return the id of the underlying entity as string
     */
    String getIdAsString();

    /**
     * Makes {@link BaseEntity#getUniqueName()} visible so that it can be used in Tagliatelle templates.
     *
     * @return the unique name of this entity
     */
    String getUniqueName();

    /**
     * Makes {@link BaseEntity#isNew()} visible so that it can be used in Tagliatelle templates.
     *
     * @return <tt>ture</tt> if the underlying entity has not been persisted yet, <tt>false</tt> otherwise
     */
    boolean isNew();

    /**
     * Provides access to the schedulder data stored in the underlying entity.
     *
     * @return the scheduler data of this entry.
     */
    SchedulerData getSchedulerData();

    /**
     * Provides access to the job configuration stored in the underlying entity.
     *
     * @return the job configuration of this entry.
     */
    JobConfigData getJobConfigData();

    @Override
    String toString();
}