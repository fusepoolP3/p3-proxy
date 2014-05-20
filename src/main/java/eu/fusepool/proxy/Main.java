/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package eu.fusepool.proxy;

import org.eclipse.jetty.server.Server;

/**
 *
 * @author reto
 */
public class Main {
    public static void main(String[] args) throws Exception
    {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        Server server = new Server(8181);
        server.setHandler(new ProxyHandler("http://www.ti.bfh.ch/"));
        server.start();
        server.join();
    }
}
