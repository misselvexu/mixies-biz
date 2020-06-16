/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.storage.layer3;

import sirius.biz.jobs.batch.SimpleBatchProcessJobFactory;
import sirius.biz.jobs.params.BooleanParameter;
import sirius.biz.jobs.params.EnumParameter;
import sirius.biz.jobs.params.Parameter;
import sirius.biz.process.ProcessContext;
import sirius.biz.process.logs.ProcessLog;
import sirius.kernel.commons.Strings;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.Register;
import sirius.kernel.nls.NLS;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Provides a generic job to copy or move {@link VirtualFile virtual files} in the background.
 */
@Register
public class TransferFilesJob extends SimpleBatchProcessJobFactory {

    public static final String NAME = "transfer-files";
    public static final String SOURCE_PARAMETER_NAME = "source";
    public static final String DESTINATION_PARAMETER_NAME = "destination";
    public static final String MODE_PARAMETER_NAME = "mode";
    public static final String SMART_TRANSFER_PARAMETER_NAME = "smartTransfer";

    /**
     * Determines the mode to use when transferring files.
     */
    public enum TransferMode {
        COPY, MOVE, COPY_CONTENTS, MOVE_CONTENTS;

        @Override
        public String toString() {
            return NLS.get(TransferMode.class.getSimpleName() + "." + name());
        }
    }

    private FileOrDirectoryParameter sourceParameter =
            new FileOrDirectoryParameter(SOURCE_PARAMETER_NAME, "$TransferFilesJob.source").markRequired();
    private FileOrDirectoryParameter destinationParameter =
            new FileOrDirectoryParameter(DESTINATION_PARAMETER_NAME, "$TransferFilesJob.destination").markRequired();
    private EnumParameter<TransferMode> modeParameter =
            new EnumParameter<>(MODE_PARAMETER_NAME, "$TransferFilesJob.mode", TransferMode.class).withDefault(
                    TransferMode.COPY).markRequired();
    private BooleanParameter smartTransferParameter =
            new BooleanParameter(SMART_TRANSFER_PARAMETER_NAME, "$TransferFilesJob.smartTransfer").withDefaultTrue();

    @Part
    private VirtualFileSystem virtualFileSystem;

    @Override
    protected void execute(ProcessContext process) throws Exception {
        VirtualFile source = process.require(sourceParameter);
        VirtualFile destination = process.require(destinationParameter);
        TransferMode mode = process.require(modeParameter);

        if (mode == TransferMode.COPY_CONTENTS || mode == TransferMode.MOVE_CONTENTS) {
            if (!source.isDirectory()) {
                process.log(ProcessLog.error()
                                      .withNLSKey("TransferFilesJob.illegalSource")
                                      .withContext("sourceFile", source));
                return;
            }
            if (!destination.isDirectory()) {
                process.log(ProcessLog.error()
                                      .withNLSKey("TransferFilesJob.illegalDestination")
                                      .withContext("destinationFile", destination));
                return;
            }
        }

        Transfer transfer = source.transferTo(destination).batch(process);

        if (Boolean.TRUE.equals(process.require(smartTransferParameter))) {
            transfer.smartTransfer();
        }

        switch (mode) {
            case COPY:
                transfer.copy();
                return;
            case MOVE:
                transfer.move();
                return;
            case COPY_CONTENTS:
                transfer.copyContents();
                return;
            case MOVE_CONTENTS:
                transfer.moveContents();
        }
    }

    @Override
    protected String createProcessTitle(Map<String, String> context) {
        String source = sourceParameter.get(context).map(VirtualFile::name).orElse("?");
        String destination = destinationParameter.get(context).map(VirtualFile::name).orElse("?");
        String mode = modeParameter.get(context).orElse(TransferMode.COPY).toString();
        return Strings.apply("%s: %s -> %s", mode, source, destination);
    }

    @Override
    protected void collectParameters(Consumer<Parameter<?, ?>> parameterCollector) {
        parameterCollector.accept(sourceParameter);
        parameterCollector.accept(destinationParameter);
        parameterCollector.accept(modeParameter);
        parameterCollector.accept(smartTransferParameter);
    }

    @Nonnull
    @Override
    public String getName() {
        return NAME;
    }
}
