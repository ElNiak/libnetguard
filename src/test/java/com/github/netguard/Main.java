package com.github.netguard;

import eu.faircode.netguard.ServiceSinkhole;

import java.io.IOException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws IOException {
        try {
            ServiceSinkhole serviceSinkhole = new ServiceSinkhole();
            System.out.println(serviceSinkhole);
        } catch (Exception e) {
            e.printStackTrace();
        }

        VpnServer vpnServer = new VpnServer();
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

}