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

import eu.fusepool.p3.transformer.client.Transformer;
import eu.fusepool.p3.transformer.client.TransformerClientImpl;
import eu.fusepool.p3.transformer.commons.Entity;
import eu.fusepool.p3.transformer.commons.util.InputStreamEntity;
import eu.fusepool.p3.vocab.ELDP;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.clerezza.commons.rdf.Graph;
import org.apache.clerezza.commons.rdf.IRI;
import org.apache.clerezza.commons.rdf.RDFTerm;
import org.apache.clerezza.commons.rdf.Triple;
import org.apache.clerezza.rdf.core.serializedform.Parser;
import org.apache.clerezza.rdf.core.serializedform.Serializer;
import org.apache.clerezza.rdf.core.serializedform.SupportedFormat;
import org.apache.commons.io.IOUtils;
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
        final String targetUriString = targetBaseUri + getFullRequestUriPath(inRequest);
        final String requestUri = getFullRequestUrl(inRequest);
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
            if (!"no-transform".equals(inRequest.getHeader("X-Fusepool-Proxy"))) {
                transformerUri = getTransformerUrl(requestUri);
            }
        }
        final Enumeration<String> headerNames = baseRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            final String headerName = headerNames.nextElement();
            if (headerName.equalsIgnoreCase("Content-Length") 
                    || headerName.equalsIgnoreCase("X-Fusepool-Proxy")
                    || headerName.equalsIgnoreCase("Transfer-Encoding")) {
                continue;
            }
            final Enumeration<String> headerValues = baseRequest.getHeaders(headerName);
            if (headerValues.hasMoreElements()) {
                final String headerValue = headerValues.nextElement();
                outRequest.setHeader(headerName, headerValue);
            }
            while (headerValues.hasMoreElements()) {
                final String headerValue = headerValues.nextElement();
                outRequest.addHeader(headerName, headerValue);
            }
        }
        final Header[] outRequestHeaders = outRequest.getAllHeaders();
        //slow: outRequest.setEntity(new InputStreamEntity(inRequest.getInputStream()));
        final byte[] inEntityBytes = IOUtils.toByteArray(inRequest.getInputStream());
        if (inEntityBytes.length > 0) {
            outRequest.setEntity(new ByteArrayEntity(inEntityBytes));
        }
        final CloseableHttpResponse inResponse = httpclient.execute(outRequest);      
        try {
            outResponse.setStatus(inResponse.getStatusLine().getStatusCode());
            final Header[] inResponseHeaders = inResponse.getAllHeaders();
            final Set<String> setHeaderNames = new HashSet();
            for (Header header : inResponseHeaders) {
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
                startTransformation(locationHeader.getValue(), requestUri, transformerUri, inEntityBytes, outRequestHeaders);
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
    
    private static String getFullRequestUriPath(HttpServletRequest request) {
        final StringBuffer requestURL = new StringBuffer(request.getRequestURI());
        if (request.getQueryString() != null) {
            requestURL.append("?").append(request.getQueryString());
        }
        return requestURL.toString();
    }

    /**
     * If uri is a transforming container returns the URI of the associated
     * container, otherwise null
     */
    private String getTransformerUrl(String uri) throws IOException {
        HttpGet httpGet = new HttpGet(uri);
        httpGet.addHeader("Accept", "text/turtle");
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
            final IRI baseUri = new IRI(uri);
            final Graph graph = parser.parse(entity.getContent(), contentType, baseUri);
            EntityUtils.consume(entity);
            final Iterator<Triple> triples = graph.filter(baseUri, ELDP.transformer, null);
            while (triples.hasNext()) {
                RDFTerm object = triples.next().getObject();
                if (object instanceof IRI) {
                    return ((IRI) object).getUnicodeString();
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
            final byte[] bytes,
            final Header[] requestHeaders) {
        (new Thread() {

            @Override
            public void run() {
                Transformer transformer= new TransformerClientImpl(transformerUri);
                Entity entity = new InputStreamEntity() {

                    @Override
                    public MimeType getType() {
                        try {
                            for(Header h : requestHeaders) {
                                if (h.getName().equalsIgnoreCase("Content-Type")) {
                                    return new MimeType(h.getValue());
                                }
                            }
                            return new MimeType("application/octet-stream");
                        } catch (MimeTypeParseException ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public InputStream getData() throws IOException {
                        return new ByteArrayInputStream(bytes);
                    }

                    @Override
                    public URI getContentLocation() {
                        try {
                            return new URI(resourceUri);
                        } catch (URISyntaxException ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                };

                Entity transformationResult;
                try {
                    transformationResult = transformer.transform(entity, new MimeType("*/*"));
                } catch (MimeTypeParseException ex) {
                    throw new RuntimeException(ex);
                }

                //final HttpEntity httpEntity = response.getEntity();
                //final Header contentTypeHeader = httpEntity.getContentType();
                final String contentType = transformationResult.getType().toString();
                try {
                    if (isRdf(contentType)) {
                        Graph transformationResultGraph = parser.parse(transformationResult.getData(), contentType);
                        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        serializer.serialize(baos, transformationResultGraph, SupportedFormat.TURTLE);
                        final byte[] bytes = baos.toByteArray();
                        final StringWriter turtleString = new StringWriter(baos.size() + 2000);
                        turtleString.append(new String(bytes, "UTF-8"));
                        turtleString.append('\n');
                        turtleString.append("<> " + ELDP.transformedFrom + " <" + resourceUri + "> .");
                        post(ldpcUri, new ByteArrayEntity(turtleString.toString().getBytes("UTF-8")), "text/turtle", resourceUri, requestHeaders);
                    } else {
                        post(ldpcUri, new org.apache.http.entity.InputStreamEntity(transformationResult.getData()), contentType, resourceUri, requestHeaders);
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
                
            }

        }).start();
    }

    private void post(String ldpcUri, HttpEntity entity, String mediaType,
            final String extractedFromUri, Header[] allHeaders) throws IOException {
        HttpPost httpPost = new HttpPost(ldpcUri);
        final Set<String> setHeaderNames = new HashSet();
        for (Header header : allHeaders) {
            if (header.getName().equalsIgnoreCase("Content-Length")) {
                continue;
            }
            if (setHeaderNames.add(header.getName())) {
                httpPost.setHeader(header.getName(), header.getValue());
            } else {
                httpPost.addHeader(header.getName(), header.getValue());
            }
        }
        httpPost.setHeader("Slug", extractedFromUri.substring(extractedFromUri.lastIndexOf('/') + 1) + "-transformed");
        httpPost.setHeader("Content-type", mediaType);
        //while we could also post directly to the proxied server, this would 
        //require twaeking host header and possibly request path
        //so we call back to the proxy and use this header to allow a transformation loop
        httpPost.setHeader("X-Fusepool-Proxy", "no-transform");
        httpPost.setEntity(entity);
        try (CloseableHttpResponse response = httpclient.execute(httpPost)) {
            if (response.getStatusLine().getStatusCode() != 201) {
                log.warn("Response to POST request to LDPC resulted in: "
                        + response.getStatusLine() + " rather than 201. URI: " + ldpcUri);
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
