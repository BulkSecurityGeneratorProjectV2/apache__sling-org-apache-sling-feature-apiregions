/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.feature.apiregions.impl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;

public class Activator implements BundleActivator, FrameworkListener {
    static final String MANAGED_SERVICE_PKG_NAME = "org.osgi.service.cm";
    static final String MANAGED_SERVICE_CLASS_NAME = MANAGED_SERVICE_PKG_NAME + ".ManagedService";
    static final String MANAGED_SERVICE_FACTORY_CLASS_NAME = MANAGED_SERVICE_PKG_NAME + ".ManagedServiceFactory";
    static final String FACTORY_PID = "org.apache.sling.feature.apiregions.factory";

    static final String REGIONS_PROPERTY_NAME = "org.apache.sling.feature.apiregions.regions";

    static final Logger LOG = Logger.getLogger(ResolverHookImpl.class.getName());

    BundleContext bundleContext;
    ServiceRegistration<ResolverHookFactory> hookRegistration;

    RegionConfiguration configuration;

    @Override
    public synchronized void start(BundleContext context) throws Exception {
        bundleContext = context;

        createConfiguration();

        registerHook();

        context.addFrameworkListener(this);
    }

    @Override
    public synchronized void stop(BundleContext context) throws Exception {
        // All services automatically get unregistered by the framework.
    }

    private void createConfiguration() {
        try {
            this.configuration = new RegionConfiguration(bundleContext);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Problem activating API Regions runtime enforcement component", e);
        }
    }

    synchronized void registerHook() {
        if (hookRegistration != null)
            return; // There is already a hook, no need to re-register

        if (bundleContext.getProperty(REGIONS_PROPERTY_NAME) == null) {
            LOG.log(Level.WARNING, "API Regions not enabled. To enable set framework property: " + REGIONS_PROPERTY_NAME);
            return; // Component not enabled
        }

        RegionEnforcer enforcer = new RegionEnforcer(this.configuration);
        hookRegistration = bundleContext.registerService(ResolverHookFactory.class, enforcer, this.configuration.getRegistrationProperties());
    }

    synchronized void unregisterHook() {
        if (hookRegistration != null) {
            hookRegistration.unregister();
            hookRegistration = null;
        }
    }

    @Override
    public void frameworkEvent(FrameworkEvent event) {
        if (event.getType() == FrameworkEvent.STARTED) {
            bundleContext.removeFrameworkListener(this);

            FrameworkWiring fw = bundleContext.getBundle().adapt(FrameworkWiring.class);
            if (fw == null) {
                LOG.log(Level.WARNING, "The API Regions runtime fragment is not attached to the system bundle.");
                return;
            }

            Requirement cmReq = createPackageRequirement();

            // Reflectively register a Configuration Admin ManagedService, if the Config Admin API is available.
            // Because this fragment is a framework extension, we need to use the wiring API to find the CM API.
            Collection<BundleCapability> providers = fw.findProviders(cmReq);
            for (BundleCapability cap : providers) {
                if ( registerManagedService(cap) && registerManagedServiceFactory(cap)) {
                    break;
                }
            }
            LOG.log(Level.INFO, "No Configuration Admin API available");
        }
    }

    private boolean registerManagedService(final BundleCapability cap) {
        try {
            ClassLoader loader = cap.getRevision().getWiring().getClassLoader();
            Class<?> msClass = loader.loadClass(MANAGED_SERVICE_CLASS_NAME);
            Object ms = Proxy.newProxyInstance(loader, new Class[] {msClass}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    Class<?> mdDecl = method.getDeclaringClass();
                    if (mdDecl.equals(Object.class)) {
                        switch (method.getName()) {
                            case "equals" :
                                return proxy == args[0];
                            case "hashCode" :
                                return System.identityHashCode(proxy);
                            case "toString" :
                                return "Proxy for " + msClass;
                            default :
                                throw new UnsupportedOperationException("Method " + method
                                    + " not supported on proxy for " + msClass);
                        }
                    }
                    if ("updated".equals(method.getName()) && args.length == 1) {
                        Object arg = args[0];
                        if (arg == null) {
                            registerHook();
                        } else if (arg instanceof Dictionary) {
                            Dictionary<?,?> props = (Dictionary<?,?>) args[0];
                            Object disabled = props.get("disable");
                            if ("true".equals(disabled)) {
                                unregisterHook();
                            } else {
                                registerHook();
                            }
                        }
                    }
                    return null;
                }
            });
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(Constants.SERVICE_PID, getClass().getPackage().getName());
            bundleContext.registerService(MANAGED_SERVICE_CLASS_NAME, ms, props);

            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Problem attempting to register ManagedService from " + cap, e);
        }
        return false;
    }

    private boolean registerManagedServiceFactory(final BundleCapability cap) {
        try {
            ClassLoader loader = cap.getRevision().getWiring().getClassLoader();
            Class<?> msClass = loader.loadClass(MANAGED_SERVICE_FACTORY_CLASS_NAME);
            Object msf = Proxy.newProxyInstance(loader, new Class[] {msClass}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    Class<?> mdDecl = method.getDeclaringClass();
                    if (mdDecl.equals(Object.class)) {
                        switch (method.getName()) {
                            case "equals" :
                                return proxy == args[0];
                            case "hashCode" :
                                return System.identityHashCode(proxy);
                            case "toString" :
                                return "Proxy for " + msClass;
                            default :
                                throw new UnsupportedOperationException("Method " + method
                                    + " not supported on proxy for " + msClass);
                        }
                    }
                    if ("updated".equals(method.getName()) && args.length == 2) {
                        final String pid = (String)args[0];
                        @SuppressWarnings("unchecked")
                        final Dictionary<String, Object> props = (Dictionary<String, Object>) args[1];
                        configuration.setConfig(pid, props);
                    } else if ("deleted".equals(method.getName()) && args.length == 1) {
                        final String pid = (String)args[0];
                        configuration.removeConfig(pid);
                    } else if ("getName".equals(method.getName()) && args.length == 0 ) {
                        return "Region Factory Configuration";
                    }
                    return null;
                }
            });
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(Constants.SERVICE_PID, FACTORY_PID);
            bundleContext.registerService(MANAGED_SERVICE_FACTORY_CLASS_NAME, msf, props);

            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Problem attempting to register ManagedServiceFactory from " + cap, e);
        }
        return false;
    }

    static Requirement createPackageRequirement() {
        Requirement cmReq = new Requirement() {
            @Override
            public String getNamespace() {
                return PackageNamespace.PACKAGE_NAMESPACE;
            }

            @Override
            public Map<String, String> getDirectives() {
                return Collections.singletonMap("filter",
                        "(" + PackageNamespace.PACKAGE_NAMESPACE + "=" + MANAGED_SERVICE_PKG_NAME + ")");
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Collections.emptyMap();
            }

            @Override
            public Resource getResource() {
                return null;
            }

        };
        return cmReq;
    }
}
