package com.github.netguard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.httptoolkit.android.vpn.ClientPacketWriter;
import tech.httptoolkit.android.vpn.SessionHandler;
import tech.httptoolkit.android.vpn.SessionManager;
import tech.httptoolkit.android.vpn.socket.SocketNIODataService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

class ProxyVpnRunnable implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ProxyVpnRunnable.class);

    private final Socket socket;

    // Packets from device apps downstream, heading upstream via this VPN
    private final InputStream vpnReadStream;

    private final ClientPacketWriter vpnPacketWriter;
    private final Thread vpnPacketWriterThread;

    private final SocketNIODataService nioService;
    private final Thread dataServiceThread;

    private final SessionHandler handler;

    private final ByteBuffer packet = ByteBuffer.allocate(65536);

    private final List<ProxyVpnRunnable> clients;

    ProxyVpnRunnable(Socket socket, List<ProxyVpnRunnable> clients) throws IOException {
        this.socket = socket;
        this.clients = clients;
        this.vpnReadStream = socket.getInputStream();

        // Packets from upstream servers, received by this VPN
        OutputStream vpnWriteStream = socket.getOutputStream();
        this.vpnPacketWriter = new ClientPacketWriter(vpnWriteStream);

        this.vpnPacketWriterThread = new Thread(vpnPacketWriter);
        this.nioService = new SocketNIODataService(vpnPacketWriter);
        this.dataServiceThread = new Thread(nioService, "Socket NIO thread: " + socket);
        SessionManager manager = new SessionManager();
        this.handler = new SessionHandler(manager, nioService, vpnPacketWriter);
    }

    private boolean running;

    public void run() {
        if (running) {
            log.warn("Vpn runnable started, but it's already running");
            return;
        }
        log.info("Vpn thread starting");

        dataServiceThread.start();
        vpnPacketWriterThread.start();

        running = true;
        while (running) {
            try {
                byte[] data = packet.array();

                int length = vpnReadStream.read(data);
                if (length > 0) {
                    try {
                        packet.limit(length);
                        handler.handlePacket(packet);
                    } catch (Exception e) {
                        log.error("handlePacket", e);
                    }

                    packet.clear();
                } else {
                    TimeUnit.MILLISECONDS.sleep(10);
                }
            } catch (InterruptedException e) {
                log.info("Sleep interrupted", e);
            } catch (IOException e) {
                log.info("Read interrupted", e);
            }
        }

        try {
            socket.close();
        } catch (IOException ignored) {
        }
        log.info("Vpn thread shutting down");

        clients.remove(this);
    }

    void stop() {
        if (running) {
            running = false;
            try {
                vpnReadStream.close();
            } catch (IOException ignored) {
            }
            nioService.shutdown();
            dataServiceThread.interrupt();

            vpnPacketWriter.shutdown();
            vpnPacketWriterThread.interrupt();
        } else {
            log.warn("Vpn runnable stopped, but it's not running");
        }
    }

}
