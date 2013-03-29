package org.italiangrid.voms.container;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.webapp.WebAppContext;
import org.italiangrid.voms.container.listeners.VOListener;
import org.italiangrid.voms.container.listeners.VOMSESListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VOMSAppProvider extends AbstractLifeCycle implements AppProvider {

	public static final String VOMSES_APP_KEY = "__vomses__";

	public static final String DEFAULT_TMP_PREFIX = "/var/tmp";
	public static final int DEFAULT_SCAN_INTERVAL_IN_SECONDS = 10;

	private static final Logger log = LoggerFactory
		.getLogger(VOMSAppProvider.class);

	private final String configurationDir;
	private final String warFile;
	private final String deploymentDir;
	private final String hostname;

	private int scanIntervalInSeconds = DEFAULT_SCAN_INTERVAL_IN_SECONDS;

	private Scanner scanner;

	private final Scanner.DiscreteListener scannerListener = new Scanner.DiscreteListener() {

		private String getBasename(String filename) {

			File f = new File(filename);
			return f.getName();
		}

		@Override
		public void fileRemoved(String filename) throws Exception {

			VOMSAppProvider.this.stopVO(getBasename(filename));
		}

		@Override
		public void fileChanged(String filename) throws Exception {

			// Do nothing

		}

		@Override
		public void fileAdded(String filename) throws Exception {

			VOMSAppProvider.this.startVO(getBasename(filename));
		}
	};

	private DeploymentManager deploymentManager;

	private Map<String, App> vomsApps = new HashMap<String, App>();

	public VOMSAppProvider(String confDir, String warFile, String deploymentDir,
		String hostname) {

		this.configurationDir = confDir;
		this.warFile = warFile;
		this.deploymentDir = deploymentDir;
		this.hostname = hostname;
	}

	protected void confDirSanityChecks() {

		File confDirFile = new File(configurationDir);

		if (!confDirFile.exists())
			throw new IllegalArgumentException("Configuration dir does not exist: "
				+ configurationDir);

		if (!confDirFile.isDirectory())
			throw new IllegalArgumentException("Configuration dir is not "
				+ "a directory: " + configurationDir);
	}

	protected List<String> getConfiguredVONames() {

		confDirSanityChecks();

		List<String> voNames = new ArrayList<String>();

		File confDirFile = new File(configurationDir);

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

	protected App createApp(String voName) {

		return new App(deploymentManager, this, voName);
	}

	private boolean voNameIsValid(String voName) {

		String voConfDir = String.format("%s/%s", configurationDir, voName)
			.replaceAll("/+", "/");

		File f = new File(voConfDir);

		return (f.exists() && f.isDirectory());
	}

	public void startVOMSES() {

		log.debug("Request to start VOMSES webapp");

		App a = createApp(VOMSES_APP_KEY);
		if (a != null) {

			vomsApps.put(VOMSES_APP_KEY, a);
			deploymentManager.addApp(a);
		}

	}

	public void startVO(String voName) {

		log.debug("Request to start vo {}", voName);

		if (!voNameIsValid(voName)) {
			log.error("VO {} is not configured on this host!");
			return;
		}
		App a = createApp(voName);
		if (a != null) {
			vomsApps.put(voName, a);
			deploymentManager.addApp(a);
		}

	}

	public void stopVO(String voName) {

		log.debug("Request to stop vo {}", voName);
		if (!voNameIsValid(voName)) {
			log.error("VO {} is not configured on this host!");
			return;
		}
		App a = vomsApps.remove(voName);
		if (a != null)
			deploymentManager.removeApp(a);
	}

	@Override
	public void setDeploymentManager(DeploymentManager deploymentManager) {

		this.deploymentManager = deploymentManager;

	}

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

	protected ContextHandler configureWebApp(String vo) {

		String contextPath = String.format("/voms/%s", vo);
		WebAppContext vomsWebappContext = new WebAppContext();
		vomsWebappContext.setContextPath(contextPath);
		vomsWebappContext.setTempDirectory(getJettyTmpDirForVO(vo));

		vomsWebappContext.setParentLoaderPriority(false);

		File webArchive = new File(warFile);

		if (webArchive.isDirectory()) {

			String webXMLPath = String.format("%s/WEB-INF/web.xml",
				webArchive.getAbsolutePath());

			vomsWebappContext.setDescriptor(webXMLPath);
			vomsWebappContext.setResourceBase(webArchive.getAbsolutePath());

		} else {
			vomsWebappContext.setWar(warFile);
		}

		// Consider logback and slf4j server classes
		vomsWebappContext.addServerClass("ch.qos.logback.");
		vomsWebappContext.addServerClass("org.slf4j.");

		vomsWebappContext.setInitParameter("VO_NAME", vo);
		vomsWebappContext.setInitParameter("CONF_DIR", configurationDir);

		vomsWebappContext.addLifeCycleListener(VOListener.INSTANCE);

		return vomsWebappContext;
	}

	protected ContextHandler configureVOMSES() {

		String webappResourceDir = this.getClass().getClassLoader()
			.getResource("status-webapp").toExternalForm();

		WebAppContext statusContext = new WebAppContext();

		statusContext.setContextPath("/");
		statusContext.setResourceBase(webappResourceDir);

		statusContext.setParentLoaderPriority(true);

		statusContext.setInitParameter("host", hostname);
		statusContext.setInitParameter("confdir", configurationDir);

		statusContext.setThrowUnavailableOnStartupException(true);
		statusContext.addLifeCycleListener(new VOMSESListener());

		return statusContext;
	}

	@Override
	public ContextHandler createContextHandler(App app) throws Exception {

		ContextHandler webApp = null;

		if (app != null) {

			if (app.getOriginId().equals(VOMSES_APP_KEY)) {
				webApp = configureVOMSES();
			} else {
				webApp = configureWebApp(app.getOriginId());
			}
		}

		return webApp;
	}

	public Map<String, App> getDeployedApps() {

		return vomsApps;
	}

	@Override
	protected void doStart() throws Exception {

		log.debug("Starting VOMS App provider.");
		File scanDir = new File(deploymentDir);

		if (!scanDir.exists() || !scanDir.isDirectory()) {
			throw new IllegalArgumentException("VOMS Admin deployment dir "
				+ "does not exist or is not a directory: " + scanDir.getAbsolutePath());
		}

		scanner = new Scanner();
		scanner.setScanDirs(Collections.singletonList(scanDir));
		scanner.setScanInterval(scanIntervalInSeconds);
		scanner.setRecursive(false);
		scanner.setReportDirs(false);
		scanner.addListener(scannerListener);

		startVOMSES();

		scanner.start();
	}

	@Override
	protected void doStop() throws Exception {

		log.debug("Stopping VOMS App provider.");
		if (scanner != null) {
			scanner.stop();
			scanner.removeListener(scannerListener);
			scanner = null;
		}
	}

}
