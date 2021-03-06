package com.amazonaws.serverless.proxy.jersey;


import com.amazonaws.serverless.proxy.internal.servlet.AwsProxyHttpServletRequest;
import com.amazonaws.serverless.proxy.internal.testutils.Timer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.internal.PropertiesDelegate;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spi.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import static com.amazonaws.serverless.proxy.RequestReader.API_GATEWAY_CONTEXT_PROPERTY;
import static com.amazonaws.serverless.proxy.RequestReader.API_GATEWAY_STAGE_VARS_PROPERTY;
import static com.amazonaws.serverless.proxy.RequestReader.JAX_SECURITY_CONTEXT_PROPERTY;
import static com.amazonaws.serverless.proxy.RequestReader.LAMBDA_CONTEXT_PROPERTY;


public class JerseyHandlerFilter implements Filter, Container {
    public static final String JERSEY_SERVLET_REQUEST_PROPERTY = "com.amazonaws.serverless.jersey.servletRequest";
    public static final String JERSEY_SERVLET_RESPONSE_PROPERTY = "com.amazonaws.serverless.jersey.servletResponse";

    private ApplicationHandler jersey;
    private Application app;
    private Logger log = LoggerFactory.getLogger(JerseyHandlerFilter.class);

    JerseyHandlerFilter(Application jaxApplication) {
        Timer.start("JERSEY_FILTER_CONSTRUCTOR");
        app = jaxApplication;

        jersey = new ApplicationHandler(app);
        jersey.onStartup(this);
        Timer.stop("JERSEY_FILTER_CONSTRUCTOR");
    }

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("Initialize Jersey application handler");
    }


    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        Timer.start("JERSEY_FILTER_DOFILTER");
        // we use a latch to make the processing inside Jersey synchronous
        CountDownLatch jerseyLatch = new CountDownLatch(1);

        ContainerRequest req = servletRequestToContainerRequest(servletRequest);
        req.setWriter(new JerseyServletResponseWriter(servletResponse, jerseyLatch));

        req.setProperty(JERSEY_SERVLET_RESPONSE_PROPERTY, servletResponse);

        jersey.handle(req);
        try {
            jerseyLatch.await();
        } catch (InterruptedException e) {
            log.error("Interrupted while processing request", e);
            throw new InternalServerErrorException(e);
        }
        Timer.stop("JERSEY_FILTER_DOFILTER");
        filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy() {
        log.info("Jersey filter destroy");
        jersey.onShutdown(this);
    }

    // suppressing warnings because I expect headers and query strings to be checked by the underlying
    // servlet implementation
    @SuppressFBWarnings({ "SERVLET_HEADER", "SERVLET_QUERY_STRING" })
    private ContainerRequest servletRequestToContainerRequest(ServletRequest request) {
        Timer.start("JERSEY_SERVLET_REQUEST_TO_CONTAINER");
        URI basePathUri;
        URI requestPathUri;
        String basePath = "/";
        HttpServletRequest servletRequest = (HttpServletRequest)request;

        try {
            if (servletRequest.getContextPath().equals("")) {
                basePathUri = URI.create(basePath);
            } else {
                basePathUri = new URI(servletRequest.getContextPath());
            }
        } catch (URISyntaxException e) {
            log.error("Could not read base path URI", e);
            basePathUri = URI.create(basePath);
        }

        UriBuilder uriBuilder = UriBuilder.fromPath(servletRequest.getPathInfo());
        uriBuilder.replaceQuery(AwsProxyHttpServletRequest.decodeValueIfEncoded(servletRequest.getQueryString()));

        requestPathUri = uriBuilder.build();

        PropertiesDelegate apiGatewayProperties = new MapPropertiesDelegate();
        apiGatewayProperties.setProperty(API_GATEWAY_CONTEXT_PROPERTY, servletRequest.getAttribute(API_GATEWAY_CONTEXT_PROPERTY));
        apiGatewayProperties.setProperty(API_GATEWAY_STAGE_VARS_PROPERTY, servletRequest.getAttribute(API_GATEWAY_STAGE_VARS_PROPERTY));
        apiGatewayProperties.setProperty(LAMBDA_CONTEXT_PROPERTY, servletRequest.getAttribute(LAMBDA_CONTEXT_PROPERTY));
        apiGatewayProperties.setProperty(JERSEY_SERVLET_REQUEST_PROPERTY, servletRequest);

        ContainerRequest requestContext = new ContainerRequest(
                basePathUri,
                requestPathUri,
                servletRequest.getMethod().toUpperCase(Locale.ENGLISH),
                (SecurityContext)servletRequest.getAttribute(JAX_SECURITY_CONTEXT_PROPERTY),
                apiGatewayProperties);

        InputStream requestInputStream;
        try {
            requestInputStream = servletRequest.getInputStream();
            if (requestInputStream != null) {
                requestContext.setEntityStream(requestInputStream);
            }
        } catch (IOException e) {
            log.error("Could not read input stream from request", e);
        }

        Enumeration<String> headerNames = servletRequest.getHeaderNames();

        while (headerNames.hasMoreElements()) {
            String headerKey = headerNames.nextElement();
            requestContext.header(headerKey, servletRequest.getHeader(headerKey));
        }
        Timer.stop("JERSEY_SERVLET_REQUEST_TO_CONTAINER");
        return requestContext;
    }

    //-------------------------------------------------------------
    // Implementation - Container
    //-------------------------------------------------------------


    @Override
    public ResourceConfig getConfiguration() {
        return jersey.getConfiguration();
    }


    @Override
    public ApplicationHandler getApplicationHandler() {
        return jersey;
    }


    /**
     * Shuts down and restarts the application handler in the current container. The <code>ApplicationHandler</code>
     * object is re-initialized with the <code>Application</code> object initially set in the <code>LambdaContainer.getInstance()</code>
     * call.
     */
    @Override
    public void reload() {
        Timer.start("JERSEY_RELOAD_DEFAULT");
        jersey.onShutdown(this);

        jersey = new ApplicationHandler(app);

        jersey.onReload(this);
        jersey.onStartup(this);
        Timer.stop("JERSEY_RELOAD_DEFAULT");
    }


    /**
     * Restarts the application handler and configures a different <code>Application</code> object. The new application
     * resets the one currently configured in the container.
     * @param resourceConfig An initialized Application
     */
    @Override
    public void reload(ResourceConfig resourceConfig) {
        Timer.start("JERSEY_RELOAD_CONFIG");
        jersey.onShutdown(this);

        app = resourceConfig;
        jersey = new ApplicationHandler(resourceConfig);

        jersey.onReload(this);
        jersey.onStartup(this);
        Timer.stop("JERSEY_RELOAD_CONFIG");
    }
}
