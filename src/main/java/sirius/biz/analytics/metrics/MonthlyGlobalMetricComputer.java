/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.analytics.metrics;

import sirius.biz.analytics.scheduler.AnalyticalTask;
import sirius.kernel.di.std.Part;

import javax.annotation.Nullable;
import java.time.LocalDate;

/**
 * Provides a base class for all metric computers which are invoked on a monthly basis to compute a global metric.
 * <p>
 * Subclasses have to be {@link sirius.kernel.di.std.Register registered} as <tt>MonthlyGlobalMetricComputer</tt> so that
 * they are visible to the framework.
 * <p>
 * Note that these computers are also invoked on a daily basis for the current month to update its value
 * (if possible - as best effort scheduling is used).
 */
public abstract class MonthlyGlobalMetricComputer {

    @Part
    @Nullable
    protected Metrics metrics;

    /**
     * Performs the computation for the given date.
     *
     * @param date the date for which the computation should be performed
     */
    public abstract void compute(LocalDate date);

    /**
     * Returns the level of this computer.
     *
     * @return the priority level of this computer
     * @see AnalyticalTask#getLevel() for an in-depth description
     */
    public int getLevel() {
        return AnalyticalTask.DEFAULT_LEVEL;
    }
}
