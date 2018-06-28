/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.protocol;

import sirius.db.es.ElasticEntity;
import sirius.db.es.annotations.Analyzed;
import sirius.db.mixing.Mapping;
import sirius.db.mixing.types.StringMap;
import sirius.kernel.di.std.Framework;

import java.time.LocalDateTime;

/**
 * Stores an exception along with some context.
 */
@Framework(Protocols.FRAMEWORK_PROTOCOLS)
public class StoredIncident extends ElasticEntity {

    /**
     * Contains the error message.
     */
    public static final Mapping MESSAGE = Mapping.named("message");
    @Analyzed(indexOptions = Analyzed.IndexOption.DOCS)
    private String message;

    /**
     * Contains the category or logger name which logged the error.
     */
    public static final Mapping CATEGORY = Mapping.named("category");
    private String category;

    /**
     * Contains the name of the node on which the error occured.
     */
    public static final Mapping NODE = Mapping.named("node");
    private String node;

    /**
     * Contains the code-location where the error occured.
     */
    public static final Mapping LOCATION = Mapping.named("location");
    private String location;

    /**
     * Contains the exception stacktrace.
     */
    public static final Mapping STACK = Mapping.named("stack");
    private String stack;

    /**
     * Contains the timestamp when the error first occured.
     */
    public static final Mapping FIRST_OCCURRENCE = Mapping.named("firstOccurrence");
    private LocalDateTime firstOccurrence = LocalDateTime.now();

    /**
     * Contains the timestamp when the error last occured.
     */
    public static final Mapping LAST_OCCURRENCE = Mapping.named("lastOccurrence");
    private LocalDateTime lastOccurrence = LocalDateTime.now();

    /**
     * Contains the number of occurences between the <tt>firstOccurence</tt> and <tt>lastOccurence</tt>.
     */
    public static final Mapping NUMBER_OF_OCCURRENCES = Mapping.named("numberOfOccurrences");
    private int numberOfOccurrences = 0;

    /**
     * Contains the <tt>mapped diagnostic context</tt>, providing some insight how and why the error occured.
     */
    public static final Mapping MDC = Mapping.named("mdc");
    private final StringMap mdc = new StringMap();

    /**
     * Contains the name of the user that was logged in while the error occured.
     */
    public static final Mapping USER = Mapping.named("user");
    private String user;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getStack() {
        return stack;
    }

    public void setStack(String stack) {
        this.stack = stack;
    }

    public LocalDateTime getFirstOccurrence() {
        return firstOccurrence;
    }

    public void setFirstOccurrence(LocalDateTime firstOccurrence) {
        this.firstOccurrence = firstOccurrence;
    }

    public LocalDateTime getLastOccurrence() {
        return lastOccurrence;
    }

    public void setLastOccurrence(LocalDateTime lastOccurrence) {
        this.lastOccurrence = lastOccurrence;
    }

    public int getNumberOfOccurrences() {
        return numberOfOccurrences;
    }

    public void setNumberOfOccurrences(int numberOfOccurrences) {
        this.numberOfOccurrences = numberOfOccurrences;
    }

    public StringMap getMdc() {
        return mdc;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }
}