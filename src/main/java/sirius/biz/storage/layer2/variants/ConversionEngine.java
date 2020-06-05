/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.biz.storage.layer2.variants;

import sirius.biz.storage.layer1.FileHandle;
import sirius.biz.storage.layer2.Blob;
import sirius.biz.storage.util.StorageUtils;
import sirius.kernel.Sirius;
import sirius.kernel.async.Promise;
import sirius.kernel.async.Tasks;
import sirius.kernel.di.GlobalContext;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.Register;
import sirius.kernel.health.Exceptions;
import sirius.kernel.settings.Extension;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Responsible for generating {@link BlobVariant variants} of a given blob.
 * <p>
 * Each blob can have any number of variants (e.g. resized JPG images of a given raw or EPS file). Each variant has
 * a distinctive name. This name is used to lookup the appropriate conversion pipeline by inspecting the system
 * config section {@link #CONFIG_KEY_VARIANTS}.
 * <p>
 * These settings will be used and passed on to the selected {@link Converter}. Note that the converter isn't
 * specified directly, but another config section in {@link #CONFIG_KEY_CONVERTERS} is used to determine the effective
 * implementation (addressing the appropriate {@link ConverterFactory} along with default settings).
 * <p>
 * Using this approach we can provide some standard converters and standard variants which can be fully customized
 * in applications by either overwriting the variants or the converters.
 */
@Register(classes = ConversionEngine.class)
public class ConversionEngine {

    private static final String CONFIG_KEY_VARIANTS = "storage.layer2.conversion.variants";
    private static final String CONFIG_KEY_FILE_EXTENSION = "fileExtension";
    private static final String CONFIG_KEY_CONVERTER = "converter";
    private static final String CONFIG_KEY_CONVERTERS = "storage.layer2.conversion.converters";
    private static final String EXECUTOR_STORAGE_CONVERSION = "storage-conversion";
    private static final String CONFIG_KEY_TYPE = "type";

    @Part
    private Tasks tasks;

    @Part
    private GlobalContext globalContext;

    /**
     * When delivering files (e.g. preview images to be shown in the browser), we normally don't bother to lookup the
     * original filename of the image (as this would required a DB lookup). However, we need to generated a name with
     * the appropriate file extension so that the <tt>Content-Type</tt> is setup properly.
     * <p>
     * Therefore we keep a map which stores the effective file extension per variant as this is both, frequently used
     * and constant over the lifetime of the system.
     */
    private Map<String, String> fileExtensionPerVariant;

    /**
     * Contains the fully initialized and configured {@link Converter} for each variant. As these are itself
     * stateless and remain constant over the lifetime of the system (and also probably expensive to instantiate),
     * we keep a list of instances around.
     */
    private Map<String, Converter> converterPerVariant = new ConcurrentHashMap<>();

    /**
     * Returns the effective file extension of the files generated by a given variant.
     *
     * @param variant the name of the variant
     * @return the file extension of the files generated by the given variant or <tt>null</tt> if the file extension
     * isn't known or not constant.
     */
    @Nullable
    public String determineTargetFileExension(String variant) {
        if (fileExtensionPerVariant == null) {
            initializeFileExtensions();
        }
        return fileExtensionPerVariant.get(variant);
    }

    protected void initializeFileExtensions() {
        fileExtensionPerVariant = Sirius.getSettings()
                                        .getExtensions(CONFIG_KEY_VARIANTS)
                                        .stream()
                                        .collect(Collectors.toMap(Extension::getId,
                                                                  ext -> ext.getString(CONFIG_KEY_FILE_EXTENSION)));
    }

    /**
     * Determines if a configuration is present for the given variant name.
     *
     * @param variant the variant name to check
     * @return <tt>true</tt> if a configuration is present, <tt>false</tt> otherwise
     */
    public boolean isKnownVariant(String variant) {
        if (fileExtensionPerVariant == null) {
            initializeFileExtensions();
        }
        return fileExtensionPerVariant.containsKey(variant);
    }

    /**
     * Returns the cached converter instance for the given variant or creates a new converter if the cache is empty.
     *
     * @param variant the variant to find the converter for
     * @return the converter or <tt>null</tt> if a configuration problem is present for the requested converter
     */
    @Nullable
    private Converter fetchConverter(String variant) {
        return converterPerVariant.computeIfAbsent(variant, this::createConverter);
    }

    /**
     * Creates a new converter for the given variant.
     * <p>
     * This will use the {@link #CONFIG_KEY_VARIANTS variant config} and the
     * {@link #CONFIG_KEY_CONVERTERS converter config} to lookup and invoke the appropriate {@link ConverterFactory}.
     *
     * @param variant the variant for which the converter is to be created
     * @return the newly created converter or <tt>null</tt> if a configuration problem is present
     * @throws IllegalArgumentException if the variant is unknown
     */
    @Nullable
    private Converter createConverter(String variant) {
        Extension variantConfig = Sirius.getSettings().getExtension(CONFIG_KEY_VARIANTS, variant);

        if (variantConfig == null || variantConfig.isDefault()) {
            throw new IllegalArgumentException("Unknown variant: " + variant);
        }

        String converter = variantConfig.get(CONFIG_KEY_CONVERTER).asString();
        Extension converterConfig = Sirius.getSettings().getExtension(CONFIG_KEY_CONVERTERS, converter);
        try {
            return globalContext.findPart(variantConfig.get(CONFIG_KEY_TYPE)
                                                       .asString(converterConfig.get(CONFIG_KEY_TYPE).asString()),
                                          ConverterFactory.class).createConverter(variantConfig, converterConfig);
        } catch (Exception e) {
            Exceptions.handle()
                      .error(e)
                      .to(StorageUtils.LOG)
                      .withSystemErrorMessage("Failed to create a converter of type %s for %s: %s (%s)",
                                              converter,
                                              variant)
                      .handle();

            return null;
        }
    }

    /**
     * Invokes the {@link Converter} which has been configured for the given variant to perform the actual conversion.
     *
     * @param blob    the blob to convert
     * @param variant the variant to generate
     * @return a promise which will either be fullfilled with the generated file or be failed with an appropriate
     * error message
     */
    public Promise<FileHandle> performConversion(Blob blob, String variant) {
        Promise<FileHandle> result = new Promise<>();

        tasks.executor(EXECUTOR_STORAGE_CONVERSION).dropOnOverload(() -> {
            result.fail(new IllegalStateException("Conversion subsystem overloaded!"));
        }).fork(() -> {
            try {
                Converter converter = fetchConverter(variant);
                if (converter == null) {
                    // We use a handled exception here as the error has already been reported and we do not want to jam
                    // the logs with additional error reports for the same problem.
                    throw Exceptions.createHandled()
                                    .withSystemErrorMessage("A configuration problem is present for: %s", variant)
                                    .handle();
                }

                result.success(converter.performConversion(blob));
            } catch (Exception e) {
                result.fail(e);
            }
            //TODO metics
        });

        return result;
    }
}
