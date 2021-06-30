/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.tycho.search;

import sirius.biz.tycho.QuickAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a result as generated by a {@link OpenSearchProvider}.
 */
public class OpenSearchResult {

    private String label;
    private String description;
    private String url;
    private List<QuickAction> actions = new ArrayList<>();

    /**
     * Specifies the label which will be the clickable main action.
     *
     * @param label the label of the main action
     * @return the result itself for fluent method calls.
     */
    public OpenSearchResult withLabel(String label) {
        this.label = label;
        return this;
    }

    /**
     * Specifies the URL to invoke if the result label is clicked.
     * <p>
     * Use <tt>javascript:someFunction()</tt> to invoke a JS callback rather than navigating somewhere.
     *
     * @param url the action to open / execute
     * @return the result itself for fluent method calls.
     */
    public OpenSearchResult withURL(String url) {
        this.url = url;
        return this;
    }

    /**
     * Provides an additional description to show.
     *
     * @param description the description of this result
     * @return the result itself for fluent method calls.
     */
    public OpenSearchResult withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Adds a quick action to this result.
     *
     * @param action the secondary action to show for this result.
     * @return the result itself for fluent method calls.
     */
    public OpenSearchResult withQuickAction(QuickAction action) {
        this.actions.add(action);
        return this;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public List<QuickAction> getActions() {
        return Collections.unmodifiableList(actions);
    }
}
