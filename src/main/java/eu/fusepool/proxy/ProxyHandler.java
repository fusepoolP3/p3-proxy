/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.fusepool.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

public class ProxyHandler extends AbstractHandler {

    private final String targetBaseUri;
    //private final CloseableHttpClient httpclient;

    ProxyHandler(String targetBaseUri) {
        if (targetBaseUri.endsWith("/")) {
            this.targetBaseUri = targetBaseUri.substring(0, targetBaseUri.length() - 1);
        } else {
            this.targetBaseUri = targetBaseUri;
        }
        
    }

    @Override
    public void handle(String target, Request baseRequest,
            final HttpServletRequest inRequest, final HttpServletResponse outResponse)
            throws IOException, ServletException {
        final HttpClientBuilder hcb = HttpClientBuilder.create();
        hcb.setRedirectStrategy(new NeverRedirectStrategy());
        CloseableHttpClient httpclient = hcb.build();
        String targetUrlString = targetBaseUri + inRequest.getRequestURI();
        System.out.println(targetUrlString);
        final URL targetUrl = new URL(targetUrlString);
        final HttpEntityEnclosingRequestBase outRequest = new HttpEntityEnclosingRequestBase() {

            @Override
            public String getMethod() {
                return inRequest.getMethod();
            }

        };
        try {
            outRequest.setURI(targetUrl.toURI());
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
        final Enumeration<String> headerNames = baseRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            final String headerName = headerNames.nextElement();
            final Enumeration<String> headerValues = baseRequest.getHeaders(headerName);
            while (headerValues.hasMoreElements()) {
                final String headerValue = headerValues.nextElement();
                if (!headerName.equalsIgnoreCase("Content-Length")) {
                    outRequest.setHeader(headerName, headerValue);
                }
            }
        }
        //slow: outRequest.setEntity(new InputStreamEntity(inRequest.getInputStream()));
        final byte[] inEntityBytes = IOUtils.toByteArray(inRequest.getInputStream());
        if (inEntityBytes.length > 0) {
            outRequest.setEntity(new ByteArrayEntity(inEntityBytes));
        }
        final CloseableHttpResponse inResponse = httpclient.execute(outRequest);
        try {
            outResponse.setStatus(inResponse.getStatusLine().getStatusCode());
            final Set<String> setHeaderNames = new HashSet();
            for (Header header : inResponse.getAllHeaders()) {
                if (setHeaderNames.add(header.getName())) {
                    outResponse.setHeader(header.getName(), header.getValue());
                } else {
                    outResponse.addHeader(header.getName(), header.getValue());
                }
            }
            final HttpEntity entity = inResponse.getEntity();
            if (entity != null) {
                //outResponse.setContentType(target);
                final InputStream instream = entity.getContent();
                try {
                    final ServletOutputStream os = outResponse.getOutputStream();
                    IOUtils.copy(instream, os);
                    os.flush();
                } finally {
                    instream.close();
                }
            }
        } finally {
            inResponse.close();
        }
        httpclient.close();

    }

    private static class NeverRedirectStrategy implements RedirectStrategy {

        public NeverRedirectStrategy() {
        }

        @Override
        public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
            return false;
        }

        @Override
        public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
            throw new UnsupportedOperationException("Should not be called");
        }
    }

}
