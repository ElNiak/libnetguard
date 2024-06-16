package com.github.netguard;

import com.github.netguard.vpn.ClientOS;
import com.github.netguard.vpn.IPacketCapture;
import com.github.netguard.vpn.InspectorVpn;
import com.github.netguard.vpn.tcp.RootCert;
import com.github.netguard.vpn.tcp.SSLProxyV2;
import com.github.netguard.vpn.udp.UDProxy;
import eu.faircode.netguard.Allowed;
import eu.faircode.netguard.Application;
import eu.faircode.netguard.Packet;
import net.luminis.quic.concurrent.DaemonThreadFactory;

import java.io.DataInput;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class ProxyVpn implements Runnable, InspectorVpn {

    protected final ExecutorService executorService = Executors.newCachedThreadPool(new DaemonThreadFactory(getClass().getSimpleName()));

    protected static ClientOS readOS(DataInput vpnReadStream) throws IOException {
        int os = vpnReadStream.readUnsignedByte();
        switch (os) {
            case 0:
                return ClientOS.Android;
            case 1:
                return ClientOS.iOS;
            case 2:
                return ClientOS.MacOS;
            default:
                throw new IOException("Invalid os=" + os);
        }
    }

    protected final List<ProxyVpn> clients;

    private final RootCert rootCert;

    protected ProxyVpn(List<ProxyVpn> clients, RootCert rootCert) {
        this.clients = clients;
        this.rootCert = rootCert;
    }

    @Override
    public RootCert getRootCert() {
        return rootCert;
    }

    @Override
    public ExecutorService getExecutorService() {
        return executorService;
    }

    protected ClientOS clientOS = ClientOS.MacOS;

    @Override
    public ClientOS getClientOS() {
        return clientOS;
    }

    @Override
    public Application[] queryApplications(int hash) {
        return new Application[0];
    }

    @Override
    public boolean isTransparentProxying() {
        return false;
    }

    protected abstract void stop();

    protected IPacketCapture packetCapture;

    @Override
    public final void setPacketCapture(IPacketCapture packetCapture) {
        this.packetCapture = packetCapture;
    }

    @Override
    public final IPacketCapture getPacketCapture() {
        return packetCapture;
    }

    protected boolean directAllowAll;

    @Override
    public void setDirectAllowAll() {
        directAllowAll = true;
    }

    protected final Allowed redirectTcp(Packet packet) {
        int timeout = 10000; // default 10 seconds;
        return SSLProxyV2.create(this, packet, timeout);
    }

    protected final Allowed redirectUdp(Packet packet) {
        return UDProxy.redirect(this, packet.createClientAddress(), packet.createServerAddress());
    }

}
