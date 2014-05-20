/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package eu.fusepool.proxy;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;


public class ProxyHandler extends AbstractHandler {
    private final String targetBaseUri;

    ProxyHandler(String targetBaseUri) {
        if (targetBaseUri.endsWith("/")) {
            this.targetBaseUri = targetBaseUri.substring(0, targetBaseUri.length()-1);
        } else {
            this.targetBaseUri = targetBaseUri;
        }
    }

    public void handle(String target, Request baseRequest, 
            HttpServletRequest request, HttpServletResponse response) 
            throws IOException, ServletException {
        String targetUrlString = targetBaseUri+request.getRequestURI();
        System.out.println(targetUrlString);
        URL targetUrl = new URL(targetUrlString);
        HttpURLConnection urlc = (HttpURLConnection) targetUrl.openConnection();
        urlc.setRequestProperty("Host", request.getHeader("Host"));
        urlc.connect();
        for (Entry<String, List<String>> e : urlc.getHeaderFields().entrySet()) {
            final Iterator<String> values = e.getValue().iterator();
            final String key = e.getKey();
            if (key != null) { //HttpURLConncetion sees a status code as a null-key header
                response.setHeader(key, values.next());
                while (values.hasNext()) {
                    response.addHeader(key, values.next());
                }
            }
        }
        urlc.getInputStream();
        final ServletOutputStream os = response.getOutputStream();
        IOUtils.copy(urlc.getInputStream(), os);
        os.flush();
    }
    
}
