package com.github.netguard;

import com.github.netguard.handler.PacketDecoder;
import com.github.netguard.vpn.IPacketCapture;
import com.github.netguard.vpn.Vpn;
import com.github.netguard.vpn.VpnListener;
import com.github.netguard.vpn.ssl.SSLProxyV2;
import com.github.netguard.vpn.ssl.h2.Http2Filter;
import com.github.netguard.vpn.ssl.h2.Http2SessionKey;
import com.twitter.http2.HttpFrameForward;
import eu.faircode.netguard.ServiceSinkhole;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.krakenapps.pcap.decoder.http.HttpDecoder;

import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws IOException {
        Logger.getLogger(ServiceSinkhole.class).setLevel(Level.INFO);
        Logger.getLogger(SSLProxyV2.class).setLevel(Level.INFO);
        Logger.getLogger(PacketDecoder.class).setLevel(Level.INFO);
        Logger.getLogger(HttpDecoder.class).setLevel(Level.INFO);
        Logger.getLogger(HttpFrameForward.class).setLevel(Level.INFO);
        Logger.getLogger("edu.baylor.cs.csi5321.spdy.frames").setLevel(Level.INFO);
        VpnServer vpnServer = new VpnServer();
        vpnServer.enableBroadcast(10);
        vpnServer.setVpnListener(new MyVpnListener());
        vpnServer.start();

        System.out.println("vpn server listen on: " + vpnServer.getPort());
        Scanner scanner = new Scanner(System.in);
        String cmd;
        while ((cmd = scanner.nextLine()) != null) {
            if ("q".equals(cmd) || "exit".equals(cmd)) {
                break;
            }
        }
        vpnServer.shutdown();
    }

    private static class MyVpnListener implements VpnListener, Http2Filter {
        @Override
        public void onConnectClient(Vpn vpn) {
            IPacketCapture packetCapture = new PacketDecoder() {
                @Override
                public Http2Filter getH2Filter() {
                    return MyVpnListener.this;
                }
            };
            vpn.setPacketCapture(packetCapture);
        }
        @Override
        public boolean acceptHost(String hostName) {
            if (hostName.endsWith("weixin.qq.com")) {
                return true;
            } else {
                System.out.println("NOT filter http2 host=" + hostName);
                return false;
            }
        }
        @Override
        public byte[] filterRequest(Http2SessionKey sessionKey, HttpRequest request, HttpHeaders headers, byte[] requestData) {
            Inspector.inspect(requestData, "filterRequest sessionKey=" + sessionKey + ", request=" + request);
            return requestData;
        }
        @Override
        public byte[] filterResponse(Http2SessionKey sessionKey, HttpResponse response, HttpHeaders headers, byte[] responseData) {
            Inspector.inspect(responseData, "sessionKey session=" + sessionKey + ", response=" + response);
            return responseData;
        }

        @Override
        public byte[] filterPollingRequest(Http2SessionKey sessionKey, HttpRequest request, byte[] requestData, boolean newStream) {
            return requestData;
        }

        @Override
        public byte[] filterPollingResponse(Http2SessionKey sessionKey, HttpResponse response, byte[] responseData, boolean endStream) {
            return responseData;
        }
    }

}