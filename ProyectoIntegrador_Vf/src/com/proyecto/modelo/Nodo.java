package com.proyecto.modelo;

public class Nodo {/**
 * Representa un equipo conectado a la plataforma.
 * 
 * Esta clase permite aplicar encapsulamiento y centralizar
 * la información básica de cada nodo de la red.
 */


    private final int id;
    private final String ip;
    private final String sistemaOperativo;
    private final String usuario;

    private boolean conectado;
    private volatile Metrica ultimaMetrica;

    /**
     * Lecturas recientes para la gráfica en vivo. Con el intervalo por defecto
     * del agente (10 s), 120 lecturas cubren 20 minutos.
     */
    public static final int CAPACIDAD_HISTORIAL = 120;
    private final java.util.ArrayDeque<Metrica> historial = new java.util.ArrayDeque<>();

    /** Última latencia medida del canal servidor-agente (ms); -1 = sin medir. */
    private volatile double latenciaMs = -1;

    public Nodo(int id, String ip, String sistemaOperativo, String usuario) {
        this.id = id;
        this.ip = ip;
        this.sistemaOperativo = sistemaOperativo;
        this.usuario = usuario;
        this.conectado = true;
    }

    public int getId() {
        return id;
    }

    public String getIp() {
        return ip;
    }

    public String getSistemaOperativo() {
        return sistemaOperativo;
    }

    public String getUsuario() {
        return usuario;
    }

    public boolean estaConectado() {
        return conectado;
    }

    public void conectar() {
        this.conectado = true;
    }

    public void desconectar() {
        this.conectado = false;
    }

    public Metrica getUltimaMetrica() {
        return ultimaMetrica;
    }

    /** Guarda la lectura como la última y la añade al historial (lo llama el hilo del nodo). */
    public void actualizarMetrica(Metrica metrica) {
        synchronized (historial) {
            this.ultimaMetrica = metrica;
            historial.addLast(metrica);
            while (historial.size() > CAPACIDAD_HISTORIAL) {
                historial.removeFirst();
            }
        }
    }

    /** Copia del historial, de la lectura más antigua a la más reciente. */
    public java.util.List<Metrica> getHistorial() {
        synchronized (historial) {
            return new java.util.ArrayList<>(historial);
        }
    }

    public double getLatenciaMs() {
        return latenciaMs;
    }

    public boolean tieneLatencia() {
        return latenciaMs >= 0;
    }

    public void setLatenciaMs(double latenciaMs) {
        this.latenciaMs = latenciaMs;
    }

    /** Alertas activas reportadas por el monitoreo continuo del agente (CPU, RAM, ...). */
    private final java.util.Set<String> alertasActivas =
            java.util.Collections.synchronizedSet(new java.util.TreeSet<>());

    public void marcarAlerta(String metrica, boolean activa) {
        if (activa) {
            alertasActivas.add(metrica);
        } else {
            alertasActivas.remove(metrica);
        }
    }

    public int getCantidadAlertas() {
        return alertasActivas.size();
    }

    /** Ej.: "CPU, RAM"; cadena vacía si no hay alertas. */
    public String resumenAlertas() {
        synchronized (alertasActivas) {
            return String.join(", ", alertasActivas);
        }
    }

    public String getNombreVisible() {
        return usuario + " @ " + ip;
    }

    @Override
    public String toString() {
        return String.format(
                "Nodo #%03d - %s - %s",
                id,
                ip,
                conectado ? "ONLINE" : "OFFLINE"
        );
    }
}

