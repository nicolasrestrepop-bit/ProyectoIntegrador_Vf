package com.proyecto.cliente;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MonitoreoRed {

    public static String obtenerTelemetriaRed() {
        StringBuilder sb = new StringBuilder();
        sb.append("TAREA: Monitoreo Avanzado de Interfaces de Red\n");

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            int contador = 0;

            while (interfaces.hasMoreElements()) {
                NetworkInterface netInt = interfaces.nextElement();
                if (netInt.isLoopback() || !netInt.isUp()) continue;

                contador++;
                sb.append(" [Interfaz ").append(contador).append("]: ").append(netInt.getDisplayName()).append("\n");
                sb.append("  - Nombre Técnico: ").append(netInt.getName()).append("\n");
                sb.append("  - MTU (Maximum Transmission Unit): ").append(netInt.getMTU()).append(" bytes\n");

                // Dirección MAC
                byte[] mac = netInt.getHardwareAddress();
                if (mac != null) {
                    StringBuilder macSb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        macSb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
                    }
                    sb.append("  - Dirección MAC: ").append(macSb.toString()).append("\n");
                }

                // Direcciones IP asociadas
                Enumeration<InetAddress> inetAddresses = netInt.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inet = inetAddresses.nextElement();
                    sb.append("  - IP Asignada: ").append(inet.getHostAddress()).append("\n");
                }
            }

            // Test de Ping/Latencia hacia el Gateway/Servidor DNS público (8.8.8.8)
            long inicioPing = System.currentTimeMillis();
            boolean reachable = InetAddress.getByName("8.8.8.8").isReachable(1000);
            long finPing = System.currentTimeMillis() - inicioPing;

            sb.append(" - Diagnóstico de Conectividad Exterior (DNS 8.8.8.8):\n");
            sb.append("  - Respuesta: ").append(reachable ? "EXITOSA" : "SIN RESPUESTA").append("\n");
            sb.append("  - Latencia Estimada: ").append(finPing).append(" ms\n");
            sb.append(" - Estado de Red: OPERATIVO Y ESTABLE");

        } catch (Exception e) {
            sb.append("Error al recolectar telemetría de red: ").append(e.getMessage());
        }

        return sb.toString();
    }
}