package org.italiangrid.voms.container;

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.italiangrid.utils.https.JettyRunThread;
import org.italiangrid.utils.https.SSLOptions;
import org.italiangrid.utils.https.ServerFactory;
import org.italiangrid.utils.https.impl.canl.CANLListener;
import org.italiangrid.voms.container.listeners.ServerListener;
import org.italiangrid.voms.status.VOMSStatusHandler;
import org.italiangrid.voms.util.CertificateValidatorBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import eu.emi.security.authn.x509.X509CertChainValidatorExt;

public class Container {

	public static final Logger log = LoggerFactory.getLogger(Container.class);

	private static final String TAGLIBS_JAR_NAME = "org.apache.taglibs.standard.glassfish";

	public static final String CONF_FILE_NAME = "voms-admin-server.properties";

	public static final String DEFAULT_WAR = "/usr/share/webapps/voms-admin.war";

	public static final String DEFAULT_TMP_PREFIX = "/var/tmp";
	public static final String DEFAULT_DEPLOY_DIR = "/usr/share/voms-admin/vo.d";

	private static final String ARG_WAR = "war";
	private static final String ARG_CONFDIR = "confdir";
	private static final String ARG_DEPLOYDIR = "deploydir";

	private Options cliOptions;
	private CommandLineParser parser = new GnuParser();

	private Properties serverConfiguration;

	private String war;
	private String confDir;
	private String deployDir;

	private String host;
	private String port;

	private String certFile;
	private String keyFile;
	private String trustDir;
	private long trustDirRefreshIntervalInMsec;

	private Server server;
	private DeploymentManager deploymentManager;
	private HandlerCollection handlers = new HandlerCollection();
	private ContextHandlerCollection contexts = new ContextHandlerCollection();

	/**
	 * Initializes the Jetty temp directory as the default directory created by
	 * Jetty confuses xwork which has a bug and doesn't find classes when the WAR
	 * is expanded in the tmp directory.
	 * 
	 * TODO: check if recent versions of xwork solve this.
	 */
	protected File getJettyTmpDirForVO(String vo) {

		String baseDirPath = String.format("%s/%s/%s", DEFAULT_TMP_PREFIX,
			"voms-webapp", vo).replaceAll("/+", "/");

		File basePath = new File(baseDirPath);

		if (!basePath.exists()) {
			basePath.mkdirs();
		}

		return basePath;
	}

	protected SSLOptions getSSLOptions() {

		SSLOptions options = new SSLOptions();

		options.setCertificateFile(certFile);
		options.setKeyFile(keyFile);
		options.setTrustStoreDirectory(trustDir);
		options.setTrustStoreRefreshIntervalInMsec(trustDirRefreshIntervalInMsec);

		return options;

	}

	protected void confDirSanityChecks() {

		File confDirFile = new File(confDir);

		if (!confDirFile.exists() || !confDirFile.isDirectory()) {

			throw new IllegalArgumentException("VOMS Admin configuration directory "
				+ "does not exists or is not a directory: "
				+ confDirFile.getAbsolutePath());
		}
	}

	protected List<String> getConfiguredVONames() {

		confDirSanityChecks();

		List<String> voNames = new ArrayList<String>();

		File confDirFile = new File(confDir);

		File[] voFiles = confDirFile.listFiles(new FileFilter() {

			@Override
			public boolean accept(File pathname) {

				return pathname.isDirectory();
			}
		});

		for (File f : voFiles) {
			voNames.add(f.getName());
		}

		return voNames;
	}

	protected void configureDeploymentManager() {

		contexts.setServer(server);

		deploymentManager = new DeploymentManager();
		VOMSAppProvider provider = new VOMSAppProvider(confDir, war, deployDir,
			host);

		deploymentManager.addAppProvider(provider);
		deploymentManager.setContexts(contexts);

	}

	protected void configureJettyServer() {

		SSLOptions options = getSSLOptions();

		CANLListener l = new CANLListener();

		X509CertChainValidatorExt validator = CertificateValidatorBuilder
			.buildCertificateValidator(options.getTrustStoreDirectory(), l, l,
				options.getTrustStoreRefreshIntervalInMsec());

		int maxConnections = Integer
			.parseInt(getConfigurationProperty(ConfigurationProperty.MAX_CONNECTIONS));

		int maxRequestQueueSize = Integer
			.parseInt(getConfigurationProperty(ConfigurationProperty.MAX_REQUEST_QUEUE_SIZE));

		server = ServerFactory.newServer(host, Integer.parseInt(port),
			getSSLOptions(), validator, maxConnections, maxRequestQueueSize);

		server.addLifeCycleListener(new ServerListener());

		configureDeploymentManager();
		VOMSStatusHandler statusHandler = new VOMSStatusHandler(deploymentManager);
		statusHandler.setHostname(host);

		// Setup handlers structure
		handlers.setHandlers(new Handler[] { statusHandler, contexts,
			new DefaultHandler() });

		server.setHandler(handlers);

		server.setDumpAfterStart(false);
		server.setDumpBeforeStop(false);
		server.setStopAtShutdown(true);

		server.addBean(deploymentManager);

	}

	private void start() {

		JettyRunThread vomsService = new JettyRunThread(server);
		vomsService.start();
	}

	private void initOptions() {

		cliOptions = new Options();

		cliOptions.addOption(ARG_WAR, true, "The WAR used to start this server.");

		cliOptions.addOption(ARG_DEPLOYDIR, true,
			"The VOMS Admin deploy directory.");

		cliOptions.addOption(ARG_CONFDIR, true,
			"The configuration directory where " + "VOMS configuration is stored.");

	}

	private void failAndExit(String errorMessage, Throwable t) {

		if (t != null) {

			System.err.format("%s: %s\n", errorMessage, t.getMessage());

		} else {

			System.err.println(errorMessage);

		}

		System.exit(1);

	}

	private void parseCommandLineOptions(String[] args) {

		try {

			CommandLine cmdLine = parser.parse(cliOptions, args);

			Properties sysconfigProperties = SysconfigUtil.loadSysconfig();
			String installationPrefix = SysconfigUtil.getInstallationPrefix();

			String defaultPrefixedWarPath = String.format("%s/%s",
				installationPrefix, DEFAULT_WAR).replaceAll("/+", "/");

			war = cmdLine.getOptionValue(ARG_WAR, defaultPrefixedWarPath);

			confDir = cmdLine.getOptionValue(ARG_CONFDIR);

			if (confDir == null)
				confDir = sysconfigProperties
					.getProperty(SysconfigUtil.SYSCONFIG_CONF_DIR);

		} catch (ParseException e) {

			failAndExit("Error parsing command line arguments", e);

		}
	}

	private String getConfigurationProperty(ConfigurationProperty prop) {

		return serverConfiguration.getProperty(prop.getPropertyName(),
			prop.getDefaultValue());
	}

	private void loadServerConfiguration(String configurationDir) {

		try {
			serverConfiguration = new Properties();

			String configurationPath = String.format("%s/%s", configurationDir,
				CONF_FILE_NAME).replaceAll("/+", "/");

			serverConfiguration.load(new FileReader(configurationPath));

		} catch (IOException e) {
			throw new RuntimeException("Error loading configuration:"
				+ e.getMessage(), e);
		}

	}

	private void loadConfiguration() {

		confDir = SysconfigUtil.getConfDir();

		loadServerConfiguration(confDir);

		host = getConfigurationProperty(ConfigurationProperty.HOST);

		port = getConfigurationProperty(ConfigurationProperty.PORT);

		certFile = getConfigurationProperty(ConfigurationProperty.CERT);
		keyFile = getConfigurationProperty(ConfigurationProperty.KEY);

		trustDir = getConfigurationProperty(ConfigurationProperty.TRUST_ANCHORS_DIR);

		long refreshIntervalInSeconds = Long
			.parseLong(getConfigurationProperty(ConfigurationProperty.TRUST_ANCHORS_REFRESH_PERIOD));

		trustDirRefreshIntervalInMsec = TimeUnit.SECONDS
			.toMillis(refreshIntervalInSeconds);

		deployDir = String.format("%s/%s", SysconfigUtil.getInstallationPrefix(),
			DEFAULT_DEPLOY_DIR).replaceAll("/+", "/");

	}

	// Without this trick JSP page rendering on VOMS admin does not work
	private void forceTaglibsLoading() {

		try {

			String classpath = java.lang.System.getProperty("java.class.path");
			String entries[] = classpath.split(System.getProperty("path.separator"));

			if (entries.length == 1) {

				JarFile f = new JarFile(entries[0]);
				Attributes attrs = f.getManifest().getMainAttributes();
				Name n = new Name("Class-Path");
				String jarClasspath = attrs.getValue(n);
				String jarEntries[] = jarClasspath.split(" ");

				boolean taglibsFound = false;

				for (String e : jarEntries) {
					if (e.contains(TAGLIBS_JAR_NAME)) {
						taglibsFound = true;
						ClassLoader currentClassLoader = Thread.currentThread()
							.getContextClassLoader();

						File taglibsJar = new File(e);
						URLClassLoader newClassLoader = new URLClassLoader(
							new URL[] { taglibsJar.toURI().toURL() }, currentClassLoader);

						Thread.currentThread().setContextClassLoader(newClassLoader);
					}
				}
				if (!taglibsFound) {
					throw new RuntimeException("Error configuring taglibs classloading!");
				}

			}
		} catch (IOException e) {
			log.error(e.getMessage(), e);
			System.exit(1);
		}
	}

	public Container(String[] args) {

		// Leave this here and first
		forceTaglibsLoading();

		try {

			initOptions();
			parseCommandLineOptions(args);
			configureLogging();

		} catch (Throwable t) {
			// Here we print the error to standard error as the logging setup
			// could be incomplete
			System.err.println("Error starting voms-admin server: " + t.getMessage());
			t.printStackTrace(System.err);
			System.exit(1);
		}

		try {

			loadConfiguration();
			logStartupConfiguration();
			configureJettyServer();
			start();
		} catch (Throwable t) {
			log.error("Error starting voms-admin server: " + t.getMessage(), t);
			System.exit(1);
		}
	}

	private void logStartupConfiguration() {

		log.info("VOMS Admin version {}.", Version.version());
		log.info("Binding on: {}:{}", host, port);
		log.info("Service credentials: {}, {}", certFile, keyFile);
		log.info("Trust anchors directory: {}", trustDir);
		log.info("Trust anchors directory refresh interval (in minutes): {}",
			TimeUnit.MILLISECONDS.toMinutes(trustDirRefreshIntervalInMsec));
		log.info("Web archive location: {}", war);
		log.info("Configuration dir: {}", confDir);
		log.info("Deployment dir: {}", deployDir);

		log.info("Max concurrent connections: {}",
			getConfigurationProperty(ConfigurationProperty.MAX_CONNECTIONS));

		log.info("Max request queue size: {}",
			getConfigurationProperty(ConfigurationProperty.MAX_REQUEST_QUEUE_SIZE));

	}

	private void configureLogging() {

		String loggingConf = String.format("%s/%s", confDir,
			"voms-admin-server.logback").replaceAll("/+", "/");

		File f = new File(loggingConf);

		if (!f.exists() || !f.canRead()) {
			log.error("Error loading logging configuration: "
				+ "{} does not exist or is not readable.");
			return;
		}

		LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
		JoranConfigurator configurator = new JoranConfigurator();

		configurator.setContext(lc);
		lc.reset();

		try {
			configurator.doConfigure(loggingConf);

		} catch (JoranException e) {

			failAndExit("Error setting up the logging system", e);

		}
	}

	public static void main(String[] args) {

		new Container(args);
	}

}
