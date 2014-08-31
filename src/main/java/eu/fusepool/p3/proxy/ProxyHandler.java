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

import eu.fusepool.p3.vocab.ELDP;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.clerezza.rdf.core.Graph;
import org.apache.clerezza.rdf.core.Resource;
import org.apache.clerezza.rdf.core.Triple;
import org.apache.clerezza.rdf.core.UriRef;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.core.serializedform.Serializer;
import org.apache.clerezza.rdf.core.serializedform.SupportedFormat;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyHandler extends AbstractHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);
    private final String targetBaseUri;
    private final CloseableHttpClient httpclient;
    private final Parser parser = Parser.getInstance();
    private final Serializer serializer = Serializer.getInstance();

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
        final String targetUriString = targetBaseUri + inRequest.getRequestURI();
        //System.out.println(targetUriString);
        final URI targetUri;
        try {
            targetUri = new URI(targetUriString);
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
        final String method = inRequest.getMethod();
        final HttpEntityEnclosingRequestBase outRequest = new HttpEntityEnclosingRequestBase() {

            @Override
            public String getMethod() {
                return method;
            }

        };
        outRequest.setURI(targetUri);
        String transformerUri = null;
        if (method.equals("POST")) {
            transformerUri = getTransformerUrl(getFullRequestUrl(inRequest), targetUri);
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
        if (transformerUri != null) {
            Header locationHeader = inResponse.getFirstHeader("Location");
            if (locationHeader == null) {
                log.warn("Response to POST request to LDPC contains no Location header. URI: " + targetUriString);
            } else {
                startTransformation(locationHeader.getValue(), targetUriString, transformerUri, inEntityBytes);
            }
        }
    }

    /*
     * Rather than just checking if its RDF we should check the Liunke header following LDP Spec 5.2.1
     */
    private boolean isRdf(String contentTypeHeader) {
        for (String supported : parser.getSupportedFormats()) {
            if (supported.equalsIgnoreCase(contentTypeHeader)) {
                return true;
            }
        }
        return false;
    }

    private static String getFullRequestUrl(HttpServletRequest request) {
        StringBuffer requestURL = request.getRequestURL();
        if (request.getQueryString() != null) {
            requestURL.append("?").append(request.getQueryString());
        }
        return requestURL.toString();
    }

    /**
     * If targetUrl is a transforming container returns the URI of the
     * associated container, otherwise null
     */
    private String getTransformerUrl(String requestedUri, URI targetUri) throws IOException {
        HttpGet httpGet = new HttpGet(targetUri);
        CloseableHttpResponse response = httpclient.execute(httpGet);
        try {
            final Header contentTypeHeader = response.getFirstHeader("Content-Type");
            if (contentTypeHeader == null) {
                return null;
            }
            final String contentType = contentTypeHeader.getValue();
            if (!isRdf(contentType)) {
                return null;
            }
            HttpEntity entity = response.getEntity();
            final UriRef baseUri = new UriRef(requestedUri);
            final Graph graph = parser.parse(entity.getContent(), contentType, baseUri);
            EntityUtils.consume(entity);
            final Iterator<Triple> triples = graph.filter(baseUri, ELDP.transformer, null);
            while (triples.hasNext()) {
                Resource object = triples.next().getObject();
                if (object instanceof UriRef) {
                    return ((UriRef) object).getUnicodeString();
                }
            }
        } finally {
            response.close();
        }
        return null;
    }

    private void startTransformation(final String resourceUri, 
            final String ldpcUri,
            final String transformerUri,
            final byte[] bytes) {
        (new Thread() {

            @Override
            public void run() {
                HttpPost httpPost = new HttpPost(transformerUri);
                httpPost.setHeader("Content-Location", resourceUri);
                httpPost.setEntity(new ByteArrayEntity(bytes));
                try (CloseableHttpResponse response = httpclient.execute(httpPost)) {
                    final HttpEntity entity = response.getEntity();
                    final Header contentTypeHeader = entity.getContentType();
                    final String contentType = contentTypeHeader.getValue();
                    if (isRdf(contentType)) {
                        Graph transformationResult = parser.parse(entity.getContent(), contentType);
                        final ByteArrayOutputStream baos = new ByteArrayOutputStream((int) (entity.getContentLength()+3000));
                        serializer.serialize(baos, transformationResult, SupportedFormat.TURTLE);
                        final byte[] bytes = baos.toByteArray();
                        final StringWriter turtleString = new StringWriter(baos.size()+2000);
                        turtleString.append(new String(bytes, "UTF-8"));
                        turtleString.append('\n');
                        turtleString.append("<> "+ELDP.transformedFrom+" <"+resourceUri+ "> .");
                        post(ldpcUri, new ByteArrayEntity(turtleString.toString().getBytes("UTF-8")), "text/turtle", resourceUri);
                    } else {
                        post(ldpcUri, entity, contentType, resourceUri);
                    }
                    EntityUtils.consume(entity);
                } catch (IOException ex) {
                    log.error("Error executing transformer: "+transformerUri, ex);
                }
            }

        }).start();
    }
    
    private void post(String ldpcUri, HttpEntity entity, String mediaType,
            final String extractedFromUri) throws IOException {
        HttpPost httpPost = new HttpPost(ldpcUri);
        httpPost.setHeader("Slug", extractedFromUri.substring(extractedFromUri.lastIndexOf('/'))+"-transformed");
        httpPost.setHeader("Content-type", mediaType);
        httpPost.setEntity(entity);
        try (CloseableHttpResponse response = httpclient.execute(httpPost)) {
            if (response.getStatusLine().getStatusCode() != 201) {
                log.warn("Response to POST request to LDPC resulted in: "
                        +response.getStatusLine()+" rather than 201. URI: "+ldpcUri);
            }
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
