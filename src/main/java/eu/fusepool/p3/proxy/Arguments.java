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

import org.wymiwyg.commons.util.arguments.ArgumentsWithHelp;
import org.wymiwyg.commons.util.arguments.CommandLine;

/**
 *
 * @author reto
 */
public interface Arguments extends ArgumentsWithHelp {
    

    @CommandLine(longName = "port", shortName = {"P"}, required = false,
            defaultValue = "8181",
            description = "The port on which the proxy shall listen")
    public int getPort();
    
    @CommandLine(longName = "target", shortName = {"T"}, required = false,
            defaultValue = "http://localhost:8080/",
            description = "The base URI to which to redirect the requests (hostname is only used to locate target server)")
    public String getTarget();
    
}
