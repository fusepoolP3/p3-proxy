# P3 Proxy

An LDP proxy adding the extracting importer API.

Compile and run with

    mvn clean install exec:java

You can configure the proxy with the following system properties:

 - proxy.port: specifies the port the proxy should listen to (default: 8181)
 - target.uri: specifies the URI requests should be forwarded to (default: http://localhost:8080)

Example:

    mvn -Dtarget.uri=http://fusepool.openlinksw.com/ -Dproxy.port=8080 exec:java

This will start the proxy to listen to port 8080 and forward requests to http://fusepool.openlinksw.com/

As an alternative to running with maven a built exacutable jar can also be used. The 
jar is located in the target directory and create when building with maven (e.g. `mvn install`), "--help" shows its usage:

     java -jar proxy-*-jar-with-dependencies.jar --help
     This command has the following arguments: [-T|--target string] [-P|--port int] [-H|--help] 
       -T|--target string   The base URI to which to redirect the requests (hostname is only used to locate target server)
       -P|--port int        The port on which the proxy shall listen
       -H|--help            Show help on command line arguments
