/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.jobs.batch.file;

import sirius.biz.jobs.params.BooleanParameter;
import sirius.biz.jobs.params.Parameter;
import sirius.biz.process.ProcessContext;

import java.util.function.Consumer;

/**
 * Provides a base implementation for batch jobs which import line based files using a
 * {@link sirius.biz.importer.format.ImportDictionary}.
 */
public abstract class DictionaryBasedImportJobFactory extends LineBasedImportJobFactory {

    /**
     * Contains the parameter which is used to determine if empty values should be ignored).
     */
    protected final BooleanParameter ignoreEmptyParameter;

    protected DictionaryBasedImportJobFactory() {
        ignoreEmptyParameter = new BooleanParameter("ignoreEmpty", "$DictionaryBasedImportJobFactory.ignoreEmpty");
        ignoreEmptyParameter.withDescription("$DictionaryBasedImportJobFactory.ignoreEmpty.help");
    }

    @Override
    protected abstract DictionaryBasedImportJob createJob(ProcessContext process);

    @Override
    protected void collectParameters(Consumer<Parameter<?, ?>> parameterCollector) {
        super.collectParameters(parameterCollector);
        parameterCollector.accept(ignoreEmptyParameter);
    }
}
