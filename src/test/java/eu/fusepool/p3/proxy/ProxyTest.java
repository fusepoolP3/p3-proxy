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
public class ProxyTest {

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
        server.setHandler(new ProxyHandler("http://localhost:" + backendPort));
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

        RestAssured.given().header("Accept", "text/turtle")
                .expect().statusCode(HttpStatus.SC_OK)
                .header("Content-Type", "text/turtle")
                .body(new IsEqual(turtleLdpc)).when()
                .get("/my/resource");

    }
    
    @Test
    public void queryParamsForwarded() throws Exception {

        final String textResponse = "hello";
        
        stubFor(get(urlEqualTo("/my/test?a=1&b=2"))
                .withHeader("Accept", equalTo("text/plain"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(textResponse)));

        RestAssured.given().header("Accept", "text/plain")
                .expect().statusCode(HttpStatus.SC_OK)
                .header("Content-Type", "text/plain")
                .body(new IsEqual(textResponse)).when()
                .get("/my/test?a=1&b=2");

    }
    
    @Test
    public void transformingContainer() throws Exception {

        final String turtleLdpc = "@prefix dcterms: <http://purl.org/dc/terms/>.\n"
                + "@prefix ldp: <http://www.w3.org/ns/ldp#>.\n"
                + "@prefix eldp: <http://vocab.fusepool.info/eldp#>.\n"
                + "\n"
                + "<http://localhost:"+proxyPort+"/container1/>\n"
                + "   a ldp:DirectContainer;\n"
                + "   dcterms:title \"An extracting LDP Container using simple-transformer\";\n"
                + "   ldp:membershipResource <http://localhost:"+proxyPort+"/container1/>;\n"
                + "   ldp:hasMemberRelation ldp:member;\n"
                + "   ldp:insertedContentRelation ldp:MemberSubject;\n"
                + "   eldp:transformer <http://localhost:"+backendPort+"/simple-transformer>.";
        
        final String turtleTransformer = "@prefix dct: <http://purl.org/dc/terms/>.\n" +
                "@prefix trans: <http://vocab.fusepool.info/transformer#>.\n" +
                "<http://example.org/simple-transformer> a trans:Transformer;\n" +
                "    dct:title \"A simple RDF Transformation\"@en;\n" +
                "    dct:description \"transforms vcards to RDF\";\n" +
                "    trans:supportedInputFormat \"text/vcard\";\n" +
                "    trans:supportedOutputFormat \"text/turtle\";\n" +
                "    trans:supportedOutputFormat \"text/ld+json\".";
        
        stubFor(get(urlEqualTo("/container1/"))
                //.withHeader("Accept", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/turtle")
                        .withBody(turtleLdpc)));
        
        stubFor(post(urlEqualTo("/container1/"))
                //.withHeader("Conetnt-Type", matching("text/plain*"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_CREATED)
                        .withHeader("Location", "http://localhost:"+proxyPort+"/container1/new-resource")));
        
        stubFor(get(urlEqualTo("/simple-transformer"))
                //.withHeader("Accept", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/turtle")
                        .withBody(turtleTransformer)));
        
        stubFor(post(urlEqualTo("/simple-transformer"))
                //.withHeader("Accept", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("I am transformed")));
        
        //A GET request returns the unmodified answer
        RestAssured.given().header("Accept", "text/turtle")
                .expect().statusCode(HttpStatus.SC_OK).header("Content-Type", "text/turtle").when()
                .get("/container1/");
        //Certainly the backend got the request
        verify(getRequestedFor(urlMatching("/container1/")));
        
        //Let's post some content
        RestAssured.given()
                .contentType("text/plain;charset=UTF-8")
                .header("Authorization", "foobar")
                .content("hello")
                .expect().statusCode(HttpStatus.SC_CREATED).when()
                .post("/container1/");
        //the backend got the post request aiaginst the LDPC
        verify(postRequestedFor(urlMatching("/container1/")).withHeader("Authorization", equalTo("foobar")));
        //and after a while also against the Transformer
        //first the transformer whould be checcked if the format matches
        //wait and try: verify(getRequestedFor(urlMatching("/simple-transformer")));
        //then we will get a POST (since media type is supported)
        int i = 0;
        while (true) {
            Thread.sleep(100);
            try {
                verify(postRequestedFor(urlMatching("/simple-transformer")));
                break;
            } catch (Error e) {
                
            }
            if (i++ > 600) {
                //after one minute for real:
                verify(postRequestedFor(urlMatching("/simple-transformer")));
            }
        }
        i = 0;
        while (true) {            
            try {
                verify(2,postRequestedFor(urlMatching("/container1/")).withHeader("Authorization", equalTo("foobar")));
                break;
            } catch (Error e) {
                
            }
            Thread.sleep(100);
            if (i++ > 600) {
                //after one minute for real:
                verify(2,postRequestedFor(urlMatching("/container1/")).withHeader("Authorization", equalTo("foobar")));
            }
        }
        
    }
    
    @Test
    public void postingTurtleToTransformingContainer() throws Exception {

        final String turtleLdpc = "@prefix dcterms: <http://purl.org/dc/terms/>.\n"
                + "@prefix ldp: <http://www.w3.org/ns/ldp#>.\n"
                + "@prefix eldp: <http://vocab.fusepool.info/eldp#>.\n"
                + "\n"
                + "<http://localhost:"+proxyPort+"/container1/>\n"
                + "   a ldp:DirectContainer;\n"
                + "   dcterms:title \"An extracting LDP Container using simple-transformer\";\n"
                + "   ldp:membershipResource <http://localhost:"+proxyPort+"/container1/>;\n"
                + "   ldp:hasMemberRelation ldp:member;\n"
                + "   ldp:insertedContentRelation ldp:MemberSubject;\n"
                + "   eldp:transformer <http://localhost:"+backendPort+"/rdf-transformer>.";
        
        final String turtleTransformer = "@prefix dct: <http://purl.org/dc/terms/>.\n" +
                "@prefix trans: <http://vocab.fusepool.info/transformer#>.\n" +
                "<http://example.org/simple-transformer> a trans:Transformer;\n" +
                "    dct:title \"A simple RDF Transformation\"@en;\n" +
                "    dct:description \"transforms vcards to RDF\";\n" +
                "    trans:supportedInputFormat \"text/turtle\";\n" +
                "    trans:supportedOutputFormat \"text/turtle\";\n" +
                "    trans:supportedOutputFormat \"text/ld+json\".";
        
        final String turtleUnTransformed = "@prefix dct: <http://purl.org/dc/terms/>.\n" +
                "[] dct:title \"Some thing\"@en;.";
        
        final String turtleTransformed = "@prefix dct: <http://purl.org/dc/terms/>.\n" +
                "[] dct:title \"Some transformed thing\"@en;.";
        
        stubFor(get(urlEqualTo("/container1/"))
                //.withHeader("Accept", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/turtle")
                        .withBody(turtleLdpc)));
        
        stubFor(post(urlEqualTo("/container1/"))
                //.withHeader("Conetnt-Type", matching("text/plain*"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_CREATED)
                        .withHeader("Location", "http://localhost:"+proxyPort+"/container1/new-resource")));
        
        stubFor(get(urlEqualTo("/rdf-transformer"))
                //.withHeader("Accept", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/turtle")
                        .withBody(turtleTransformer)));
        
        stubFor(post(urlEqualTo("/rdf-transformer"))
                //.withHeader("Accept", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/turtle")
                        .withBody(turtleTransformed)));
        
        //A GET request returns the unmodified answer
        RestAssured.given().header("Accept", "text/turtle")
                .expect().statusCode(HttpStatus.SC_OK).header("Content-Type", "text/turtle").when()
                .get("/container1/");
        //Certainly the backend got the request
        verify(getRequestedFor(urlMatching("/container1/")));
        
        //Let's post some content
        RestAssured.given()
                .contentType("text/plain;charset=UTF-8")
                .header("Authorization", "foobar")
                .content("hello")
                .expect().statusCode(HttpStatus.SC_CREATED).when()
                .post("/container1/");
        //the backend got the post request against the LDPC
        verify(postRequestedFor(urlMatching("/container1/")).withHeader("Authorization", equalTo("foobar")));
        //and after a while also against the Transformer
        //first the transformer whould be checcked if the format matches
        //wait and try: verify(getRequestedFor(urlMatching("/simple-transformer")));
        //then we will get a POST (since media type is supported)
        int i = 0;
        while (true) {
            Thread.sleep(100);
            try {
                verify(postRequestedFor(urlMatching("/rdf-transformer")));
                break;
            } catch (Error e) {
                
            }
            if (i++ > 600) {
                //after one minute for real:
                verify(postRequestedFor(urlMatching("/rdf-transformer")));
            }
        }
        i = 0;
        while (true) {            
            try {
                verify(2,postRequestedFor(urlMatching("/container1/")).withHeader("Authorization", equalTo("foobar")));
                break;
            } catch (Error e) {
                
            }
            Thread.sleep(100);
            if (i++ > 600) {
                //after one minute for real:
                verify(2,postRequestedFor(urlMatching("/container1/")).withHeader("Authorization", equalTo("foobar")));
            }
        }
        
    }
    
    @Test
    public void longRunningTransformer() throws Exception {
        
        TransformerServer transformerServer = new TransformerServer(transformerPort, false);
        final boolean[] transformerInvoked = new boolean[1];
        transformerServer.start(new SyncTransformer() {

            @Override
            public Entity transform(HttpRequestEntity hre) throws IOException {
                transformerInvoked[0] = true;
                try {
                    Thread.sleep(longRunningSeconds*1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new InputStreamEntity() {

                    @Override
                    public MimeType getType() {
                        try {
                            return new MimeType("text/plain");
                        } catch (MimeTypeParseException ex) {
                            throw new RuntimeException(ex);
                        }
                    }

                    @Override
                    public InputStream getData() throws IOException {
                        return new ByteArrayInputStream("Tranformed".getBytes());
                    }
                };
            }

            @Override
            public boolean isLongRunning() {
                return true;
            }

            @Override
            public Set<MimeType> getSupportedInputFormats() {
                try {
                    return Collections.singleton(new MimeType("text/plain"));
                } catch (MimeTypeParseException ex) {
                    throw new RuntimeException(ex);
                }
            }

            @Override
            public Set<MimeType> getSupportedOutputFormats() {
                try {
                    return Collections.singleton(new MimeType("text/plain"));
                } catch (MimeTypeParseException ex) {
                    throw new RuntimeException(ex);
                }
            }


        
        });

        final String turtleLdpc = "@prefix dcterms: <http://purl.org/dc/terms/>.\n"
                + "@prefix ldp: <http://www.w3.org/ns/ldp#>.\n"
                + "@prefix eldp: <http://vocab.fusepool.info/eldp#>.\n"
                + "\n"
                + "<http://localhost:"+proxyPort+"/container2/>\n"
                + "   a ldp:DirectContainer;\n"
                + "   dcterms:title \"An extracting LDP Container using simple-transformer\";\n"
                + "   ldp:membershipResource <http://localhost:"+proxyPort+"/container1/>;\n"
                + "   ldp:hasMemberRelation ldp:member;\n"
                + "   ldp:insertedContentRelation ldp:MemberSubject;\n"
                + "   eldp:transformer <http://localhost:"+transformerPort+"/long-transformer>.";
        
        stubFor(get(urlEqualTo("/container2/"))
                //.withHeader("Accept", equalTo("text/turtle"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/turtle")
                        .withBody(turtleLdpc)));
        
        stubFor(post(urlEqualTo("/container2/"))
                //.withHeader("Conetnt-Type", matching("text/plain*"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_CREATED)
                        .withHeader("Location", "http://localhost:"+proxyPort+"/container2/new-resource")));
        
        
        //A GET request returns the unmodified answer
        RestAssured.given().header("Accept", "text/turtle")
                .expect().statusCode(HttpStatus.SC_OK).header("Content-Type", "text/turtle").when()
                .get("/container2/");
        //Certainly the backend got the request
        verify(getRequestedFor(urlMatching("/container2/")));
        
        //Let's post some content
        RestAssured.given()
                .contentType("text/plain;charset=UTF-8")
                .header("Authorization", "foobar")
                .content("hello")
                .expect().statusCode(HttpStatus.SC_CREATED).when()
                .post("/container2/");
        //the backend got the post request aiaginst the LDPC
        verify(postRequestedFor(urlMatching("/container2/")).withHeader("Authorization", equalTo("foobar")));
        //and after a while also against the Transformer
        //first the transformer whould be checcked if the format matches
        //wait and try: verify(getRequestedFor(urlMatching("/simple-transformer")));
        //then we will get a POST (since media type is supported)
        int i = 0;
        while (true) {
            Thread.sleep(100);
            try {
                Assert.assertTrue(transformerInvoked[0]);
                break;
            } catch (Error e) {
                
            }
            if (i++ > 600) {
                //after one minute for real:
                Assert.assertTrue(transformerInvoked[0]);
            }
        }
        Thread.sleep(longRunningSeconds*1000);
        i = 0;
        while (true) {            
            try {
                verify(2,postRequestedFor(urlMatching("/container2/")).withHeader("Authorization", equalTo("foobar")));
                break;
            } catch (Error e) {
                
            }
            Thread.sleep(100);
            if (i++ > 1200) {
                //after two minute for real (two minutes is the maximum polling interval of the transformer client)
                verify(2,postRequestedFor(urlMatching("/container2/")).withHeader("Authorization", equalTo("foobar")));
            }
        }
        
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
