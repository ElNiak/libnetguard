package com.github.netguard.vpn.udp;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.IoUtil;
import com.github.netguard.Inspector;
import com.github.netguard.vpn.IPacketCapture;
import com.github.netguard.vpn.InspectorVpn;
import eu.faircode.netguard.Allowed;
import net.luminis.quic.core.Role;
import net.luminis.quic.core.Version;
import net.luminis.quic.core.VersionHolder;
import net.luminis.quic.crypto.Aead;
import net.luminis.quic.crypto.ConnectionSecrets;
import net.luminis.quic.frame.CryptoFrame;
import net.luminis.quic.frame.QuicFrame;
import net.luminis.quic.log.NullLogger;
import net.luminis.quic.log.SysOutLogger;
import net.luminis.quic.packet.InitialPacket;
import net.luminis.quic.receive.Receiver;
import net.luminis.tls.handshake.ClientHello;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Message;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.List;

public class UDProxy {

    private static final Logger log = LoggerFactory.getLogger(UDProxy.class);

    private static final int MTU = Receiver.MAX_DATAGRAM_SIZE;
    private static final int READ_TIMEOUT = 60000;

    public static Allowed redirect(InspectorVpn vpn, SocketAddress client, SocketAddress server) {
        log.debug("redirect client={}, server={}", client, server);
        try {
            UDProxy proxy = new UDProxy(vpn, client, server);
            return proxy.redirect();
        } catch (SocketException e) {
            throw new IllegalStateException("redirect", e);
        }
    }

    private final InspectorVpn vpn;
    private final SocketAddress clientAddress;
    private final SocketAddress serverAddress;
    private final DatagramSocket clientSocket;
    private final DatagramSocket serverSocket;

    private UDProxy(InspectorVpn vpn, SocketAddress clientAddress, SocketAddress serverAddress) throws SocketException {
        this.vpn = vpn;
        this.clientAddress = clientAddress;
        this.serverAddress = serverAddress;
        this.serverSocket = new DatagramSocket(new InetSocketAddress(0));
        this.serverSocket.setSoTimeout(READ_TIMEOUT);
        this.clientSocket = new DatagramSocket(new InetSocketAddress(0));
        this.clientSocket.setSoTimeout(READ_TIMEOUT);
        log.debug("UDProxy client={}, server={}, clientSocket={}, serverSocket={}", clientAddress, serverAddress, clientSocket.getLocalPort(), serverSocket.getLocalPort());

        Client client = new Client();
        Thread serverThread = new Thread(new Server(client), "UDProxy server " + clientAddress + " => " + serverAddress);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread clientThread = new Thread(client, "UDProxy client " + clientAddress + " => " + serverAddress);
        clientThread.setDaemon(true);
        clientThread.start();
    }

    private Allowed redirect() {
        return new Allowed("127.0.0.1", serverSocket.getLocalPort());
    }

    private boolean serverClosed;

    private class Server implements Runnable {
        private final Client client;
        Server(Client client) {
            this.client = client;
        }
        @Override
        public void run() {
            IPacketCapture packetCapture = vpn.getPacketCapture();
            DNSFilter dnsFilter = packetCapture == null ? null : packetCapture.getDNSFilter();
            try {
                byte[] buffer = new byte[MTU];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                boolean first = true;
                while (true) {
                    try {
                        serverSocket.receive(packet);
                        int length = packet.getLength();
                        if (log.isDebugEnabled()) {
                            byte[] data = new byte[length];
                            System.arraycopy(buffer, 0, data, 0, length);
                            log.debug("{}", Inspector.inspectString(data, "ServerReceived: " + clientAddress + " => " + serverAddress + ", base64=" + Base64.encode(data)));
                        }
                        if (first) {
                            client.forwardAddress = packet.getSocketAddress();
                            ClientHello clientHello = detectQuicClientHello(buffer, length);
                            log.debug("quic client hello: {}", clientHello);
                            client.dnsQuery = clientHello == null ? detectDnsQuery(buffer, length) : null;
                            log.trace("dnsQuery={}", client.dnsQuery);
                            Message fake;
                            if (client.dnsQuery != null && dnsFilter != null && (fake = dnsFilter.cancelDnsQuery(client.dnsQuery)) != null) {
                                log.trace("cancelDnsQuery: {}", fake);
                                byte[] fakeResponse = fake.toWire();
                                DatagramPacket fakePacket = new DatagramPacket(fakeResponse, fakeResponse.length);
                                fakePacket.setSocketAddress(client.forwardAddress);
                                serverSocket.send(fakePacket);
                                continue;
                            }
                        }
                        packet.setSocketAddress(serverAddress);
                        clientSocket.send(packet);
                    } catch (SocketTimeoutException e) {
                        break;
                    } catch (Exception e) {
                        log.warn("server", e);
                        break;
                    } finally {
                        first = false;
                    }
                }
            } finally {
                serverClosed = true;
                log.debug("udp proxy server exit: client={}, server={}", clientAddress, serverAddress);
            }
        }
        private Message detectDnsQuery(byte[] buffer, int length) {
            try {
                ByteBuffer bb = ByteBuffer.wrap(buffer);
                bb.limit(length);
                Message message = new Message(bb);
                if (!message.getSection(0).isEmpty()) {
                    return message;
                }
            } catch (IOException | BufferUnderflowException e) {
                log.trace("detectDnsQuery", e);
            } catch (Exception e) {
                log.warn("detectDnsQuery", e);
            }
            return null;
        }
        private ClientHello detectQuicClientHello(byte[] buffer, int length) {
            try {
                ByteBuffer bb = ByteBuffer.wrap(buffer);
                bb.limit(length);
                bb.mark();
                if (bb.remaining() < 0x20) {
                    return null;
                }
                int flags = bb.get() & 0xff;
                if ((flags & 0x40) != 0x40) {
                    return null;
                }
                if ((flags & 0x80) == 0x80) {
                    int version = bb.getInt();
                    if (log.isDebugEnabled()) {
                        log.debug("detectQuicClientHello flags=0x{}, version=0x{}", Integer.toHexString(flags), Integer.toHexString(version));
                    }
                    if (version == Version.QUIC_version_1.getId()) {
                        Version quicVersion = Version.parse(version);
                        int dcidLength = bb.get() & 0xff;
                        byte[] dcid = new byte[dcidLength];
                        bb.get(dcid);
                        int type = (flags & 0x30) >> 4;
                        if (InitialPacket.isInitial(type, quicVersion)) {
                            InitialPacket initialPacket = new InitialPacket(quicVersion);
                            ConnectionSecrets connectionSecrets = new ConnectionSecrets(VersionHolder.with(quicVersion), Role.Server, null, new NullLogger());
                            connectionSecrets.computeInitialKeys(dcid);

                            bb.reset();
                            Aead aead = connectionSecrets.getPeerAead(initialPacket.getEncryptionLevel());
                            net.luminis.quic.log.Logger logger;
                            if (log.isDebugEnabled()) {
                                logger = new SysOutLogger();
                                logger.logDebug(true);
                            } else {
                                logger = new NullLogger();
                            }
                            initialPacket.parse(bb, aead, 0, logger, 0);
                            log.debug("detectQuicClientHello initialPacket={}", initialPacket);
                            List<QuicFrame> frameList = initialPacket.getFrames();
                            QuicFrame firstFrame;
                            if (!frameList.isEmpty() && (firstFrame = frameList.get(0)) instanceof CryptoFrame) {
                                CryptoFrame cryptoFrame = (CryptoFrame) firstFrame;
                                ClientHello clientHello = new ClientHello(ByteBuffer.wrap(cryptoFrame.getStreamData()), null);
                                if (log.isDebugEnabled()) {
                                    log.debug("{}", Inspector.inspectString(cryptoFrame.getStreamData(), "detectQuicClientHello initialPacket.cryptoFrame"));
                                }
                                return clientHello;
                            } else {
                                log.warn("detectQuicClientHello frameList={}", frameList);
                            }
                        } else {
                            log.warn("detectQuicClientHello type={}", type);
                        }
                    } else if (version == Version.QUIC_version_2.getId() ||
                            version == Version.IETF_draft_27.getId() ||
                            version == Version.IETF_draft_29.getId()) {
                        log.warn("detectQuicClientHello version={}", version);
                    }
                } else {
                    log.warn("detectQuicClientHello flags=0x{}", Integer.toHexString(flags));
                }
            } catch(Exception e) {
                log.warn("detectQuicClientHello", e);
            }
            return null;
        }
    }

    private class Client implements Runnable {
        private SocketAddress forwardAddress;
        private Message dnsQuery;
        @Override
        public void run() {
            IPacketCapture packetCapture = vpn.getPacketCapture();
            DNSFilter dnsFilter = packetCapture == null ? null : packetCapture.getDNSFilter();
            try {
                byte[] buffer = new byte[MTU];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (true) {
                    try {
                        clientSocket.receive(packet);
                        int length = packet.getLength();
                        if (log.isDebugEnabled()) {
                            byte[] data = new byte[length];
                            System.arraycopy(buffer, 0, data, 0, length);
                            log.debug("{}", Inspector.inspectString(data, "ClientReceived: " + clientAddress + " => " + serverAddress));
                        }
                        if (forwardAddress == null) {
                            throw new IllegalStateException("vpnAddress is null");
                        }
                        if (dnsQuery != null) {
                            try {
                                ByteBuffer bb = ByteBuffer.wrap(buffer);
                                bb.limit(length);
                                Message dnsResponse = new Message(bb);
                                log.trace("client={}, server={}, dnsQuery={}\ndnsResponse={}", clientAddress, serverAddress, dnsQuery, dnsResponse);

                                if (dnsFilter != null) {
                                    Message fake = dnsFilter.filterDnsResponse(dnsQuery, dnsResponse);
                                    if (fake != null) {
                                        log.trace("filterDnsResponse: {}", fake);
                                        byte[] fakeResponse = fake.toWire();
                                        DatagramPacket fakePacket = new DatagramPacket(fakeResponse, fakeResponse.length);
                                        fakePacket.setSocketAddress(forwardAddress);
                                        serverSocket.send(fakePacket);
                                        continue;
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("decode dns response, query={}", dnsQuery, e);
                            }
                        }
                        packet.setSocketAddress(forwardAddress);
                        serverSocket.send(packet);
                    } catch (SocketTimeoutException e) {
                        if (serverClosed) {
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("client", e);
                        break;
                    }
                }
            } finally {
                IoUtil.close(serverSocket);
                IoUtil.close(clientSocket);
                log.debug("udp proxy client exit: client={}, server={}", clientAddress, serverAddress);
            }
        }
    }

}
