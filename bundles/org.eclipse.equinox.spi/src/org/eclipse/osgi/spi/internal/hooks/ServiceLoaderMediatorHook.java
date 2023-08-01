/*******************************************************************************
 * Copyright (c) 2023, 2023 Hannes Wellmann and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Hannes Wellmann - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.spi.internal.hooks;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.internal.hookregistry.ClassLoaderHook;
import org.eclipse.osgi.internal.loader.BundleLoader;
import org.eclipse.osgi.internal.loader.ModuleClassLoader;
import org.eclipse.osgi.storage.BundleInfo.Generation;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.util.tracker.BundleTracker;

public class ServiceLoaderMediatorHook extends ClassLoaderHook {
	// TODO: Consider to provide this in a system-bundle fragment that requires
	// Java-9 and register it via a HOOK_CONFIGURATORS_FILE, see HookRegistry

	private static final String SERVICE_NAME_PREFIX = "META-INF/services/"; //$NON-NLS-1$

	private static final Function<Function<Stream<Class<?>>, Object>, Object> CALLER_CLASS = getCallerClassComputer();
	private static final int CALLER_SEARCH_DEPTH = 13;

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static <T> Function<Function<Stream<Class<?>>, T>, T> getCallerClassComputer() {
		try { // The Java-9+ way
			Class<?> stackWalkerClass = Class.forName("java.lang.StackWalker"); //$NON-NLS-1$
			Class stackWalkerOption = Class.forName("java.lang.StackWalker$Option"); //$NON-NLS-1$
			Enum<?> retainClassReference = Enum.valueOf(stackWalkerOption, "RETAIN_CLASS_REFERENCE"); //$NON-NLS-1$
			Object stackWalker = stackWalkerClass.getMethod("getInstance", Set.class, int.class).invoke(null, //$NON-NLS-1$
					new HashSet<>(Arrays.asList(retainClassReference)), CALLER_SEARCH_DEPTH);

			Method stackWalkerWalk = stackWalkerClass.getMethod("walk", Function.class); //$NON-NLS-1$
			Class<?> stackFrameClass = Class.forName("java.lang.StackWalker$StackFrame"); //$NON-NLS-1$
			Method stackFrameGetDeclaringClass = stackFrameClass.getMethod("getDeclaringClass"); //$NON-NLS-1$

			Function<Stream<?>, Stream<Class<?>>> walkCallerClasses = frames -> frames.<Class<?>>map(f -> {
				try {
					return (Class<?>) stackFrameGetDeclaringClass.invoke(f);
				} catch (ReflectiveOperationException e) {
					return null;
				}
			}).filter(Objects::nonNull).limit(CALLER_SEARCH_DEPTH);

			return function -> {
				try {
					return (T) stackWalkerWalk.invoke(stackWalker, walkCallerClasses.andThen(function));
				} catch (ReflectiveOperationException e) {
					throw new AssertionError("Failed to call method " + stackWalkerWalk, e);
				}
			};
		} catch (ReflectiveOperationException e) { // ignore
		}
		try { // Try the Java-1.8 way
			Class<?> reflectionClass = Class.forName("sun.reflect.Reflection"); //$NON-NLS-1$
			Method getCallerClass = Objects.requireNonNull(reflectionClass.getMethod("getCallerClass", int.class)); //$NON-NLS-1$
			return function -> {
				Stream<Class<?>> stream = IntStream.range(0, CALLER_SEARCH_DEPTH).<Class<?>>mapToObj(i -> {
					try {
						return (Class<?>) getCallerClass.invoke(null, i);
					} catch (ReflectiveOperationException e) {
						throw new AssertionError("Failed to call method " + getCallerClass, e);
					}
				}).filter(Objects::nonNull);
				return function.apply(stream);
			};

		} catch (ReflectiveOperationException e) { // ignore an try Java-8 way
		}
		throw new AssertionError("Neither the Java-8 nor the Java-9+ way to obtain the caller class is available"); //$NON-NLS-1$
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static <T> T walkCallerClasses(Function<Stream<Class<?>>, T> function) {
		return (T) CALLER_CLASS.apply((Function) function);
	}

	private static final Class<?> SERVICE_LOADER_GET_RESOURCES_CALLER;
	private static final Class<?> SERVICE_LOADER_LOAD_CLASS_CALLERS;

	static {
		ClassLoader classLoader = ServiceLoaderMediatorHook.class.getClassLoader();
		URL tracingDummy = classLoader
				.getResource(SERVICE_NAME_PREFIX + "org.eclipse.osgi.spi.internal.hooks.ServiceLoaderMediatorHook");
		@SuppressWarnings("unchecked")
		Optional<Class<?>>[] resourcesCallers = new Optional[1];
		@SuppressWarnings("unchecked")
		Optional<Class<?>>[] classCallers = new Optional[1];
		ClassLoader tracker = new ClassLoader() {
			@Override
			public Enumeration<URL> getResources(String name) throws IOException {
				resourcesCallers[0] = walkCallerClasses(callerClasses -> callerClasses
						.filter(c -> c == ServiceLoader.class || c.getEnclosingClass() == ServiceLoader.class)
						.findFirst());
				return Collections.enumeration(Arrays.asList(tracingDummy));
			}

			@Override
			public Class<?> loadClass(String name) throws ClassNotFoundException {
				if ("a.caller.tracing.Dummy".equals(name)) {
					classCallers[0] = walkCallerClasses(callerClasses -> callerClasses
							.filter(c -> c == ServiceLoader.class || c.getEnclosingClass() == ServiceLoader.class)
							.findFirst());
				}
				throw new ClassNotFoundException();
			}
		};
		try {
			ServiceLoader.load(ServiceLoaderMediatorHook.class, tracker).findFirst();
		} catch (ServiceConfigurationError e) { // expected
		}
		SERVICE_LOADER_GET_RESOURCES_CALLER = resourcesCallers[0].get();
		SERVICE_LOADER_LOAD_CLASS_CALLERS = classCallers[0].get();
	}

	private static final String OSGI_SERVICELOADER_NAMESPACE = "osgi.serviceloader"; //$NON-NLS-1$

	private static final class BundleServices {
		private static final Pattern SERVICE_LOADER_FILTER = Pattern
				.compile("\\(" + OSGI_SERVICELOADER_NAMESPACE + "=([\\w\\$\\.]+)\\)"); //$NON-NLS-1$ //$NON-NLS-2$
		private static final int SERVICE_NAME = 1;

		final Set<String> consumedServices;
		final Map<String, List<String>> providedServices;

		public BundleServices(Bundle bundle, BundleWiring wiring) {
			this.providedServices = getProvidedServices(bundle, wiring);
			if (hasServiceConsumerRequirement(wiring)) {
				this.consumedServices = wiring.getRequirements(OSGI_SERVICELOADER_NAMESPACE).stream() // $NON-NLS-1$
						.map(r -> r.getDirectives().getOrDefault("filter", "")) //$NON-NLS-1$ //$NON-NLS-2$
						.map(SERVICE_LOADER_FILTER::matcher).filter(Matcher::matches).map(m -> m.group(SERVICE_NAME))
						.filter(BundleServices::isFullyQualifiedJavaClassName).collect(Collectors.toSet());
			} else {
				this.consumedServices = Collections.emptySet();
			}
		}

		private Map<String, List<String>> getProvidedServices(Bundle bundle, BundleWiring wiring) {
			if (hasServiceProviderRequirement(wiring)) {
				Map<String, List<String>> provided = new HashMap<>();
				// TODO: how to treat fragments. They should be considered as part of the host?
				// If lists are merged, handle fragment removal. Or merge on demand to simplify
				// tracking?
				Enumeration<String> serviceProviderEntries = bundle.getEntryPaths(SERVICE_NAME_PREFIX);
				while (serviceProviderEntries.hasMoreElements()) {
					String path = serviceProviderEntries.nextElement();
					int lastSeparator = path.lastIndexOf('/');
					if (lastSeparator + 1 != SERVICE_NAME_PREFIX.length()) {
						continue;
					}
					String service = path.substring(lastSeparator + 1);
					URL entry = bundle.getEntry(path);
					try (InputStream is = entry.openStream();
							BufferedReader reader = new BufferedReader(new InputStreamReader(is));) {
						for (String providerName; (providerName = reader.readLine()) != null;) {
							String classname = getServiceImplementorName(providerName);
							if (!classname.isEmpty()) {
								provided.computeIfAbsent(service, n -> new ArrayList<>()).add(classname);
							}
						}
					} catch (IOException e) { // TODO: ignore, log?
					}
				}
				return provided;
			}
			return Collections.emptyMap();
		}

		private String getServiceImplementorName(String providerName) {
			int commentIndex = providerName.indexOf('#');
			if (commentIndex >= 0) {
				providerName = providerName.substring(0, commentIndex);
			}
			return providerName.trim();
		}

		static boolean isFullyQualifiedJavaClassName(String service) {
			return Character.isJavaIdentifierStart(service.codePointAt(0)) && IntStream.range(1, service.length())
					.allMatch(i -> Character.isJavaIdentifierPart(service.codePointAt(i)) || service.charAt(i) == '.');
		}
	}

	private static class ServiceBundleTracker extends BundleTracker<BundleServices> {
		private static final int SERVICES_ACTIVE_STATES = Bundle.RESOLVED | Bundle.STARTING
				| Bundle.START_ACTIVATION_POLICY | Bundle.ACTIVE | Bundle.STOPPING | Bundle.START_TRANSIENT
				| Bundle.STOP_TRANSIENT;

		final Map<String, String> allProvidedServices = new ConcurrentHashMap<>();

		public ServiceBundleTracker(BundleContext context) {
			super(context, SERVICES_ACTIVE_STATES, null);
		}

		@Override
		public BundleServices addingBundle(Bundle bundle, BundleEvent event) {
			BundleWiring wiring = bundle.adapt(BundleWiring.class);
			if (wiring != null) {
				BundleServices services = new BundleServices(bundle, wiring);
				if (!services.consumedServices.isEmpty() || !services.providedServices.isEmpty()) {
					services.providedServices.forEach((service, providers) -> {
						for (String serviceClassname : providers) {
							if (allProvidedServices.putIfAbsent(serviceClassname, service) != null) {
								// TODO: or handle this? Could happen with multiple versions of a bundle
								// installed
								throw new IllegalStateException("Service provider class available more than once:" //$NON-NLS-1$
										+ serviceClassname);
							}
						}
					});
					return services;
				}
			}
			return null; // Not interesting, don't track
		}

		@Override
		public void removedBundle(Bundle bundle, BundleEvent event, BundleServices services) {
			for (List<String> providers : services.providedServices.values()) {
				for (String classname : providers) {
					allProvidedServices.remove(classname);
				}
			}
		}

		boolean consumesService(Bundle bundle, String serviceName) {
			BundleServices bundleServices = getObject(bundle);
			return bundleServices != null && bundleServices.consumedServices.contains(serviceName);
		}
	}

	private volatile ServiceBundleTracker serviceBundleTracker;

	@Override
	public ModuleClassLoader createClassLoader(ClassLoader parent, EquinoxConfiguration configuration,
			BundleLoader delegate, Generation generation) {
		if (serviceBundleTracker == null) {
			synchronized (this) {
				if (serviceBundleTracker == null) {
					Bundle aBundle = delegate.getWiring().getBundle();
					Bundle systemBundle = aBundle.getBundleContext().getBundle(Constants.SYSTEM_BUNDLE_ID);
					ServiceBundleTracker tracker = new ServiceBundleTracker(systemBundle.getBundleContext());
					// TODO: what is suitable if the framework is restarted. Is this called again?
					systemBundle.getBundleContext().addBundleListener(event -> {
						if (event.getType() == Framework.STOPPING) {
							tracker.close();
						}
					});
					systemBundle.getBundleContext().addFrameworkListener(event -> {
						if (event.getType() == FrameworkEvent.STOPPED) {
							tracker.close();
						}
					});
					tracker.open();
					serviceBundleTracker = tracker;
				}
			}
		}
		return null;
	}

	@Override
	public Enumeration<URL> preFindResources(String name, ModuleClassLoader classLoader) throws FileNotFoundException {
		if (name.startsWith(SERVICE_NAME_PREFIX)) {
			String serviceName = name.substring(SERVICE_NAME_PREFIX.length());
			Bundle bundle = classLoader.getBundle();
			if (serviceBundleTracker.consumesService(bundle, serviceName)
					&& walkCallerClasses(s -> s.anyMatch(c -> c == SERVICE_LOADER_GET_RESOURCES_CALLER))
					.booleanValue()) {
				List<URL> services = searchProviderClassLoaders(serviceName, bundle, classLoader).flatMap(cl -> {
					try {
						Enumeration<URL> resources = cl.getResources(name);
						return Collections.list(resources).stream();
					} catch (IOException e) {
						return Stream.empty();
					}
				}).collect(Collectors.toList());
				return Collections.enumeration(services);
			}
		}
		return null;
	}

	@Override
	public Class<?> preFindClass(String name, ModuleClassLoader classLoader) throws ClassNotFoundException {
		String serviceName = serviceBundleTracker.allProvidedServices.get(name);
		if (serviceName != null) {
			Bundle bundle = classLoader.getBundle(); // TODO: get the services from this, when being called

			if (serviceBundleTracker.consumesService(bundle, serviceName)
					&& walkCallerClasses(s -> s.anyMatch(c -> c == SERVICE_LOADER_LOAD_CLASS_CALLERS)).booleanValue()) {
				// TODO: index and parse the found service files per bundle and only continue
				// here if the class to load is a registered service of the bundle.
				// IF possible use the ServiceLoader class itself to parse those files.
				// See LazyClassPath LazyClassPathLookupIterator.parseLine():
				// All non empty line and everything behind a # is a comment and ignored.
				// No need to check for the correct syntax. The ServiceLoader will do the
				// failure for us.
				serviceBundleTracker.getObject(bundle);
				return searchProviderClassLoaders(serviceName, bundle, classLoader).map(cl -> {
					try {
						return cl.loadClass(name);
					} catch (ClassNotFoundException | NoClassDefFoundError e) { // ignore
						return null;
					}
				}).filter(Objects::nonNull).findFirst().orElse(null);
			}
		}
		return null;
	}

	private Stream<ClassLoader> searchProviderClassLoaders(String serviceName, Bundle bundle,
			ModuleClassLoader classLoader) {
		BundleWiring wiring = bundle.adapt(BundleWiring.class);
		return Stream.concat(Stream.of(classLoader), providerClassLoaders(serviceName, wiring));
	}

	private static boolean isCalledFromServiceLoader() {
		Optional<Class<?>> caller = walkCallerClasses(s -> s
				.filter(c -> c == ServiceLoader.class || c.getEnclosingClass() == ServiceLoader.class).findFirst());
		// TODO: If Java-9+ is required one day, just use the following code
		// ClassLoader thisLoader = ServiceLoaderMediatorHook.class.getClassLoader();
		// Optional<Class<?>> caller = WALKER.walk(frames -> frames.<Class<?>>map(f ->
		// f.getDeclaringClass())
		// .dropWhile(c -> c.getName().startsWith("org.eclipse.osgi") &&
		// c.getClassLoader() == thisLoader) //$NON-NLS-1$
		// .findFirst());
		return caller.isPresent() && caller.get().getEnclosingClass() == ServiceLoader.class;
	}

	static boolean hasServiceProviderRequirement(BundleWiring wiring) {
		return hasOSGiExtenderRequirement(wiring, "(osgi.extender=osgi.serviceloader.registrar)"); //$NON-NLS-1$
	}

	static boolean hasServiceConsumerRequirement(BundleWiring wiring) {
		return hasOSGiExtenderRequirement(wiring, "(osgi.extender=osgi.serviceloader.processor)"); //$NON-NLS-1$
	}

	private static boolean hasOSGiExtenderRequirement(BundleWiring wiring, String filter) {
		List<BundleWire> requiredWires = wiring.getRequiredWires("osgi.extender"); //$NON-NLS-1$
		if (requiredWires.isEmpty()) {
			return false;
		}
		return requiredWires.stream()
				.anyMatch(w -> w.getRequirement().getDirectives().getOrDefault("filter", "").contains(filter) //$NON-NLS-1$ //$NON-NLS-2$
						&& w.getProvider().getBundle().getBundleId() == Constants.SYSTEM_BUNDLE_ID);
	}

	private static Stream<ClassLoader> providerClassLoaders(String serviceName, BundleWiring wiring) {
		List<BundleWire> requiredWires = wiring.getRequiredWires(OSGI_SERVICELOADER_NAMESPACE);
		if (!requiredWires.isEmpty()) {
			return requiredWires.stream().filter(wire -> {
				Object serviceLoaderAttribute = wire.getCapability().getAttributes().get(OSGI_SERVICELOADER_NAMESPACE);
				return serviceName.equals(serviceLoaderAttribute);
			}).map(wire -> wire.getProviderWiring().getClassLoader());

		}
		return Stream.empty();
	}

}
