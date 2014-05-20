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
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

public class ProxyHandler extends AbstractHandler {

    private final String targetBaseUri;

    ProxyHandler(String targetBaseUri) {
        if (targetBaseUri.endsWith("/")) {
            this.targetBaseUri = targetBaseUri.substring(0, targetBaseUri.length() - 1);
        } else {
            this.targetBaseUri = targetBaseUri;
        }
    }

    public void handle(String target, Request baseRequest,
            final HttpServletRequest inRequest, final HttpServletResponse outResponse)
            throws IOException, ServletException {
        String targetUrlString = targetBaseUri + inRequest.getRequestURI();
        System.out.println(targetUrlString);
        final URL targetUrl = new URL(targetUrlString);
        final HttpRequestBase outRequest = new HttpRequestBase() {

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
        final CloseableHttpClient httpclient = HttpClients.createDefault();
        final CloseableHttpResponse inResponse = httpclient.execute(outRequest);
        try {
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


    }

}
