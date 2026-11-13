package com.proyecto.servidor;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class EscaneadorRed {

    public interface EscaneoListener {
        void onDispositivoEncontrado(String ip, String hostName, long latenciaMs);
        void onEscaneoCompletado(int totalEncontrados);
    }

    public static void escanearSubred(String rangoBaseIP, EscaneoListener listener) {
        // Hilos en paralelo para escanear las 254 IPs en 2-3 segundos
        ExecutorService executor = Executors.newFixedThreadPool(50);
        int[] contadorEncontrados = {0};

        // Extraer base de la IP (ejemplo: "192.168.1.0/24" -> "192.168.1.")
        String base = rangoBaseIP.contains("/") ? rangoBaseIP.split("/")[0] : rangoBaseIP;
        int ultimoPunto = base.lastIndexOf('.');
        if (ultimoPunto != -1) {
            base = base.substring(0, ultimoPunto + 1);
        } else {
            base = "192.168.1.";
        }

        String prefijoIP = base;

        for (int i = 1; i <= 254; i++) {
            final String ipProbar = prefijoIP + i;
            executor.submit(() -> {
                try {
                    long tiempoInicio = System.currentTimeMillis();
                    InetAddress address = InetAddress.getByName(ipProbar);
                    
                    // Comprobar si responde a ICMP / Echo (Timeout 300 ms)
                    if (address.isReachable(300)) {
                        long latencia = System.currentTimeMillis() - tiempoInicio;
                        synchronized (contadorEncontrados) {
                            contadorEncontrados[0]++;
                        }
                        listener.onDispositivoEncontrado(ipProbar, address.getHostName(), latencia);
                    }
                } catch (Exception ignored) {}
            });
        }

        executor.shutdown();
        new Thread(() -> {
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
                listener.onEscaneoCompletado(contadorEncontrados[0]);
            } catch (InterruptedException e) {
                listener.onEscaneoCompletado(0);
            }
        }).start();
    }
}
