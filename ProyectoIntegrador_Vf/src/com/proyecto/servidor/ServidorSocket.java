package com.proyecto.servidor;

import com.proyecto.seguridad.ConfiguracionSeguridad;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Acepta las conexiones entrantes de los agentes.
 *
 * Recibe el ServerSocket ya abierto (con TLS, ver CanalSeguro.crearServidor) para
 * que cualquier problema de certificados o de puerto ocupado se detecte al arrancar
 * y no quede un servidor "encendido" que en realidad no escucha.
 */
public class ServidorSocket implements Runnable {

    private final ServerSocket servidorSocket;
    private final int maxClientes;
    private final AtomicInteger siguienteIdNodo;
    private final ServidorCentral servidor;
    private final ConfiguracionSeguridad seguridad;

    public ServidorSocket(
            ServerSocket servidorSocket,
            int maxClientes,
            AtomicInteger siguienteIdNodo,
            ServidorCentral servidor,
            ConfiguracionSeguridad seguridad) {

        this.servidorSocket = servidorSocket;
        this.maxClientes = maxClientes;
        this.siguienteIdNodo = siguienteIdNodo;
        this.servidor = servidor;
        this.seguridad = seguridad;
    }

    @Override
    public void run() {
        servidor.agregarLog(
                "Servidor Central iniciado. Puerto de escucha de Sockets: "
                        + servidorSocket.getLocalPort()
                        + " (canal cifrado con TLS y token de acceso)"
        );

        try (ServerSocket socketEscucha = servidorSocket) {

            while (true) {

                Socket socketCliente =
                        socketEscucha.accept();

                if (servidor.getCantidadNodos() >= maxClientes) {

                    servidor.agregarLog(
                            "Conexión rechazada: límite de "
                                    + maxClientes
                                    + " clientes alcanzado."
                    );

                    socketCliente.close();
                    continue;
                }

                // El id del nodo se asigna dentro del manejador, después de autenticar.
                ManejadorCliente manejador =
                        new ManejadorCliente(
                                socketCliente,
                                siguienteIdNodo,
                                servidor,
                                seguridad
                        );

                new Thread(manejador).start();
            }

        } catch (IOException e) {

            servidor.agregarLog(
                    "Error crítico de servidor: "
                            + e.getMessage()
            );
        }
    }
}
