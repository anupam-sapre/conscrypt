/*
 * Copyright (C) 2010 The Android Open Source Project
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

package org.conscrypt.javax.net.ssl;

import static org.conscrypt.TestUtils.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import libcore.java.security.StandardNames;
import libcore.tlswire.handshake.CipherSuite;
import libcore.tlswire.handshake.ClientHello;
import libcore.tlswire.handshake.CompressionMethod;
import libcore.tlswire.handshake.EllipticCurve;
import libcore.tlswire.handshake.EllipticCurvesHelloExtension;
import libcore.tlswire.handshake.HandshakeMessage;
import libcore.tlswire.handshake.HelloExtension;
import libcore.tlswire.record.TlsProtocols;
import libcore.tlswire.record.TlsRecord;
import libcore.tlswire.util.TlsProtocolVersion;
import org.conscrypt.TestUtils;
import org.conscrypt.java.security.TestKeyStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import tests.util.ForEachRunner;
import tests.util.ForEachRunner.Callback;
import tests.util.Pair;

@RunWith(JUnit4.class)
public class SSLSocketTest {
    private ExecutorService executor;
    private ThreadGroup threadGroup;

    @Before
    public void setup() {
        threadGroup = new ThreadGroup("SSLSocketTest");
        executor = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(threadGroup, r);
            }
        });
    }

    @After
    public void teardown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    public void test_SSLSocket_defaultConfiguration() throws Exception {
        SSLConfigurationAsserts.assertSSLSocketDefaultConfiguration(
                (SSLSocket) SSLSocketFactory.getDefault().createSocket());
    }

    @Test
    public void test_SSLSocket_getSupportedCipherSuites_returnsCopies() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        assertNotSame(ssl.getSupportedCipherSuites(), ssl.getSupportedCipherSuites());
    }

    @Test
    public void test_SSLSocket_getSupportedCipherSuites_connect() throws Exception {
        // note the rare usage of non-RSA keys
        TestKeyStore testKeyStore = new TestKeyStore.Builder()
                                            .keyAlgorithms("RSA", "DSA", "EC", "EC_RSA")
                                            .aliasPrefix("rsa-dsa-ec")
                                            .ca(true)
                                            .build();
        StringBuilder error = new StringBuilder();
        test_SSLSocket_getSupportedCipherSuites_connect(testKeyStore, error);
        if (error.length() > 0) {
            throw new Exception("One or more problems in "
                    + "test_SSLSocket_getSupportedCipherSuites_connect:\n" + error);
        }
    }

    private void test_SSLSocket_getSupportedCipherSuites_connect(
            TestKeyStore testKeyStore, StringBuilder error) throws Exception {
        String clientToServerString = "this is sent from the client to the server...";
        String serverToClientString = "... and this from the server to the client";
        byte[] clientToServer = clientToServerString.getBytes(UTF_8);
        byte[] serverToClient = serverToClientString.getBytes(UTF_8);
        KeyManager pskKeyManager =
                PSKKeyManagerProxy.getConscryptPSKKeyManager(new PSKKeyManagerProxy() {
                    @Override
                    protected SecretKey getKey(
                            String identityHint, String identity, Socket socket) {
                        return newKey();
                    }

                    @Override
                    protected SecretKey getKey(
                            String identityHint, String identity, SSLEngine engine) {
                        return newKey();
                    }

                    private SecretKey newKey() {
                        return new SecretKeySpec("Just an arbitrary key".getBytes(UTF_8), "RAW");
                    }
                });
        TestSSLContext c = TestSSLContext.newBuilder()
                                   .client(testKeyStore)
                                   .server(testKeyStore)
                                   .additionalClientKeyManagers(new KeyManager[] {pskKeyManager})
                                   .additionalServerKeyManagers(new KeyManager[] {pskKeyManager})
                                   .build();
        String[] cipherSuites = c.clientContext.getSocketFactory().getSupportedCipherSuites();
        for (String cipherSuite : cipherSuites) {
            try {
                /*
                 * TLS_EMPTY_RENEGOTIATION_INFO_SCSV cannot be used on
                 * its own, but instead in conjunction with other
                 * cipher suites.
                 */
                if (cipherSuite.equals(StandardNames.CIPHER_SUITE_SECURE_RENEGOTIATION)) {
                    continue;
                }
                /*
                 * Similarly with the TLS_FALLBACK_SCSV suite, it is not
                 * a selectable suite, but is used in conjunction with
                 * other cipher suites.
                 */
                if (cipherSuite.equals(StandardNames.CIPHER_SUITE_FALLBACK)) {
                    continue;
                }
                /*
                 * This test uses TLS 1.2, and the TLS 1.3 cipher suites aren't customizable
                 * anyway.
                 */
                if (StandardNames.CIPHER_SUITES_TLS13.contains(cipherSuite)) {
                    continue;
                }
                String[] clientCipherSuiteArray =
                        new String[] {cipherSuite, StandardNames.CIPHER_SUITE_SECURE_RENEGOTIATION};
                TestSSLSocketPair socketPair = TestSSLSocketPair.create(c).connect(
                        clientCipherSuiteArray, clientCipherSuiteArray);
                SSLSocket server = socketPair.server;
                SSLSocket client = socketPair.client;
                // Check that the client can read the message sent by the server
                server.getOutputStream().write(serverToClient);
                byte[] clientFromServer = new byte[serverToClient.length];
                readFully(client.getInputStream(), clientFromServer);
                assertEquals(serverToClientString, new String(clientFromServer, UTF_8));
                // Check that the server can read the message sent by the client
                client.getOutputStream().write(clientToServer);
                byte[] serverFromClient = new byte[clientToServer.length];
                readFully(server.getInputStream(), serverFromClient);
                assertEquals(clientToServerString, new String(serverFromClient, UTF_8));
                // Check that the server and the client cannot read anything else
                // (reads should time out)
                server.setSoTimeout(10);
                try {
                    @SuppressWarnings("unused")
                    int value = server.getInputStream().read();
                    fail();
                } catch (IOException expected) {
                    // Ignored.
                }
                client.setSoTimeout(10);
                try {
                    @SuppressWarnings("unused")
                    int value = client.getInputStream().read();
                    fail();
                } catch (IOException expected) {
                    // Ignored.
                }
                client.close();
                server.close();
            } catch (Exception maybeExpected) {
                String message = ("Problem trying to connect cipher suite " + cipherSuite);
                System.out.println(message);
                maybeExpected.printStackTrace();
                error.append(message);
                error.append('\n');
            }
        }
        c.close();
    }

    @Test
    public void test_SSLSocket_getEnabledCipherSuites_returnsCopies() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        assertNotSame(ssl.getEnabledCipherSuites(), ssl.getEnabledCipherSuites());
    }

    @Test
    public void test_SSLSocket_setEnabledCipherSuites_storesCopy() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        String[] array = new String[] {ssl.getEnabledCipherSuites()[0]};
        String originalFirstElement = array[0];
        ssl.setEnabledCipherSuites(array);
        array[0] = "Modified after having been set";
        assertEquals(originalFirstElement, ssl.getEnabledCipherSuites()[0]);
    }

    @Test
    public void test_SSLSocket_setEnabledCipherSuites() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        try {
            ssl.setEnabledCipherSuites(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
        try {
            ssl.setEnabledCipherSuites(new String[1]);
            fail();
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
        try {
            ssl.setEnabledCipherSuites(new String[] {"Bogus"});
            fail();
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
        ssl.setEnabledCipherSuites(new String[0]);
        ssl.setEnabledCipherSuites(ssl.getEnabledCipherSuites());
        ssl.setEnabledCipherSuites(ssl.getSupportedCipherSuites());
        // Check that setEnabledCipherSuites affects getEnabledCipherSuites
        String[] cipherSuites = new String[] {
                TestUtils.pickArbitraryNonTls13Suite(ssl.getSupportedCipherSuites())
        };
        ssl.setEnabledCipherSuites(cipherSuites);
        assertEquals(Arrays.asList(cipherSuites), Arrays.asList(ssl.getEnabledCipherSuites()));
    }

    @Test
    public void test_SSLSocket_setEnabledCipherSuites_TLS13() throws Exception {
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, null, null);
        SSLSocketFactory sf = context.getSocketFactory();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        // The TLS 1.3 cipher suites should be enabled by default
        assertTrue(new HashSet<String>(Arrays.asList(ssl.getEnabledCipherSuites()))
                .containsAll(StandardNames.CIPHER_SUITES_TLS13));
        // Disabling them should be ignored
        ssl.setEnabledCipherSuites(new String[0]);
        assertTrue(new HashSet<String>(Arrays.asList(ssl.getEnabledCipherSuites()))
                .containsAll(StandardNames.CIPHER_SUITES_TLS13));

        ssl.setEnabledCipherSuites(new String[] {
                TestUtils.pickArbitraryNonTls13Suite(ssl.getSupportedCipherSuites())
        });
        assertTrue(new HashSet<String>(Arrays.asList(ssl.getEnabledCipherSuites()))
                .containsAll(StandardNames.CIPHER_SUITES_TLS13));

        // Disabling TLS 1.3 should disable 1.3 cipher suites
        ssl.setEnabledProtocols(new String[] { "TLSv1.2" });
        assertFalse(new HashSet<String>(Arrays.asList(ssl.getEnabledCipherSuites()))
                .containsAll(StandardNames.CIPHER_SUITES_TLS13));
    }

    @Test
    public void test_SSLSocket_getSupportedProtocols_returnsCopies() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        assertNotSame(ssl.getSupportedProtocols(), ssl.getSupportedProtocols());
    }

    @Test
    public void test_SSLSocket_getEnabledProtocols_returnsCopies() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        assertNotSame(ssl.getEnabledProtocols(), ssl.getEnabledProtocols());
    }

    @Test
    public void test_SSLSocket_setEnabledProtocols_storesCopy() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        String[] array = new String[] {ssl.getEnabledProtocols()[0]};
        String originalFirstElement = array[0];
        ssl.setEnabledProtocols(array);
        array[0] = "Modified after having been set";
        assertEquals(originalFirstElement, ssl.getEnabledProtocols()[0]);
    }

    @Test
    public void test_SSLSocket_setEnabledProtocols() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        try {
            ssl.setEnabledProtocols(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
        try {
            ssl.setEnabledProtocols(new String[1]);
            fail();
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
        try {
            ssl.setEnabledProtocols(new String[] {"Bogus"});
            fail();
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
        ssl.setEnabledProtocols(new String[0]);
        ssl.setEnabledProtocols(ssl.getEnabledProtocols());
        ssl.setEnabledProtocols(ssl.getSupportedProtocols());
        // Check that setEnabledProtocols affects getEnabledProtocols
        for (String protocol : ssl.getSupportedProtocols()) {
            if ("SSLv2Hello".equals(protocol)) {
                try {
                    ssl.setEnabledProtocols(new String[] {protocol});
                    fail("Should fail when SSLv2Hello is set by itself");
                } catch (IllegalArgumentException expected) {
                    // Ignored.
                }
            } else {
                String[] protocols = new String[] {protocol};
                ssl.setEnabledProtocols(protocols);
                assertEquals(Arrays.deepToString(protocols),
                        Arrays.deepToString(ssl.getEnabledProtocols()));
            }
        }
    }

    /**
     * Tests that when the client has a hole in their supported protocol list, the
     * lower span of contiguous protocols is used in practice.
     */
    @Test
    public void test_SSLSocket_noncontiguousProtocols_useLower() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLContext serverContext = c.serverContext;
        SSLContext clientContext = c.clientContext;
        SSLSocket client = (SSLSocket)
                clientContext.getSocketFactory().createSocket(c.host, c.port);
        client.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1"});
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        server.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.1", "TLSv1"});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                server.startHandshake();
                return null;
            }
        });
        executor.shutdown();
        client.startHandshake();

        assertEquals("TLSv1", client.getSession().getProtocol());

        future.get();
        client.close();
        server.close();
        c.close();
    }

    /**
     * Tests that protocol negotiation succeeds when the highest-supported protocol
     * for both client and server isn't supported by the other.
     */
    @Test
    public void test_SSLSocket_noncontiguousProtocols_canNegotiate() throws Exception {
        TestSSLContext c = TestSSLContext.create();
        SSLContext serverContext = c.serverContext;
        SSLContext clientContext = c.clientContext;
        SSLSocket client = (SSLSocket)
                clientContext.getSocketFactory().createSocket(c.host, c.port);
        client.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1"});
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        server.setEnabledProtocols(new String[] {"TLSv1.1", "TLSv1"});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> future = executor.submit(new Callable<Void>() {
            @Override public Void call() throws Exception {
                server.startHandshake();
                return null;
            }
        });
        executor.shutdown();
        client.startHandshake();

        assertEquals("TLSv1", client.getSession().getProtocol());

        future.get();
        client.close();
        server.close();
        c.close();
    }

    @Test
    public void test_SSLSocket_getSession() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        SSLSession session = ssl.getSession();
        assertNotNull(session);
        assertFalse(session.isValid());
    }

    @Test
    public void test_SSLSocket_getHandshakeSession() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) sf.createSocket();
        SSLSession session = getHandshakeSession(socket);
        assertNull(session);
    }

    @Test
    public void test_SSLSocket_setUseClientMode_afterHandshake() throws Exception {
        // can't set after handshake
        TestSSLSocketPair pair = TestSSLSocketPair.create().connect();
        try {
            pair.server.setUseClientMode(false);
            fail();
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
        try {
            pair.client.setUseClientMode(false);
            fail();
        } catch (IllegalArgumentException expected) {
            // Ignored.
        }
    }

    @Test
    public void test_SSLSocket_untrustedServer() throws Exception {
        TestSSLContext c =
                TestSSLContext.create(TestKeyStore.getClientCA2(), TestKeyStore.getServer());
        SSLSocket client =
                (SSLSocket) c.clientContext.getSocketFactory().createSocket(c.host, c.port);
        final SSLSocket server = (SSLSocket) c.serverSocket.accept();
        Future<Void> future = runAsync(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    server.startHandshake();
                    fail();
                } catch (SSLHandshakeException expected) {
                    // Ignored.
                }
                return null;
            }
        });
        try {
            client.startHandshake();
            fail();
        } catch (SSLHandshakeException expected) {
            assertTrue(expected.getCause() instanceof CertificateException);
        }
        future.get();
        client.close();
        server.close();
        c.close();
    }

    @Test
    public void test_SSLSocket_getSSLParameters() throws Exception {
        TestUtils.assumeSetEndpointIdentificationAlgorithmAvailable();
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        SSLParameters p = ssl.getSSLParameters();
        assertNotNull(p);
        String[] cipherSuites = p.getCipherSuites();
        assertNotSame(cipherSuites, ssl.getEnabledCipherSuites());
        assertEquals(Arrays.asList(cipherSuites), Arrays.asList(ssl.getEnabledCipherSuites()));
        String[] protocols = p.getProtocols();
        assertNotSame(protocols, ssl.getEnabledProtocols());
        assertEquals(Arrays.asList(protocols), Arrays.asList(ssl.getEnabledProtocols()));
        assertEquals(p.getWantClientAuth(), ssl.getWantClientAuth());
        assertEquals(p.getNeedClientAuth(), ssl.getNeedClientAuth());
        assertNull(p.getEndpointIdentificationAlgorithm());
        p.setEndpointIdentificationAlgorithm(null);
        assertNull(p.getEndpointIdentificationAlgorithm());
        p.setEndpointIdentificationAlgorithm("HTTPS");
        assertEquals("HTTPS", p.getEndpointIdentificationAlgorithm());
        p.setEndpointIdentificationAlgorithm("FOO");
        assertEquals("FOO", p.getEndpointIdentificationAlgorithm());
    }

    @Test
    public void test_SSLSocket_setSSLParameters() throws Exception {
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sf.createSocket();
        String[] defaultCipherSuites = ssl.getEnabledCipherSuites();
        String[] defaultProtocols = ssl.getEnabledProtocols();
        String[] supportedCipherSuites = ssl.getSupportedCipherSuites();
        String[] supportedProtocols = ssl.getSupportedProtocols();
        {
            SSLParameters p = new SSLParameters();
            ssl.setSSLParameters(p);
            assertEquals(Arrays.asList(defaultCipherSuites),
                    Arrays.asList(ssl.getEnabledCipherSuites()));
            assertEquals(Arrays.asList(defaultProtocols), Arrays.asList(ssl.getEnabledProtocols()));
        }
        {
            SSLParameters p = new SSLParameters(supportedCipherSuites, supportedProtocols);
            ssl.setSSLParameters(p);
            assertEquals(Arrays.asList(supportedCipherSuites),
                    Arrays.asList(ssl.getEnabledCipherSuites()));
            assertEquals(
                    Arrays.asList(supportedProtocols), Arrays.asList(ssl.getEnabledProtocols()));
        }
        {
            SSLParameters p = new SSLParameters();
            p.setNeedClientAuth(true);
            assertFalse(ssl.getNeedClientAuth());
            assertFalse(ssl.getWantClientAuth());
            ssl.setSSLParameters(p);
            assertTrue(ssl.getNeedClientAuth());
            assertFalse(ssl.getWantClientAuth());
            p.setWantClientAuth(true);
            assertTrue(ssl.getNeedClientAuth());
            assertFalse(ssl.getWantClientAuth());
            ssl.setSSLParameters(p);
            assertFalse(ssl.getNeedClientAuth());
            assertTrue(ssl.getWantClientAuth());
            p.setWantClientAuth(false);
            assertFalse(ssl.getNeedClientAuth());
            assertTrue(ssl.getWantClientAuth());
            ssl.setSSLParameters(p);
            assertFalse(ssl.getNeedClientAuth());
            assertFalse(ssl.getWantClientAuth());
        }
    }

    @Test
    public void test_SSLSocket_setSoTimeout_basic() throws Exception {
        ServerSocket listening = new ServerSocket(0);
        Socket underlying = new Socket(listening.getInetAddress(), listening.getLocalPort());
        assertEquals(0, underlying.getSoTimeout());
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket wrapping = sf.createSocket(underlying, null, -1, false);
        assertEquals(0, wrapping.getSoTimeout());
        // setting wrapper sets underlying and ...
        int expectedTimeoutMillis = 1000; // 10 was too small because it was affected by rounding
        wrapping.setSoTimeout(expectedTimeoutMillis);
        // The kernel can round the requested value based on the HZ setting. We allow up to 10ms.
        assertTrue(Math.abs(expectedTimeoutMillis - wrapping.getSoTimeout()) <= 10);
        assertTrue(Math.abs(expectedTimeoutMillis - underlying.getSoTimeout()) <= 10);
        // ... getting wrapper inspects underlying
        underlying.setSoTimeout(0);
        assertEquals(0, wrapping.getSoTimeout());
        assertEquals(0, underlying.getSoTimeout());
    }

    @Test
    public void test_SSLSocket_setSoTimeout_wrapper() throws Exception {
        ServerSocket listening = new ServerSocket(0);
        // setSoTimeout applies to read, not connect, so connect first
        Socket underlying = new Socket(listening.getInetAddress(), listening.getLocalPort());
        Socket server = listening.accept();
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket clientWrapping = sf.createSocket(underlying, null, -1, false);
        underlying.setSoTimeout(1);
        try {
            @SuppressWarnings("unused")
            int value = clientWrapping.getInputStream().read();
            fail();
        } catch (SocketTimeoutException expected) {
            // Ignored.
        }
        clientWrapping.close();
        server.close();
        underlying.close();
        listening.close();
    }

    @Test
    public void test_TestSSLSocketPair_create() {
        TestSSLSocketPair test = TestSSLSocketPair.create().connect();
        assertNotNull(test.c);
        assertNotNull(test.server);
        assertNotNull(test.client);
        assertTrue(test.server.isConnected());
        assertTrue(test.client.isConnected());
        assertFalse(test.server.isClosed());
        assertFalse(test.client.isClosed());
        assertNotNull(test.server.getSession());
        assertNotNull(test.client.getSession());
        assertTrue(test.server.getSession().isValid());
        assertTrue(test.client.getSession().isValid());
        test.close();
    }

    @Test
    public void test_SSLSocket_ClientHello_cipherSuites() throws Exception {
        ForEachRunner.runNamed(new Callback<SSLSocketFactory>() {
            @Override
            public void run(SSLSocketFactory sslSocketFactory) throws Exception {
                ClientHello clientHello = SSLSocketTest.this
                    .captureTlsHandshakeClientHello(sslSocketFactory);
                final String[] cipherSuites;
                // RFC 5746 allows you to send an empty "renegotiation_info" extension *or*
                // a special signaling cipher suite. The TLS API has no way to check or
                // indicate that a certain TLS extension should be used.
                HelloExtension renegotiationInfoExtension =
                    clientHello.findExtensionByType(HelloExtension.TYPE_RENEGOTIATION_INFO);
                if (renegotiationInfoExtension != null
                    && renegotiationInfoExtension.data.length == 1
                    && renegotiationInfoExtension.data[0] == 0) {
                    cipherSuites = new String[clientHello.cipherSuites.size() + 1];
                    cipherSuites[clientHello.cipherSuites.size()] =
                        StandardNames.CIPHER_SUITE_SECURE_RENEGOTIATION;
                } else {
                    cipherSuites = new String[clientHello.cipherSuites.size()];
                }
                for (int i = 0; i < clientHello.cipherSuites.size(); i++) {
                    CipherSuite cipherSuite = clientHello.cipherSuites.get(i);
                    cipherSuites[i] = cipherSuite.getAndroidName();
                }
                StandardNames.assertDefaultCipherSuites(cipherSuites);
            }
        }, getSSLSocketFactoriesToTest());
    }

    @Test
    public void test_SSLSocket_ClientHello_supportedCurves() throws Exception {
        ForEachRunner.runNamed(new Callback<SSLSocketFactory>() {
            @Override
            public void run(SSLSocketFactory sslSocketFactory) throws Exception {
                ClientHello clientHello = SSLSocketTest.this
                    .captureTlsHandshakeClientHello(sslSocketFactory);
                EllipticCurvesHelloExtension ecExtension =
                    (EllipticCurvesHelloExtension) clientHello.findExtensionByType(
                        HelloExtension.TYPE_ELLIPTIC_CURVES);
                final String[] supportedCurves;
                if (ecExtension == null) {
                    supportedCurves = new String[0];
                } else {
                    assertTrue(ecExtension.wellFormed);
                    supportedCurves = new String[ecExtension.supported.size()];
                    for (int i = 0; i < ecExtension.supported.size(); i++) {
                        EllipticCurve curve = ecExtension.supported.get(i);
                        supportedCurves[i] = curve.toString();
                    }
                }
                StandardNames.assertDefaultEllipticCurves(supportedCurves);
            }
        }, getSSLSocketFactoriesToTest());
    }

    @Test
    public void test_SSLSocket_ClientHello_clientProtocolVersion() throws Exception {
        ForEachRunner.runNamed(new Callback<SSLSocketFactory>() {
            @Override
            public void run(SSLSocketFactory sslSocketFactory) throws Exception {
                ClientHello clientHello = SSLSocketTest.this
                    .captureTlsHandshakeClientHello(sslSocketFactory);
                assertEquals(TlsProtocolVersion.TLSv1_2, clientHello.clientVersion);
            }
        }, getSSLSocketFactoriesToTest());
    }

    @Test
    public void test_SSLSocket_ClientHello_compressionMethods() throws Exception {
        ForEachRunner.runNamed(new Callback<SSLSocketFactory>() {
            @Override
            public void run(SSLSocketFactory sslSocketFactory) throws Exception {
                ClientHello clientHello = SSLSocketTest.this
                    .captureTlsHandshakeClientHello(sslSocketFactory);
                assertEquals(Collections.singletonList(CompressionMethod.NULL),
                    clientHello.compressionMethods);
            }
        }, getSSLSocketFactoriesToTest());
    }

    private List<Pair<String, SSLSocketFactory>> getSSLSocketFactoriesToTest()
            throws NoSuchAlgorithmException, KeyManagementException {
        List<Pair<String, SSLSocketFactory>> result =
                new ArrayList<Pair<String, SSLSocketFactory>>();
        result.add(Pair.of("default", (SSLSocketFactory) SSLSocketFactory.getDefault()));
        for (String sslContextProtocol : StandardNames.SSL_CONTEXT_PROTOCOLS) {
            SSLContext sslContext = SSLContext.getInstance(sslContextProtocol);
            if (StandardNames.SSL_CONTEXT_PROTOCOLS_DEFAULT.equals(sslContextProtocol)) {
                continue;
            }
            sslContext.init(null, null, null);
            result.add(Pair.of("SSLContext(\"" + sslContext.getProtocol() + "\")",
                    sslContext.getSocketFactory()));
        }
        return result;
    }
    private ClientHello captureTlsHandshakeClientHello(SSLSocketFactory sslSocketFactory)
            throws Exception {
        TlsRecord record = captureTlsHandshakeFirstTlsRecord(sslSocketFactory);
        assertEquals("TLS record type", TlsProtocols.HANDSHAKE, record.type);
        ByteArrayInputStream fragmentIn = new ByteArrayInputStream(record.fragment);
        HandshakeMessage handshakeMessage = HandshakeMessage.read(new DataInputStream(fragmentIn));
        assertEquals(
                "HandshakeMessage type", HandshakeMessage.TYPE_CLIENT_HELLO, handshakeMessage.type);
        // Assert that the fragment does not contain any more messages
        assertEquals(0, fragmentIn.available());
        return (ClientHello) handshakeMessage;
    }
    private TlsRecord captureTlsHandshakeFirstTlsRecord(SSLSocketFactory sslSocketFactory)
            throws Exception {
        byte[] firstReceivedChunk = captureTlsHandshakeFirstTransmittedChunkBytes(sslSocketFactory);
        ByteArrayInputStream firstReceivedChunkIn = new ByteArrayInputStream(firstReceivedChunk);
        TlsRecord record = TlsRecord.read(new DataInputStream(firstReceivedChunkIn));
        // Assert that the chunk does not contain any more data
        assertEquals(0, firstReceivedChunkIn.available());
        return record;
    }
    @SuppressWarnings("FutureReturnValueIgnored")
    private byte[] captureTlsHandshakeFirstTransmittedChunkBytes(
            final SSLSocketFactory sslSocketFactory) throws Exception {
        // Since there's no straightforward way to obtain a ClientHello from SSLSocket, this test
        // does the following:
        // 1. Creates a listening server socket (a plain one rather than a TLS/SSL one).
        // 2. Creates a client SSLSocket, which connects to the server socket and initiates the
        //    TLS/SSL handshake.
        // 3. Makes the server socket accept an incoming connection on the server socket, and reads
        //    the first chunk of data received. This chunk is assumed to be the ClientHello.
        // NOTE: Steps 2 and 3 run concurrently.
        ServerSocket listeningSocket = null;
        // Some Socket operations are not interruptible via Thread.interrupt for some reason. To
        // work around, we unblock these sockets using Socket.close.
        final Socket[] sockets = new Socket[2];
        try {
            // 1. Create the listening server socket.
            listeningSocket = ServerSocketFactory.getDefault().createServerSocket(0);
            final ServerSocket finalListeningSocket = listeningSocket;
            // 2. (in background) Wait for an incoming connection and read its first chunk.
            final Future<byte[]> readFirstReceivedChunkFuture = runAsync(new Callable<byte[]>() {
                @Override
                public byte[] call() throws Exception {
                    Socket socket = finalListeningSocket.accept();
                    sockets[1] = socket;
                    try {
                        byte[] buffer = new byte[64 * 1024];
                        int bytesRead = socket.getInputStream().read(buffer);
                        if (bytesRead == -1) {
                            throw new EOFException("Failed to read anything");
                        }
                        return Arrays.copyOf(buffer, bytesRead);
                    } finally {
                        closeQuietly(socket);
                    }
                }
            });
            // 3. Create a client socket, connect it to the server socket, and start the TLS/SSL
            //    handshake.
            runAsync(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    Socket client = new Socket();
                    sockets[0] = client;
                    try {
                        client.connect(finalListeningSocket.getLocalSocketAddress());
                        // Initiate the TLS/SSL handshake which is expected to fail as soon as the
                        // server socket receives a ClientHello.
                        try {
                            SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(client,
                                    "localhost.localdomain", finalListeningSocket.getLocalPort(),
                                    true);
                            sslSocket.startHandshake();
                            fail();
                            return null;
                        } catch (IOException expected) {
                            // Ignored.
                        }
                        return null;
                    } finally {
                        closeQuietly(client);
                    }
                }
            });
            // Wait for the ClientHello to arrive
            return readFirstReceivedChunkFuture.get(10, TimeUnit.SECONDS);
        } finally {
            closeQuietly(listeningSocket);
            closeQuietly(sockets[0]);
            closeQuietly(sockets[1]);
        }
    }

    @Test
    public void test_SSLSocket_sendsTlsFallbackScsv_Fallback_Success() throws Exception {
        TestSSLContext context = TestSSLContext.create();
        final SSLSocket client = (SSLSocket) context.clientContext.getSocketFactory().createSocket(
                context.host, context.port);
        final SSLSocket server = (SSLSocket) context.serverSocket.accept();
        final String[] serverCipherSuites = server.getEnabledCipherSuites();
        final String[] clientCipherSuites = new String[serverCipherSuites.length + 1];
        System.arraycopy(serverCipherSuites, 0, clientCipherSuites, 0, serverCipherSuites.length);
        clientCipherSuites[serverCipherSuites.length] = StandardNames.CIPHER_SUITE_FALLBACK;
        Future<Void> s = runAsync(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                server.setEnabledProtocols(new String[]{"TLSv1.2"});
                server.setEnabledCipherSuites(serverCipherSuites);
                server.startHandshake();
                return null;
            }
        });
        Future<Void> c = runAsync(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                client.setEnabledProtocols(new String[]{"TLSv1.2"});
                client.setEnabledCipherSuites(clientCipherSuites);
                client.startHandshake();
                return null;
            }
        });
        s.get();
        c.get();
        client.close();
        server.close();
        context.close();
    }

    // Confirms that communication without the TLS_FALLBACK_SCSV cipher works as it always did.
    @Test
    public void test_SSLSocket_sendsNoTlsFallbackScsv_Fallback_Success() throws Exception {
        TestSSLContext context = TestSSLContext.create();
        final SSLSocket client = (SSLSocket) context.clientContext.getSocketFactory().createSocket(
                context.host, context.port);
        final SSLSocket server = (SSLSocket) context.serverSocket.accept();
        // Confirm absence of TLS_FALLBACK_SCSV.
        assertFalse(Arrays.asList(client.getEnabledCipherSuites())
                            .contains(StandardNames.CIPHER_SUITE_FALLBACK));
        Future<Void> s = runAsync(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                server.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1"});
                server.startHandshake();
                return null;
            }
        });
        Future<Void> c = runAsync(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                client.setEnabledProtocols(new String[]{"TLSv1.1"});
                client.startHandshake();
                return null;
            }
        });
        s.get();
        c.get();
        client.close();
        server.close();
        context.close();
    }

    private static void assertInappropriateFallbackIsCause(Throwable cause) {
        assertTrue(cause.getMessage(),
                cause.getMessage().contains("inappropriate fallback")
                        || cause.getMessage().contains("INAPPROPRIATE_FALLBACK"));
    }

    @Test
    public void test_SSLSocket_sendsTlsFallbackScsv_InappropriateFallback_Failure()
            throws Exception {
        TestSSLContext context = TestSSLContext.create();
        final SSLSocket client = (SSLSocket) context.clientContext.getSocketFactory().createSocket(
                context.host, context.port);
        final SSLSocket server = (SSLSocket) context.serverSocket.accept();
        final String[] serverCipherSuites = server.getEnabledCipherSuites();
        // Add TLS_FALLBACK_SCSV
        final String[] clientCipherSuites = new String[serverCipherSuites.length + 1];
        System.arraycopy(serverCipherSuites, 0, clientCipherSuites, 0, serverCipherSuites.length);
        clientCipherSuites[serverCipherSuites.length] = StandardNames.CIPHER_SUITE_FALLBACK;
        Future<Void> s = runAsync(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                server.setEnabledProtocols(new String[] {"TLSv1.1", "TLSv1"});
                server.setEnabledCipherSuites(serverCipherSuites);
                try {
                    server.startHandshake();
                    fail("Should result in inappropriate fallback");
                } catch (SSLHandshakeException expected) {
                    Throwable cause = expected.getCause();
                    assertEquals(SSLProtocolException.class, cause.getClass());
                    assertInappropriateFallbackIsCause(cause);
                }
                return null;
            }
        });
        Future<Void> c = runAsync(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                client.setEnabledProtocols(new String[]{"TLSv1"});
                client.setEnabledCipherSuites(clientCipherSuites);
                try {
                    client.startHandshake();
                    fail("Should receive TLS alert inappropriate fallback");
                } catch (SSLHandshakeException expected) {
                    Throwable cause = expected.getCause();
                    assertEquals(SSLProtocolException.class, cause.getClass());
                    assertInappropriateFallbackIsCause(cause);
                }
                return null;
            }
        });
        s.get();
        c.get();
        client.close();
        server.close();
        context.close();
    }

    private <T> Future<T> runAsync(Callable<T> callable) {
        return executor.submit(callable);
    }

    private static void readFully(InputStream in, byte[] dst) throws IOException {
        int offset = 0;
        int byteCount = dst.length;
        while (byteCount > 0) {
            int bytesRead = in.read(dst, offset, byteCount);
            if (bytesRead < 0) {
                throw new EOFException();
            }
            offset += bytesRead;
            byteCount -= bytesRead;
        }
    }

    private static SSLSession getHandshakeSession(SSLSocket socket) {
        try {
            Method method = socket.getClass().getMethod("getHandshakeSession");
            return (SSLSession) method.invoke(socket);
        } catch (Exception e) {
            return null;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Ignored.
            }
        }
    }

    private static void closeQuietly(ServerSocket serverSocket) {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
                // Ignored.
            }
        }
    }
}
