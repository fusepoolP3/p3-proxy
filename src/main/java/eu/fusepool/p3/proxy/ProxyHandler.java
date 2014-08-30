/*
 * Copyright 2014 Bern University of Applied Sciences.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.fusepool.p3.proxy;

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
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

public class ProxyHandler extends AbstractHandler {

    private final String targetBaseUri;
    private final CloseableHttpClient httpclient;

    ProxyHandler(String targetBaseUri) {
        if (targetBaseUri.endsWith("/")) {
            this.targetBaseUri = targetBaseUri.substring(0, targetBaseUri.length() - 1);
        } else {
            this.targetBaseUri = targetBaseUri;
        }
        final HttpClientBuilder hcb = HttpClientBuilder.create();
        hcb.setRedirectStrategy(new NeverRedirectStrategy());
        httpclient = hcb.build();
    }

    @Override
    public void handle(String target, Request baseRequest,
            final HttpServletRequest inRequest, final HttpServletResponse outResponse)
            throws IOException, ServletException {
        final String targetUrlString = targetBaseUri + inRequest.getRequestURI();
        //System.out.println(targetUrlString);
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
            final ServletOutputStream os = outResponse.getOutputStream();
            if (entity != null) {
                //outResponse.setContentType(target);
                final InputStream instream = entity.getContent();
                try {                    
                    IOUtils.copy(instream, os);
                } finally {
                    instream.close();
                }
            }
            //without flushing this and no or too little byte jetty return 404
            os.flush();
        } finally {
            inResponse.close();
        }
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
