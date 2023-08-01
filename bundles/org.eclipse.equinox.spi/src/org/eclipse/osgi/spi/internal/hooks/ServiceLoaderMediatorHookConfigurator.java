package org.eclipse.osgi.spi.internal.hooks;

import org.eclipse.osgi.internal.hookregistry.HookConfigurator;
import org.eclipse.osgi.internal.hookregistry.HookRegistry;

public class ServiceLoaderMediatorHookConfigurator implements HookConfigurator {
	// TODO: this needs the following VM argument:
	// -Dosgi.framework.extensions=reference:file:${project_loc:/de.iils.dc43.core.osgi}
	@Override
	public void addHooks(HookRegistry hookRegistry) {
		hookRegistry.addClassLoaderHook(new ServiceLoaderMediatorHook());
	}
}
