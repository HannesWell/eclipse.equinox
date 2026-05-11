/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.equinox.launcher;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

public class JNIBridgeTest {

	@Test
	void testSetLauncherInfoReturnsOfficialNameAddress() throws Exception {
		Path launcherLibrary = findLauncherLibrary(findBinariesRoot(findEquinoxRepo()));
		long officialNameAddress = new JNIBridge(launcherLibrary.toString()).setLauncherInfo("launcher", "Eclipse"); //$NON-NLS-1$ //$NON-NLS-2$
		assertNotEquals(0, officialNameAddress);
	}

	private static Path findEquinoxRepo() throws Exception {
		Path codeSource = Path.of(JNIBridgeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		if (Files.isDirectory(codeSource) && codeSource.endsWith(Path.of("org.eclipse.equinox.launcher.tests"))) { //$NON-NLS-1$
			return codeSource.getParent().getParent();
		}
		if (Files.isDirectory(codeSource)
				&& codeSource.endsWith(Path.of("org.eclipse.equinox.launcher.tests/target/classes"))) { //$NON-NLS-1$
			return codeSource.getParent().getParent().getParent().getParent();
		}
		throw new IllegalStateException("Unknown state"); //$NON-NLS-1$
	}

	private static Path findBinariesRoot(Path equinoxRepositoryPath) {
		String binariesLocationName = "EQUINOX_BINARIES_LOC"; //$NON-NLS-1$
		Optional<Path> binariesRepo = Optional.ofNullable(System.getProperty(binariesLocationName))
				.or(() -> Optional.ofNullable(System.getenv(binariesLocationName))).map(Path::of);
		if (binariesRepo.isEmpty()) {
			binariesRepo = Stream.of("equinox.binaries", "rt.equinox.binaries") //$NON-NLS-1$ //$NON-NLS-2$
					.map(equinoxRepositoryPath::resolveSibling).filter(Files::isDirectory).findFirst();
		}
		return binariesRepo.orElseThrow(() -> new IllegalStateException(
				"Location of equinox binaries could not be auto-detected and was not provided via the System.property or environment.variable '" //$NON-NLS-1$
						+ binariesLocationName + "'."));
	}

	private static Path findLauncherLibrary(Path binariesRoot) throws IOException {
		String os = System.getProperty("osgi.os"); //$NON-NLS-1$
		String ws = System.getProperty("osgi.ws"); //$NON-NLS-1$
		String arch = System.getProperty("osgi.arch"); //$NON-NLS-1$
		Path launcherDir = binariesRoot.resolve(String.format("org.eclipse.equinox.launcher.%s.%s.%s", ws, os, arch)); //$NON-NLS-1$
		try (Stream<Path> files = Files.walk(launcherDir, 1).filter(Files::isRegularFile)) {
			return files.filter(file -> {
				String filename = file.getFileName().toString();
				return filename.startsWith("eclipse_") && hasLauncherLibraryExtension(filename, os); //$NON-NLS-1$
			}).findFirst().orElseThrow(() -> new IllegalStateException("No launcher library found in " + launcherDir)); //$NON-NLS-1$
		}
	}

	private static boolean hasLauncherLibraryExtension(String filename, String os) {
		if (os.contains("win")) { //$NON-NLS-1$
			return filename.endsWith(".dll"); //$NON-NLS-1$
		}
		if (os.contains("mac")) { //$NON-NLS-1$
			return filename.endsWith(".dylib") || filename.endsWith(".jnilib") || filename.endsWith(".so"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		return filename.endsWith(".so"); //$NON-NLS-1$
	}
}
