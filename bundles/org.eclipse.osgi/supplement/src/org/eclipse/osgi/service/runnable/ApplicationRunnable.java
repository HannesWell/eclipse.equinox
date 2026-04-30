/*******************************************************************************
 * Copyright (c) 2004, 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.service.runnable;

/**
 * A parameterized runnable that can be stopped.
 * <p>
 * This class is for internal use by the platform-related plug-ins. Clients
 * outside of the base platform should not reference or subclass this class.
 * </p>
 * 
 * @since 3.2
 * @noreference This interface is not intended to be referenced by clients.
 * @noimplement This interface is not intended to be implemented by clients.
 */
public interface ApplicationRunnable extends ParameterizedRunnable {
	/**
	 * Forces this runnable to stop.
	 */
	public void stop();
}
