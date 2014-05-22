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
        final Server server = new Server(8181);
        server.setHandler(new ProxyHandler("http://localhost:8080/"));
        server.start();
        server.join();
    }
}
