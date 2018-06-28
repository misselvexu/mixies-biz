/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.protocol;

import sirius.db.es.Elastic;
import sirius.kernel.Sirius;
import sirius.kernel.async.Tasks;
import sirius.kernel.commons.Wait;
import sirius.kernel.di.std.ConfigValue;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.Register;
import sirius.kernel.timer.EveryDay;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Removes old protocol and journal entries.
 * <p>
 * The duration for which logs and incidents are kept can be controlled via the <tt>protocoals</tt> section in the
 * system configuration.
 */
@Register(framework = Protocols.FRAMEWORK_JOURNAL)
public class CleanupProtocolsTask implements EveryDay {

    @Part
    private Elastic elastic;

    @Part
    private Tasks tasks;

    @ConfigValue("protocols.keep-logs")
    private Duration keepLogs;

    @ConfigValue("protocols.keep-incidents")
    private Duration keepIncidents;

    @ConfigValue("protocols.keep-mails")
    private Duration keepMails;

    @ConfigValue("protocols.keep-journal")
    private Duration keepJournal;

    @ConfigValue("protocols.keep-neutral-audit-logs")
    private Duration keepNeutralAudits;

    @ConfigValue("protocols.keep-negative-audit-logs")
    private Duration keepNegativeAudits;

    @Override
    public String getConfigKeyName() {
        return "protocols-cleaner";
    }

    @Override
    public void runTimer() throws Exception {
        if (elastic != null && elastic.getReadyFuture().isCompleted() && !Sirius.isStartedAsTest()) {
            tasks.defaultExecutor().fork(() -> {
                deleteLogs();
                deleteIncidents();
                deleteMails();
                deleteAuditLogs();
                deleteJournal();
            });
        }
    }

    private void deleteIncidents() {
        LocalDateTime limit = LocalDateTime.now().minusSeconds(keepIncidents.getSeconds());
        elastic.select(StoredIncident.class)
               .where(Elastic.FILTERS.lt(StoredIncident.LAST_OCCURRENCE, limit))
               .truncate();

        // Give ES some time to digest the delete...
        Wait.seconds(2);
    }

    private void deleteLogs() {
        LocalDateTime limit = LocalDateTime.now().minusSeconds(keepLogs.getSeconds());
        elastic.select(LoggedMessage.class).where(Elastic.FILTERS.lt(LoggedMessage.TOD, limit)).truncate();

        // Give ES some time to digest the delete...
        Wait.seconds(2);
    }

    private void deleteMails() {
        LocalDateTime limit = LocalDateTime.now().minusSeconds(keepMails.getSeconds());
        elastic.select(MailProtocol.class).where(Elastic.FILTERS.lt(MailProtocol.TOD, limit)).truncate();

        // Give ES some time to digest the delete...
        Wait.seconds(2);
    }

    private void deleteAuditLogs() {
        LocalDateTime limit = LocalDateTime.now().minusSeconds(keepNegativeAudits.getSeconds());
        elastic.select(AuditLogEntry.class)
               .eq(AuditLogEntry.NEGATIVE, true)
               .where(Elastic.FILTERS.lt(AuditLogEntry.TIMESTAMP, limit))
               .truncate();

        limit = LocalDateTime.now().minusSeconds(keepNeutralAudits.getSeconds());
        elastic.select(AuditLogEntry.class)
               .eq(AuditLogEntry.NEGATIVE, false)
               .where(Elastic.FILTERS.lt(AuditLogEntry.TIMESTAMP, limit))
               .truncate();

        // Give ES some time to digest the delete...
        Wait.seconds(2);
    }

    private void deleteJournal() {
        if (Sirius.isFrameworkEnabled(Protocols.FRAMEWORK_JOURNAL)) {
            LocalDateTime limit = LocalDateTime.now().minusSeconds(keepJournal.getSeconds());
            elastic.select(JournalEntry.class).where(Elastic.FILTERS.lt(JournalEntry.TOD, limit)).truncate();

            // Give ES some time to digest the delete...
            Wait.seconds(2);
        }
    }
}
