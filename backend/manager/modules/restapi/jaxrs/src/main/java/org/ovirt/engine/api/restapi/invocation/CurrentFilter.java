package org.ovirt.engine.api.restapi.invocation;

import java.io.IOException;

import javax.ejb.EJB;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ovirt.engine.core.common.businessentities.aaa.DbUser;
import org.ovirt.engine.core.common.config.ConfigCommon;
import org.ovirt.engine.core.common.constants.SessionConstants;
import org.ovirt.engine.core.common.interfaces.BackendLocal;
import org.ovirt.engine.core.common.mode.ApplicationMode;
import org.ovirt.engine.core.common.queries.ConfigurationValues;
import org.ovirt.engine.core.common.queries.GetConfigurationValueParameters;
import org.ovirt.engine.core.common.queries.VdcQueryParametersBase;
import org.ovirt.engine.core.common.queries.VdcQueryReturnValue;
import org.ovirt.engine.core.common.queries.VdcQueryType;

/**
 * This filter is responsible for initializing and cleaning the information that is associated to the current request.
 */
public class CurrentFilter implements Filter {
    // Names of headers:
    private static final String API_VERSION_HEADER = "Version";

    // Default values of headers:
    private static final String API_VERSION_DEFAULT = "4";

    /**
     * The reference to the backend bean.
     */
    @SuppressWarnings("unused")
    @EJB(lookup = "java:global/engine/bll/Backend!org.ovirt.engine.core.common.interfaces.BackendLocal")
    private BackendLocal backend;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        doFilter((HttpServletRequest) request, (HttpServletResponse) response, chain);
    }

    private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {

        String apiVersion = request.getHeader(API_VERSION_HEADER);
        if (apiVersion == null || apiVersion.isEmpty()) {
            apiVersion = API_VERSION_DEFAULT;
        }

        String sessionId = (String) request.getAttribute(SessionConstants.HTTP_SESSION_ENGINE_SESSION_ID_KEY);
        if (sessionId == null) {
            throw new ServletException("Engine session missing");
        }

        Current current = new Current();
        current.setApiVersion(apiVersion);
        current.setSessionId(sessionId);
        current.setApplicationMode(findApplicationMode(sessionId));
        current.setUser(findPrincipal(sessionId));
        CurrentManager.put(current);

        try {
            chain.doFilter(request, response);
        }
        finally {
            CurrentManager.remove();
        }
    }

    private ApplicationMode findApplicationMode(String sessionId) {
        GetConfigurationValueParameters parameters = new GetConfigurationValueParameters(
            ConfigurationValues.ApplicationMode,
            ConfigCommon.defaultConfigurationVersion
        );
        parameters.setSessionId(sessionId);
        VdcQueryReturnValue result = backend.runPublicQuery( VdcQueryType.GetConfigurationValue, parameters);
        return ApplicationMode.from(result.getReturnValue());
    }

    private DbUser findPrincipal(String sessionId) {
        VdcQueryReturnValue result = backend.runPublicQuery(VdcQueryType.GetDbUserBySession, new VdcQueryParametersBase(sessionId));
        return result.getReturnValue();
    }
}
