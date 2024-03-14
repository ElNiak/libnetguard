package eu.faircode.netguard;

import cn.hutool.core.io.IoUtil;
import com.github.netguard.ProxyVpn;
import com.github.netguard.vpn.InspectorVpn;
import com.github.netguard.vpn.ssl.RootCert;
import org.scijava.nativelib.NativeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketImpl;
import java.util.List;

public class ServiceSinkhole extends ProxyVpn implements InspectorVpn {

    private static final Logger log = LoggerFactory.getLogger(ServiceSinkhole.class);

    static {
        try {
            NativeLoader.loadLibrary("netguard");
        } catch (IOException ignored) {
        }
    }

    private static Method getImpl, getFileDescriptor;
    private static Field fdField;

    private final Socket socket;
    private final long jni_context;
    private final int fd;

    public ServiceSinkhole(Socket socket, List<ProxyVpn> clients, RootCert rootCert) {
        super(clients, rootCert);
        int mtu = jni_get_mtu();

        this.jni_context = jni_init(30);
        try {
            if (getImpl == null) {
                getImpl = Socket.class.getDeclaredMethod("getImpl");
                getImpl.setAccessible(true);
            }
            if (getFileDescriptor == null) {
                getFileDescriptor = SocketImpl.class.getDeclaredMethod("getFileDescriptor");
                getFileDescriptor.setAccessible(true);
            }
            if (fdField == null) {
                fdField = FileDescriptor.class.getDeclaredField("fd");
                fdField.setAccessible(true);
            }
            SocketImpl impl = (SocketImpl) getImpl.invoke(socket);
            FileDescriptor fileDescriptor = (FileDescriptor) getFileDescriptor.invoke(impl);
            if (!fileDescriptor.valid()) {
                throw new IllegalStateException("Invalid fd: " + fileDescriptor);
            }
            this.fd = (Integer) fdField.get(fileDescriptor);
        } catch (Exception e) {
            throw new IllegalStateException("init ServiceSinkhole", e);
        }
        this.socket = socket;
        final int ANDROID_LOG_DEBUG = 3;
        final int ANDROID_LOG_ERROR = 6;
        jni_start(jni_context, log.isTraceEnabled() ? ANDROID_LOG_DEBUG : ANDROID_LOG_ERROR);
        log.debug("mtu={}, socket={}, fd={}", mtu, socket, fd);
    }

    @Override
    protected synchronized void stop() {
        if (tunnelThread != null) {
            if (log.isDebugEnabled()) {
                log.debug("Stopping tunnel thread: context=0x{}, obj={}", Long.toHexString(jni_context), this);
            }

            jni_stop(jni_context);

            Thread thread = tunnelThread;
            if (thread != null) {
                try {
                    thread.join();
                } catch (InterruptedException ignored) {
                }
            }
            tunnelThread = null;

            log.debug("Stopped tunnel thread");
        }
    }

    private Thread tunnelThread;
    private DatagramSocket udp;

    @Override
    public String[] queryApplications(int hash) {
        DatagramSocket udp = this.udp;
        if (udp != null) {
            SocketAddress address = socket.getRemoteSocketAddress();
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                DataOutput dataOutput = new DataOutputStream(baos);
                dataOutput.writeByte(0x2);
                dataOutput.writeInt(hash);
                byte[] data = baos.toByteArray();
                log.debug("queryApplication address={}", address);
                udp.send(new DatagramPacket(data, data.length, address));

                byte[] buffer = new byte[1024];
                DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                udp.receive(datagramPacket);
                DataInput dataInput = new DataInputStream(new ByteArrayInputStream(buffer));
                if (dataInput.readUnsignedByte() == 0x2 &&
                        dataInput.readInt() == hash) {
                    int size = dataInput.readUnsignedByte();
                    if (size > 0) {
                        String[] applications = new String[size];
                        for (int i = 0; i < size; i++) {
                            applications[i] = dataInput.readUTF();
                        }
                        return applications;
                    }
                }
            } catch (IOException e) {
                log.debug("queryApplication address={}", address, e);
            }
        }
        return super.queryApplications(hash);
    }

    @Override
    public void run() {
        tunnelThread = Thread.currentThread();
        if (!directAllowAll) {
            try {
                udp = new DatagramSocket();
                udp.setSoTimeout(1500);
                byte[] data = new byte[]{0x8};
                SocketAddress address = socket.getRemoteSocketAddress();
                udp.send(new DatagramPacket(data, data.length, address));
                DatagramPacket ack = new DatagramPacket(data, data.length);
                udp.receive(ack);
                if (ack.getLength() != 1 || ack.getData()[0] != 0x8) {
                    throw new IllegalStateException("Ack failed: " + ack);
                } else {
                    log.debug("Udp ack ok.");
                }
            } catch (Exception e) {
                log.debug("create udp failed.", e);
                IoUtil.close(udp);
                udp = null;
            }
        }

        log.debug("Vpn thread starting");

        log.debug("Running tunnel");
        jni_run(jni_context, fd, true, 3);
        log.debug("Tunnel exited");
        IoUtil.close(udp);
        udp = null;

        IoUtil.close(socket);
        log.debug("Vpn thread shutting down");

        clients.remove(this);

        tunnelThread = null;

        jni_done(jni_context);

        if (packetCapture != null) {
            packetCapture.notifyFinish();
        }
    }

    private native long jni_init(int sdk);

    private native void jni_start(long context, int loglevel);

    private native void jni_run(long context, int tun, boolean fwd53, int rcode);

    private native void jni_stop(long context);

    @SuppressWarnings("unused")
    private native void jni_clear(long context);

    private native int jni_get_mtu();

    @SuppressWarnings("unused")
    private native int[] jni_get_stats(long context);

    @SuppressWarnings("unused")
    private static native void jni_pcap(String name, int record_size, int file_size);

    @SuppressWarnings("unused")
    private native void jni_socks5(String addr, int port, String username, String password);

    private native void jni_done(long context);


    // Called from native code
    @SuppressWarnings("unused")
    private void nativeExit(String reason) {
        log.debug("Native exit reason={}", reason);
    }

    // Called from native code
    @SuppressWarnings("unused")
    private void nativeError(int error, String message) {
        log.warn("Native error {}: {}", error, message);
    }

    // Called from native code
    @SuppressWarnings("unused")
    private void logPacket(Packet packet) {
         log.debug("logPacket packet {}, data={}", packet, packet.data);
    }

    // Called from native code
    @SuppressWarnings("unused")
    private void dnsResolved(ResourceRecord rr) {
        log.debug("dnsResolved rr={}", rr);
    }

    // Called from native code
    @SuppressWarnings("unused")
    private boolean isDomainBlocked(String name) {
        log.debug("check block domain name={}", name);
        return false;
    }

    // Called from native code
    @SuppressWarnings("unused")
    private int getUidQ(int version, int protocol, String saddr, int sport, String daddr, int dport) {
        if (protocol != TCP_PROTOCOL && protocol != UDP_PROTOCOL) {
            return -1;
        } else {
            return SYSTEM_UID;
        }
    }

    private boolean isSupported(int protocol) {
        return (protocol == 1 /* ICMPv4 */ ||
                protocol == 59 /* ICMPv6 */ ||
                protocol == TCP_PROTOCOL ||
                protocol == UDP_PROTOCOL);
    }

    private static final int IP_V4 = 4;
    private static final int IP_V6 = 6;
    private static final int SYSTEM_UID = 2000;
    private static final int TCP_PROTOCOL = 6;
    private static final int UDP_PROTOCOL = 17;

    // Called from native code
    @SuppressWarnings("unused")
    private Allowed isAddressAllowed(Packet packet) {
        if (directAllowAll) {
            return new Allowed();
        }
        DatagramSocket udp = this.udp;
        packet.allowed = false;
        if (packet.uid <= SYSTEM_UID && isSupported(packet.protocol)) {
            if (packet.version == IP_V4 && packet.protocol == TCP_PROTOCOL) { // ipv4
                if (udp != null) {
                    packet.sendAllowed(udp, socket.getRemoteSocketAddress());
                }
                return redirect(packet);
            }
            if (packet.version == IP_V4 && packet.protocol == UDP_PROTOCOL) {
                packet.allowed = packet.dport == 53; // DNS
            } else if(packet.version != IP_V6) { // Disallow ipv6
                packet.allowed = true;
            }
            log.debug("isAddressAllowed: packet={}, allowed={}", packet, packet.allowed);
        }

        if (udp != null && packet.allowed) {
            packet.sendAllowed(udp, socket.getRemoteSocketAddress());
        }

        return packet.allowed ? new Allowed() : null;
    }

    // Called from native code
    @SuppressWarnings("unused")
    private void accountUsage(Usage usage) {
         log.debug("accountUsage usage={}", usage);
    }

    // Called from native code
    @SuppressWarnings("unused")
    private void notifyPacket(int uid, byte[] packet) {
        if (packetCapture != null) {
            packetCapture.onPacket(packet, "NetGuard");
        }
    }

    // Called from native code
    @SuppressWarnings("unused")
    private boolean protect(int fd) {
        return true;
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }
}
