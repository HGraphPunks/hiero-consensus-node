// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared state that bridges yahcli's test helpers with the launcher session listener.
 */
public final class YahcliTestState {
    public static final String TEST_NETWORK = "hapi";
    public static final AtomicReference<String> DEFAULT_CONFIG_LOC = new AtomicReference<>();
    public static final AtomicReference<String> DEFAULT_WORKING_DIR =
            new AtomicReference<>(Path.of("build", "yahcli").toAbsolutePath().toString());

    private YahcliTestState() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void setDefaultConfigLoc(@NonNull final String configLoc) {
        DEFAULT_CONFIG_LOC.set(requireNonNull(configLoc));
    }

    public static void setDefaultWorkingDir(@NonNull final String workingDir) {
        DEFAULT_WORKING_DIR.set(requireNonNull(workingDir));
    }
}
