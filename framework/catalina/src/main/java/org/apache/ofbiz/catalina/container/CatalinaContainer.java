/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.catalina.container;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Valve;
import org.apache.catalina.authenticator.SingleSignOn;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.filters.RequestDumperFilter;
import org.apache.catalina.ha.ClusterManager;
import org.apache.catalina.ha.tcp.ReplicationValve;
import org.apache.catalina.ha.tcp.SimpleTcpCluster;
import org.apache.catalina.startup.ContextConfig;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.tribes.group.GroupChannel;
import org.apache.catalina.tribes.membership.McastService;
import org.apache.catalina.tribes.transport.MultiPointSender;
import org.apache.catalina.tribes.transport.ReplicationTransmitter;
import org.apache.catalina.tribes.transport.nio.NioReceiver;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.valves.AccessLogValve;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.ofbiz.base.component.ComponentConfig;
import org.apache.ofbiz.base.concurrent.ExecutionPool;
import org.apache.ofbiz.base.container.Container;
import org.apache.ofbiz.base.container.ContainerConfig;
import org.apache.ofbiz.base.container.ContainerConfig.Configuration;
import org.apache.ofbiz.base.container.ContainerException;
import org.apache.ofbiz.base.start.Start;
import org.apache.ofbiz.base.start.StartupCommand;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.webapp.WebAppUtil;
import org.apache.tomcat.JarScanner;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.apache.tomcat.util.scan.StandardJarScanner;
import org.xml.sax.SAXException;

/**
 * CatalinaContainer -  Tomcat
 *
 * For more information about the AccessLogValve pattern visit the
 * <a href="https://tomcat.apache.org/tomcat-9.0-doc/config/valve.html#Access_Log_Valve">Documentation</a>
 */
public class CatalinaContainer implements Container {

    private static final String MODULE = CatalinaContainer.class.getName();

    private String name;
    private Tomcat tomcat;

    @Override
    public void init(List<StartupCommand> ofbizCommands, String name, String configFile) throws ContainerException {

        this.name = name;
        ContainerConfig.Configuration configuration = ContainerConfig.getConfiguration(name);
        Configuration.Property engineConfig = retrieveTomcatEngineConfig(configuration);

        // tomcat setup
        tomcat = prepareTomcatServer(configuration, engineConfig);
        Engine engine = prepareTomcatEngine(tomcat, engineConfig);
        Host host = prepareHost(tomcat, null);

        // add realm and valve for Tomcat SSO
        if (EntityUtilProperties.propertyValueEquals("security", "security.login.tomcat.sso", "true")) {
            boolean useEncryption = EntityUtilProperties.propertyValueEquals("security", "password.encrypt", "true");
            OFBizRealm ofBizRealm = new OFBizRealm();
            if (useEncryption) {
                ofBizRealm.setCredentialHandler(new HashedCredentialHandler());
            } else {
                ofBizRealm.setCredentialHandler(new SimpleCredentialHandler());
            }
            host.setRealm(ofBizRealm);
            // CHECKSTYLE_OFF: ALMOST_ALL
            ((StandardHost) host).addValve(new SingleSignOn());
            // CHECKSTYLE_ON: ALMOST_ALL
        }

        // clustering, valves and connectors setup
        Configuration.Property clusterProps = prepareTomcatClustering(host, engineConfig);
        prepareTomcatEngineValves(engineConfig).forEach(valve -> ((StandardEngine) engine).addValve(valve));
        prepareTomcatConnectors(configuration).forEach(connector -> tomcat.getService().addConnector(connector));

        loadWebapps(tomcat, configuration, clusterProps);
    }

    @Override
    public boolean start() throws ContainerException {
        try {
            tomcat.start();
        } catch (LifecycleException e) {
            throw new ContainerException(e);
        }

        for (Connector con: tomcat.getService().findConnectors()) {
            Debug.logInfo("Connector " + con.getProtocol() + " @ " + con.getPort() + " - "
                    + (con.getSecure() ? "secure" : "not-secure") + " [" + con.getProtocolHandlerClassName() + "] started.", MODULE);
        }
        Debug.logInfo("Started " + ServerInfo.getServerInfo(), MODULE);
        return true;
    }

    @Override
    public void stop() {
        try {
            tomcat.stop();
        } catch (LifecycleException e) {
            /* Don't re-throw this exception or it will kill the rest of the shutdown process.
             * Happens usually when running tests. Output disabled unless in verbose */
            Debug.logVerbose(e, MODULE);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    private static Configuration.Property retrieveTomcatEngineConfig(Configuration cc) throws ContainerException {
        List<Configuration.Property> engineProps = cc.getPropertiesWithValue("engine");
        if (UtilValidate.isEmpty(engineProps)) {
            throw new ContainerException("Cannot load CatalinaContainer; no engines defined.");
        }
        if (engineProps.size() > 1) {
            throw new ContainerException("Cannot load CatalinaContainer; more than one engine configuration found; only one is supported.");
        }
        return engineProps.get(0);
    }

    private static Tomcat prepareTomcatServer(ContainerConfig.Configuration cc, Configuration.Property engineConfig)
            throws ContainerException {
        System.setProperty(Globals.CATALINA_HOME_PROP, System.getProperty("ofbiz.home") + "/"
                + ContainerConfig.getPropertyValue(cc, "catalina-runtime-home", "runtime/catalina"));
        System.setProperty(Globals.CATALINA_BASE_PROP, System.getProperty(Globals.CATALINA_HOME_PROP));

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(System.getProperty("ofbiz.home"));

        Configuration.Property defaultHostProp = engineConfig.getProperty("default-host");
        if (defaultHostProp == null) {
            throw new ContainerException("default-host element of server property is required for catalina!");
        }
        tomcat.setHostname(defaultHostProp.value());

        if (ContainerConfig.getPropertyValue(cc, "use-naming", false)) {
            tomcat.enableNaming();
        }

        StandardServer server = (StandardServer) tomcat.getServer();
        try {
            server.setGlobalNamingContext(new InitialContext());
        } catch (NamingException e) {
            throw new ContainerException(e);
        }

        return tomcat;
    }

    private static Engine prepareTomcatEngine(Tomcat tomcat, Configuration.Property engineConfig) {
        Engine engine = tomcat.getEngine();
        engine.setName(engineConfig.name());

        // set the JVM Route property (JK/JK2)
        String jvmRoute = ContainerConfig.getPropertyValue(engineConfig, "jvm-route", null);
        if (jvmRoute != null) {
            engine.setJvmRoute(jvmRoute);
        }

        return engine;
    }

    private static Host prepareHost(Tomcat tomcat, List<String> virtualHosts) {
        Host host;

        if (UtilValidate.isEmpty(virtualHosts)) {
            host = (Host) tomcat.getEngine().findChild(tomcat.getEngine().getDefaultHost());
            if (host == null) {
                host = tomcat.getHost();
            }
        } else {
            host = prepareVirtualHost(tomcat, virtualHosts);
        }

        host.setAppBase(System.getProperty("ofbiz.home") + "/framework/catalina/hosts");
        host.setDeployOnStartup(false);
        host.setBackgroundProcessorDelay(5);
        host.setAutoDeploy(false);
        // CHECKSTYLE_OFF: ALMOST_ALL
        ((StandardHost) host).setWorkDir(new File(System.getProperty(Globals.CATALINA_HOME_PROP),
                "work" + File.separator + host.getName()).getAbsolutePath());
        // CHECKSTYLE_ON: ALMOST_ALL

        return host;
    }

    private static Host prepareVirtualHost(Tomcat tomcat, List<String> virtualHosts) {
        // assume that the first virtual-host will be the default; additional virtual-hosts will be aliases
        String hostName = virtualHosts.get(0);
        Host host;
        Engine engine = tomcat.getEngine();

        org.apache.catalina.Container childContainer = engine.findChild(hostName);
        if (childContainer instanceof Host) {
            host = (Host) childContainer;
        } else {
            host = new StandardHost();
            host.setName(hostName);
            engine.addChild(host);
        }

        virtualHosts.stream()
            .filter(virtualHost -> virtualHost != hostName).forEach(virtualHost -> host.addAlias(virtualHost));

        return host;
    }

    private static Configuration.Property prepareTomcatClustering(Host host, Configuration.Property engineConfig)
            throws ContainerException {
        Configuration.Property clusterProp = null;

        List<Configuration.Property> clusterProps = engineConfig.getPropertiesWithValue("cluster");
        if (clusterProps.size() > 1) {
            throw new ContainerException("Only one cluster configuration allowed per engine");
        }

        if (UtilValidate.isNotEmpty(clusterProps)) {
            clusterProp = clusterProps.get(0);

            GroupChannel channel = new GroupChannel();
            channel.setChannelReceiver(prepareChannelReceiver(clusterProp));
            channel.setChannelSender(prepareChannelSender(clusterProp));
            channel.setMembershipService(prepareChannelMcastService(clusterProp));

            SimpleTcpCluster cluster = new SimpleTcpCluster();
            cluster.setClusterName(clusterProp.name());
            cluster.setManagerTemplate(prepareClusterManager(clusterProp));
            cluster.setChannel(channel);
            cluster.addValve(prepareClusterValve(clusterProp));

            host.setCluster(cluster);

            Debug.logInfo("Catalina Cluster [" + cluster.getClusterName() + "] configured for host - " + host.getName(), MODULE);
        }
        return clusterProp;
    }

    private static NioReceiver prepareChannelReceiver(Configuration.Property clusterProp) throws ContainerException {
        NioReceiver listener = new NioReceiver();

        String tla = ContainerConfig.getPropertyValue(clusterProp, "tcp-listen-host", "auto");
        int tlp = ContainerConfig.getPropertyValue(clusterProp, "tcp-listen-port", 4001);
        int tlt = ContainerConfig.getPropertyValue(clusterProp, "tcp-sector-timeout", 100);
        int tlc = ContainerConfig.getPropertyValue(clusterProp, "tcp-thread-count", 6);

        if (tlp == -1) {
            throw new ContainerException("Cluster configuration requires tcp-listen-port property");
        }

        listener.setAddress(tla);
        listener.setPort(tlp);
        listener.setSelectorTimeout(tlt);
        listener.setMaxThreads(tlc);
        listener.setMinThreads(tlc);

        return listener;
    }

    private static ReplicationTransmitter prepareChannelSender(Configuration.Property clusterProp)
            throws ContainerException {
        ReplicationTransmitter trans = new ReplicationTransmitter();
        try {
            MultiPointSender mps = (MultiPointSender) Class.forName(ContainerConfig.getPropertyValue(clusterProp,
                    "replication-mode", "org.apache.catalina.tribes.transport.bio.PooledMultiSender")).getDeclaredConstructor().newInstance();
            trans.setTransport(mps);
        } catch (Exception exc) {
            throw new ContainerException("Cluster configuration requires a valid replication-mode property: " + exc.getMessage());
        }
        return trans;
    }

    private static McastService prepareChannelMcastService(Configuration.Property clusterProp)
            throws ContainerException {
        McastService mcast = new McastService();

        String mcb = ContainerConfig.getPropertyValue(clusterProp, "mcast-bind-addr", null);
        String mca = ContainerConfig.getPropertyValue(clusterProp, "mcast-addr", null);
        int mcp = ContainerConfig.getPropertyValue(clusterProp, "mcast-port", -1);
        int mcf = ContainerConfig.getPropertyValue(clusterProp, "mcast-freq", 500);
        int mcd = ContainerConfig.getPropertyValue(clusterProp, "mcast-drop-time", 3000);

        if (mca == null || mcp == -1) {
            throw new ContainerException("Cluster configuration requires mcast-addr and mcast-port properties");
        }

        if (mcb != null) {
            mcast.setMcastBindAddress(mcb);
        }

        mcast.setAddress(mca);
        mcast.setPort(mcp);
        mcast.setMcastDropTime(mcd);
        mcast.setFrequency(mcf);

        return mcast;
    }

    private static ClusterManager prepareClusterManager(Configuration.Property clusterProp) throws ContainerException {
        String mgrClassName = ContainerConfig.getPropertyValue(clusterProp, "manager-class", "org.apache.catalina.ha.session.DeltaManager");
        try {
            return (ClusterManager) Class.forName(mgrClassName).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ContainerException("Cluster configuration requires a valid manager-class property", e);
        }
    }

    private static ReplicationValve prepareClusterValve(Configuration.Property clusterProp) {
        ReplicationValve clusterValve = new ReplicationValve();
        String defaultValveFilter = ".*\\.gif;.*\\.js;.*\\.jpg;.*\\.htm;.*\\.html;.*\\.txt;.*\\.png;.*\\.css;.*\\.ico;.*\\.htc;";
        clusterValve.setFilter(ContainerConfig.getPropertyValue(clusterProp, "rep-valve-filter", defaultValveFilter));
        return clusterValve;
    }

    private static List<Valve> prepareTomcatEngineValves(Configuration.Property engineConfig)
            throws ContainerException {
        List<Valve> engineValves = new ArrayList<>();

        // configure the CrossSubdomainSessionValve
        if (ContainerConfig.getPropertyValue(engineConfig, "enable-cross-subdomain-sessions", false)) {
            engineValves.add(new CrossSubdomainSessionValve());
        }

        // configure the SslAcceleratorValve
        String sslAcceleratorPortStr = ContainerConfig.getPropertyValue(engineConfig, "ssl-accelerator-port", null);
        if (UtilValidate.isNotEmpty(sslAcceleratorPortStr)) {
            Integer sslAcceleratorPort = Integer.valueOf(sslAcceleratorPortStr);
            SslAcceleratorValve sslAcceleratorValve = new SslAcceleratorValve();
            sslAcceleratorValve.setSslAcceleratorPort(sslAcceleratorPort);
            engineValves.add(sslAcceleratorValve);
        }

        // configure the AccessLogValve
        String logDir = ContainerConfig.getPropertyValue(engineConfig, "access-log-dir", null);
        if (logDir != null) {
            AccessLogValve accessLogValve = new AccessLogValve();

            logDir = logDir.startsWith("/") ? System.getProperty("ofbiz.home") + "/" + logDir : logDir;
            File logFile = new File(logDir);
            if (!logFile.isDirectory()) {
                throw new ContainerException("Log directory [" + logDir + "] is not available; make sure the directory is created");
            }

            accessLogValve.setDirectory(logFile.getAbsolutePath());
            String accessLogPattern = ContainerConfig.getPropertyValue(engineConfig, "access-log-pattern", null);
            if (UtilValidate.isNotEmpty(accessLogPattern)) {
                accessLogValve.setPattern(accessLogPattern);
            }
            String accessLogPrefix = ContainerConfig.getPropertyValue(engineConfig, "access-log-prefix", null);
            if (UtilValidate.isNotEmpty(accessLogPrefix)) {
                accessLogValve.setPrefix(accessLogPrefix);
            }
            accessLogValve.setRotatable(ContainerConfig.getPropertyValue(engineConfig, "access-log-rotate", false));
            accessLogValve.setMaxDays(Integer.valueOf(ContainerConfig.getPropertyValue(engineConfig, "access-log-maxDays", null)));

            engineValves.add(accessLogValve);
        }

        return engineValves;
    }

    private static List<Connector> prepareTomcatConnectors(Configuration configuration) throws ContainerException {
        List<Configuration.Property> connectorProps = configuration.getPropertiesWithValue("connector");
        if (UtilValidate.isEmpty(connectorProps)) {
            throw new ContainerException("Cannot load CatalinaContainer; no connectors defined!");
        }
        return connectorProps.stream()
            .filter(connectorProp -> UtilValidate.isNotEmpty(connectorProp.properties()))
            .map(connectorProp -> prepareConnector(connectorProp))
            .collect(Collectors.toList());
    }

    private static Connector prepareConnector(Configuration.Property connectorProp) {
        Connector connector = new Connector(ContainerConfig.getPropertyValue(connectorProp, "protocol", "HTTP/1.1"));
        connector.setPort(ContainerConfig.getPropertyValue(connectorProp, "port", 0) + Start.getInstance().getConfig().getPortOffset());
        if ("true".equals(ContainerConfig.getPropertyValue(connectorProp, "upgradeProtocol", "false"))) {
            connector.addUpgradeProtocol(new Http2Protocol());
            Debug.logInfo("Tomcat " + connector + ": enabled HTTP/2", MODULE);
        }
        connectorProp.properties().values().stream()
                .filter(prop -> {
                    String name = prop.name();
                    String value = prop.value();
                    return !"protocol".equals(name) && !"upgradeProtocol".equals(name) && !"port".equals(name) && !"sslHostConfig".equals(value);
                })
                .forEach(prop -> {
                    String name = prop.name();
                    String value = prop.value();
                    if (IntrospectionUtils.setProperty(connector, name, value)) {
                        if (name.indexOf("Pass") != -1) {
                            // this property may be a password, do not include its value in the logs
                            Debug.logInfo("Tomcat " + connector + ": set " + name, MODULE);
                        } else {
                            Debug.logInfo("Tomcat " + connector + ": set " + name + "=" + value, MODULE);
                        }
                    } else {
                        Debug.logWarning("Tomcat " + connector + ": ignored parameter " + name, MODULE);
                    }
                });
        prepareSslHostConfigs(connector, connectorProp).forEach(connector::addSslHostConfig);
        return connector;
    }

    private static List<SSLHostConfig> prepareSslHostConfigs(Connector connector, Configuration.Property connectorProp) {
        return connectorProp.getPropertiesWithValue("sslHostConfig").stream()
                .filter(sslHostConfigProp -> UtilValidate.isNotEmpty(sslHostConfigProp.properties()))
                .map(sslHostConfigProp -> prepareSslHostConfig(connector, sslHostConfigProp))
                .collect(Collectors.toList());
    }

    private static SSLHostConfig prepareSslHostConfig(Connector connector, Configuration.Property sslHostConfigProp) {
        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfigProp.properties().values().stream()
                .filter(prop -> {
                    String value = prop.value();
                    return !"certificate".equals(value);
                })
                .forEach(prop -> {
                    String name = prop.name();
                    String value = prop.value();
                    if (IntrospectionUtils.setProperty(sslHostConfig, name, value)) {
                        if (name.indexOf("Pass") != -1) {
                            // this property may be a password, do not include its value in the logs
                            Debug.logInfo("Tomcat " + connector + " " + sslHostConfig.getHostName() + ": set " + name, MODULE);
                        } else {
                            Debug.logInfo("Tomcat " + connector + " " + sslHostConfig.getHostName() + ": set " + name + "=" + value, MODULE);
                        }
                    } else {
                        Debug.logWarning("Tomcat " + connector + " " + sslHostConfig.getHostName() + ": ignored parameter " + name, MODULE);
                    }
                });
        prepareSslHostConfigCerts(connector, sslHostConfig, sslHostConfigProp).forEach(sslHostConfig::addCertificate);
        return sslHostConfig;
    }

    private static List<SSLHostConfigCertificate> prepareSslHostConfigCerts(Connector connector, SSLHostConfig sslHostConfig,
                                                                            Configuration.Property sslHostConfigProp) {
        return sslHostConfigProp.getPropertiesWithValue("certificate").stream()
                .filter(certProp -> UtilValidate.isNotEmpty(certProp.properties()))
                .map(certProp -> prepareSslHostConfigCert(connector, sslHostConfig, certProp))
                .collect(Collectors.toList());
    }

    private static SSLHostConfigCertificate prepareSslHostConfigCert(Connector connector, SSLHostConfig sslHostConfig,
                                                                     Configuration.Property certProp) {
        String certificateType = ContainerConfig.getPropertyValue(certProp, "certificateType", SSLHostConfigCertificate.DEFAULT_TYPE.name());
        SSLHostConfigCertificate sslHostConfigCert = new SSLHostConfigCertificate(sslHostConfig,
                SSLHostConfigCertificate.Type.valueOf(certificateType));
        certProp.properties().values().stream()
                .filter(prop -> {
                    String name = prop.name();
                    return !"certificateType".equals(name);
                })
                .forEach(prop -> {
                    String name = prop.name();
                    String value = prop.value();
                    if (IntrospectionUtils.setProperty(sslHostConfigCert, name, value)) {
                        if (name.indexOf("Pass") != -1) {
                            // this property may be a password, do not include its value in the logs
                            Debug.logInfo("Tomcat " + connector + " " + sslHostConfig.getHostName() + " certificate: set " + name, MODULE);
                        } else {
                            Debug.logInfo("Tomcat " + connector + " " + sslHostConfig.getHostName() + " certificate: set " + name + "=" + value,
                                    MODULE);
                        }
                    } else {
                        Debug.logWarning("Tomcat " + connector + " " + sslHostConfig.getHostName() + " certificate: ignored parameter " + name,
                                MODULE);
                    }
                });
        return sslHostConfigCert;
    }

    private static void loadWebapps(Tomcat tomcat, Configuration configuration, Configuration.Property clusterProp) {
        ScheduledExecutorService executor = ExecutionPool.getScheduledExecutor(new ThreadGroup(MODULE),
                "catalina-startup", Runtime.getRuntime().availableProcessors(), 0, true);
        List<Future<Context>> futures = new ArrayList<>();

        List<ComponentConfig.WebappInfo> webResourceInfos = ComponentConfig.getAllWebappResourceInfos();
        Collections.reverse(webResourceInfos); // allow higher level webapps to override lower ones

        Set<String> webappsMounts = new HashSet<>();
        webResourceInfos.forEach(appInfo -> webappsMounts.addAll(getWebappMounts(appInfo)));

        for (ComponentConfig.WebappInfo appInfo: webResourceInfos) {
            if (webappsMounts.removeAll(getWebappMounts(appInfo))) {
                // webapp is not yet loaded
                if (!appInfo.getLocation().isEmpty()) {
                    futures.add(executor.submit(createCallableContext(tomcat, appInfo, clusterProp, configuration)));
                }
            } else {
                /* webapp is loaded already (overridden). Therefore, disable
                 * app bar display on overridden apps and do not load */
                appInfo.setAppBarDisplay(false);
                Debug.logInfo("Duplicate webapp mount (overridding); not loading : "
                        + appInfo.getName() + " / " + appInfo.location(), MODULE);
            }
        }
        ExecutionPool.getAllFutures(futures);
        executor.shutdown();
    }

    private static List<String> getWebappMounts(ComponentConfig.WebappInfo webappInfo) {
        List<String> allAppsMounts = new ArrayList<>();
        String engineName = webappInfo.getServer();
        String mount = webappInfo.getContextRoot();
        List<String> virtualHosts = webappInfo.getVirtualHosts();
        if (virtualHosts.isEmpty()) {
            allAppsMounts.add(engineName + ":DEFAULT:" + mount);
        } else {
            virtualHosts.forEach(virtualHost -> allAppsMounts.add(engineName + ":" + virtualHost + ":" + mount));
        }
        return allAppsMounts;
    }

    private static Callable<Context> createCallableContext(Tomcat tomcat, ComponentConfig.WebappInfo appInfo,
            Configuration.Property clusterProp, ContainerConfig.Configuration configuration) {

        Debug.logInfo("Creating context [" + appInfo.getName() + "]", MODULE);
        Host host = prepareHost(tomcat, appInfo.getVirtualHosts());

        return () -> {
            StandardContext context = prepareContext(host, configuration, appInfo, clusterProp);
            Debug.logInfo("host[" + host + "].addChild(" + context + ")", MODULE);
            host.addChild(context);
            return context;
        };
    }

    private static StandardContext prepareContext(Host host, ContainerConfig.Configuration configuration,
            ComponentConfig.WebappInfo appInfo, Configuration.Property clusterProp) throws ContainerException {

        StandardContext context = new StandardContext();
        context.setDefaultWebXml(System.getProperty("ofbiz.home") + "/framework/catalina/config/web.xml");
        Tomcat.initWebappDefaults(context);

        String location = getWebappRootLocation(appInfo);
        boolean contextIsDistributable = isContextDistributable(configuration, appInfo);

        context.setParent(host);
        context.setDocBase(location);
        context.setDisplayName(appInfo.getName());
        context.setPath(getWebappMountPoint(appInfo));
        context.addLifecycleListener(new ContextConfig());
        context.setJ2EEApplication("OFBiz");
        context.setJ2EEServer("OFBiz Container");
        context.setParentClassLoader(Thread.currentThread().getContextClassLoader());
        context.setDocBase(location);
        context.setReloadable(ContainerConfig.getPropertyValue(configuration, "apps-context-reloadable", false));
        context.setDistributable(contextIsDistributable);
        context.setCrossContext(ContainerConfig.getPropertyValue(configuration, "apps-cross-context", true));
        context.setPrivileged(appInfo.isPrivileged());
        context.getServletContext().setAttribute("_serverId", appInfo.getServer());
        context.getServletContext().setAttribute("componentName", appInfo.getComponentConfig().getComponentName());

        if (clusterProp != null && contextIsDistributable) {
            context.setManager(prepareClusterManager(clusterProp));
        }

        StandardRoot resources = new StandardRoot(context);
        resources.setAllowLinking(true);
        context.setResources(resources);

        JarScanner jarScanner = context.getJarScanner();
        if (jarScanner instanceof StandardJarScanner) {
            StandardJarScanner standardJarScanner = (StandardJarScanner) jarScanner;
            standardJarScanner.setJarScanFilter(new FilterJars());
            standardJarScanner.setScanClassPath(true);
        }

        Map<String, String> initParameters = appInfo.getInitParameters();
        // request dumper filter
        if ("true".equals(initParameters.get("enableRequestDump"))) {
            FilterDef requestDumperFilterDef = new FilterDef();
            requestDumperFilterDef.setFilterClass(RequestDumperFilter.class.getName());
            requestDumperFilterDef.setFilterName("RequestDumper");
            context.addFilterDef(requestDumperFilterDef);

            FilterMap requestDumperFilterMap = new FilterMap();
            requestDumperFilterMap.setFilterName("RequestDumper");
            requestDumperFilterMap.addURLPattern("*");
            context.addFilterMap(requestDumperFilterMap);
        }

        // set the init parameters
        initParameters.entrySet().forEach(entry -> context.addParameter(entry.getKey(), entry.getValue()));

        return context;
    }

    private static String getWebappRootLocation(ComponentConfig.WebappInfo appInfo) {
        return appInfo.getComponentConfig().rootLocation()
                .resolve(appInfo.getLocation().replace('\\', '/'))
                .normalize()
                .toString();
    }

    private static String getWebappMountPoint(ComponentConfig.WebappInfo appInfo) {
        String mount = appInfo.getMountPoint();
        if (mount.endsWith("/*")) {
            mount = mount.substring(0, mount.length() - 2);
        }
        return mount;
    }

    private static boolean isContextDistributable(ContainerConfig.Configuration configuration,
            ComponentConfig.WebappInfo appInfo) throws ContainerException {
        boolean appIsDistributable = ContainerConfig.getPropertyValue(configuration, "apps-distributable", true);
        try {
            boolean isDistributable = WebAppUtil.isDistributable(appInfo);
            return appIsDistributable && isDistributable;
        } catch (SAXException | IOException e) {
            throw new ContainerException(e);
        }
    }
}
