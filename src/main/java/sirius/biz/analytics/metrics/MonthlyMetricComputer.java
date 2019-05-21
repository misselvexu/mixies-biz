/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.analytics.metrics;

import sirius.biz.analytics.scheduler.AnalyticalTask;
import sirius.db.mixing.BaseEntity;
import sirius.kernel.di.std.Part;

/**
 * Provides a base class for all metric computers which are invoked on a monthly basis to compute a metric for each of
 * the referenced entities.
 * <p>
 * Subclasses have to be {@link sirius.kernel.di.std.Register registered} as <tt>MonthlyMetricComputer</tt> so that
 * they are visible to the framework.
 * <p>
 * Note that these computers are also invoked on a daily basis for the current month to update its value
 * (if possible - as nest effort scheduling is used).
 *
 * @param <E> the type of entities being processed by this computer
 */
public abstract class MonthlyMetricComputer<E extends BaseEntity<?>> implements AnalyticalTask<E> {

    @Part
    protected Metrics metrics;
}