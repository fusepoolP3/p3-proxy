/*
 * Copyright 2014 reto.
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


import eu.fusepool.p3.proxy.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.jayway.restassured.RestAssured;
import eu.fusepool.p3.transformer.AsyncTransformer;
import eu.fusepool.p3.transformer.HttpRequestEntity;
import eu.fusepool.p3.transformer.SyncTransformer;
import eu.fusepool.p3.transformer.Transformer;
import eu.fusepool.p3.transformer.commons.Entity;
import eu.fusepool.p3.transformer.commons.util.InputStreamEntity;
import eu.fusepool.p3.transformer.sample.LongRunningTransformer;
import eu.fusepool.p3.transformer.server.TransformerServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import org.apache.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.hamcrest.core.IsEqual;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 *
 * @author reto
 */
public class ProxyAutoConfigTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(backendPort);
    //TODO choose free port instead
    private static final int backendPort = findFreePort();
    private int transformerPort = backendPort+1;
    private static int proxyPort = 8080;
    static Server server;
    public int longRunningSeconds = 50;

    @BeforeClass
    public static void startProxy() throws Exception {
        proxyPort = findFreePort();
        Assert.assertNotEquals("the assignment of different ports went wrong", backendPort, proxyPort);
        server = new Server(proxyPort);
        //Assuming we get a file-URI
        final String autoConfigPath = ProxyAutoConfigTest.class.getResource("/autoconfig").getFile();
        server.setHandler(new ProxyHandler("http://localhost:" + backendPort, autoConfigPath));
        server.start();
        RestAssured.baseURI = "http://localhost:" + proxyPort + "/";
    }

    @AfterClass
    public static void stopProxy() throws Exception {
        server.stop();
    }
    

    @Test
    public void nonTransformingContainer() throws Exception {

        final String turtleLdpc = "@prefix dcterms: <http://purl.org/dc/terms/>.\n"
                + "@prefix ldp: <http://www.w3.org/ns/ldp#>.\n"
                + "@prefix eldp: <http://vocab.fusepool.info/eldp#>.\n"
                + "\n"
                + "<http://example.org/container1/>\n"
                + "   a ldp:DirectContainer;\n"
                + "   dcterms:title \"An extracting LDP Container using simple-transformer\";\n"
                + "   ldp:membershipResource <http://example.org/container1/>;\n"
                + "   ldp:hasMemberRelation ldp:member;\n"
                + "   ldp:insertedContentRelation ldp:MemberSubject.";
        
        stubFor(get(urlEqualTo("/my/resource"))
                .withHeader("Accept", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/turtle")
                        .withBody(turtleLdpc)));
        
        stubFor(put(urlEqualTo("/ldp/platform.nt"))
                .withHeader("Content-Type", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(201)));

        RestAssured.given().header("Accept", "text/turtle")
                .expect().statusCode(HttpStatus.SC_OK)
                .header("Content-Type", "text/turtle")
                .body(new IsEqual(turtleLdpc)).when()
                .get("/my/resource");
        
        verify(putRequestedFor(urlMatching("/ldp/platform.nt")).withRequestBody(containing("sparqlEndoint")));

    }

    public static int findFreePort() {
        int port = 0;
        try (ServerSocket server = new ServerSocket(0);) {
            port = server.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("unable to find a free port");
        }
        return port;
    }

}
