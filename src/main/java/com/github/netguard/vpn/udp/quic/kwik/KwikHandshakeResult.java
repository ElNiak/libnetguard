package com.github.netguard.vpn.udp.quic.kwik;

import com.github.netguard.vpn.InspectorVpn;
import com.github.netguard.vpn.tcp.ServerCertificate;
import com.github.netguard.vpn.tcp.h2.Http2Filter;
import com.github.netguard.vpn.tcp.h2.Http2Session;
import com.github.netguard.vpn.udp.quic.HandshakeResult;
import com.github.netguard.vpn.udp.quic.QuicServer;
import net.luminis.quic.QuicClientConnection;
import net.luminis.quic.log.NullLogger;
import net.luminis.quic.server.ServerConnectionConfig;
import net.luminis.quic.server.ServerConnector;
import net.luminis.tls.handshake.TlsServerEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;

class KwikHandshakeResult implements HandshakeResult {

    private static final Logger log = LoggerFactory.getLogger(KwikHandshakeResult.class);

    private final X509Certificate peerCertificate;
    private final String handshakeApplicationProtocol;
    private final Http2Session session;
    private final QuicClientConnection connection;

    KwikHandshakeResult(X509Certificate peerCertificate, String handshakeApplicationProtocol,
                        Http2Session session, QuicClientConnection connection) {
        this.peerCertificate = peerCertificate;
        this.handshakeApplicationProtocol = handshakeApplicationProtocol;
        this.session = session;
        this.connection = connection;
    }

    @Override
    public QuicServer startServer(InspectorVpn vpn, Http2Filter http2Filter) throws Exception {
        if (handshakeApplicationProtocol == null || handshakeApplicationProtocol.isBlank()) {
            throw new IllegalStateException("handshakeApplicationProtocol=" + handshakeApplicationProtocol);
        }
        ServerCertificate serverCertificate = new ServerCertificate(peerCertificate);
        ServerConnectionConfig serverConnectionConfig = ServerConnectionConfig.builder()
                .maxOpenPeerInitiatedBidirectionalStreams(Short.MAX_VALUE)
                .maxOpenPeerInitiatedUnidirectionalStreams(Short.MAX_VALUE)
                .build();
        ServerConnector.Builder builder = ServerConnector.builder();
        ServerCertificate.ServerContext serverContext = serverCertificate.getServerContext(vpn.getRootCert());
        TlsServerEngineFactory tlsServerEngineFactory = serverContext.newTlsServerEngineFactory();
        builder.withTlsServerEngineFactory(tlsServerEngineFactory);
        net.luminis.quic.log.Logger serverLogger;
        if (log.isDebugEnabled()) {
            serverLogger = new PrintStreamLogger(System.out);
            serverLogger.logDebug(true);
        } else {
            serverLogger = new NullLogger();
        }
        DatagramSocket socket = new DatagramSocket(0);
        ServerConnector serverConnector = builder
                .withPort(443)
                .withSocket(socket)
                .withConfiguration(serverConnectionConfig)
                .withLogger(serverLogger)
                .build();
        serverConnector.start();
        int listenPort = socket.getLocalPort();
        log.debug("handshakeApplicationProtocol={}, listenPort={}, filterHttp3={}", handshakeApplicationProtocol, listenPort, http2Filter);
        serverConnector.registerApplicationProtocol(handshakeApplicationProtocol, new KwikProxy(vpn.getExecutorService(), connection, session, http2Filter));
        InetSocketAddress forwardAddress = new InetSocketAddress("127.0.0.1", listenPort);
        return new KwikServer(serverConnector, forwardAddress, tlsServerEngineFactory);
    }

    @Override
    public String toString() {
        return "KwikHandshakeResult{" +
                "peerCertificate=" + peerCertificate +
                ", handshakeApplicationProtocol='" + handshakeApplicationProtocol + '\'' +
                '}';
    }

}
