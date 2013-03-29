package org.italiangrid.voms.status;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.italiangrid.voms.container.Version;

public class VOMSStatusHandler extends HandlerWrapper {

	public static final String STATUS_MAP_KEY = "statusMap";
	public static final String VO_NAMES = "voNames";
	
	public static final String HOST_KEY = "host";
	public static final String PORT_KEY = "port";
	public static final String VERSION_KEY = "version";
	
	private DeploymentManager manager;	
	private String hostname;
	
	public VOMSStatusHandler(DeploymentManager mgr) {
		manager = mgr;
	}

	protected Map<String, Boolean> getStatusMap(){
		
		List<String> voNames = ConfiguredVOsUtil.getConfiguredVONames();
		
		Map<String, Boolean> statusMap = new HashMap<String, Boolean>();
		
		for (String vo: voNames){
			
			App voApp = manager.getAppByOriginId(vo);
			if (voApp == null){
				statusMap.put(vo, false);
			}else{
				try {
					
					statusMap.put(vo, DeploymentManager.getState(
						voApp.getContextHandler()).equals(AbstractLifeCycle.STARTED));
					
				} catch (Exception e1) {
					statusMap.put(vo, true);
				}
			}
		}
		return statusMap;
	}
	
	@Override
	public void handle(String target, Request baseRequest,
		HttpServletRequest request, HttpServletResponse response)
		throws IOException, ServletException {
	
		if (target.equals("/") || target.startsWith("/vomses")){
			baseRequest.setAttribute(VO_NAMES, 
				ConfiguredVOsUtil.getConfiguredVONames());
		
			baseRequest.setAttribute(STATUS_MAP_KEY,
				getStatusMap());
		
			baseRequest.setAttribute(HOST_KEY, 
				hostname);
		
			baseRequest.setAttribute(PORT_KEY, 
				request.getLocalPort());
		
			baseRequest.setAttribute(VERSION_KEY, 
				Version.version());
		}
		
		super.handle(target, baseRequest, request, response);
	}

	
	/**
	 * @return the manager
	 */
	public DeploymentManager getManager() {
	
		return manager;
	}

	
	/**
	 * @param manager the manager to set
	 */
	public void setManager(DeploymentManager manager) {
	
		this.manager = manager;
	}

	
	/**
	 * @return the hostname
	 */
	public String getHostname() {
	
		return hostname;
	}

	
	/**
	 * @param hostname the hostname to set
	 */
	public void setHostname(String hostname) {
	
		this.hostname = hostname;
	}

	
}
