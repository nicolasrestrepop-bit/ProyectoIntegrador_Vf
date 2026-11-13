package com.proyecto.servidor;

import com.proyecto.modelo.Metrica;
import com.proyecto.modelo.Nodo;
import com.proyecto.seguridad.CanalSeguro;
import com.proyecto.seguridad.ConfiguracionSeguridad;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestiona la comunicación entre el servidor y un nodo cliente.
 *
 * Cada objeto ManejadorCliente se ejecuta en su propio hilo.
 */
public class ManejadorCliente implements Runnable {

    private final Socket socket;
    private final AtomicInteger generadorIds;
    private final ServidorCentral servidor;
    private final ConfiguracionSeguridad seguridad;

    // El id se asigna recién cuando el agente supera el saludo (TLS + token), para
    // que los intentos rechazados no consuman números de nodo.
    private volatile int idCliente;

    /** Sondeo de latencia: el agente lo responde al instante (ver AgenteCliente). */
    public static final String CMD_LATENCIA = "@@LAT";

    /** Respuesta del agente al sondeo de latencia (sí va en la cola de respuestas). */
    private static final String RESPUESTA_LATENCIA = "@@PONG";

    private static final long MARCA_COMANDO = Long.MIN_VALUE;

    private DataOutputStream salida;
    private Nodo nodo;

    // El agente responde en orden, una respuesta por cada envío. Esta cola
    // recuerda qué se envió para saber si una respuesta es de un comando de
    // mantenimiento (MARCA_COMANDO) o de un sondeo (nanoTime de su envío).
    // También sirve de cerrojo: serializa los envíos (EDT + hilo de muestreo).
    private final ArrayDeque<Long> pendientes = new ArrayDeque<>();

    public ManejadorCliente(
            Socket socket,
            AtomicInteger generadorIds,
            ServidorCentral servidor,
            ConfiguracionSeguridad seguridad) {

        this.socket = socket;
        this.generadorIds = generadorIds;
        this.servidor = servidor;
        this.seguridad = seguridad;
    }

    @Override
    public void run() {

        String ipCliente =
                socket.getInetAddress().getHostAddress();

        boolean registrado = false;

        try (
                DataInputStream entrada =
                        new DataInputStream(socket.getInputStream());

                DataOutputStream salidaStream =
                        new DataOutputStream(socket.getOutputStream())
        ) {

            // Saludo seguro: negociación TLS + token. Si falla, el equipo ni siquiera
            // llega a registrarse como nodo.
            String[] identidad = realizarSaludo(entrada, salidaStream, ipCliente);
            if (identidad == null) {
                return;
            }

            String sistemaOperativo = identidad[0];
            String usuario = identidad[1];

            idCliente = generadorIds.getAndIncrement();
            this.salida = salidaStream;

            // Crear el objeto Nodo.
            nodo = new Nodo(
                    idCliente,
                    ipCliente,
                    sistemaOperativo,
                    usuario
            );

            registrado = true;
            servidor.registrarCliente(
                    idCliente,
                    this,
                    nodo
            );

            servidor.agregarLog(
                    "Conexión establecida con "
                    + nodo
            );

            while (!socket.isClosed()) {

                String respuesta =
                        entrada.readUTF();

                // Mensajes no solicitados del agente (alertas): empiezan con "@@" y NO
                // son respuesta a nada, así que no deben consumir la cola 'pendientes'.
                if (respuesta.startsWith("@@") && !RESPUESTA_LATENCIA.equals(respuesta)) {
                    procesarMensajeNoSolicitado(respuesta);
                    continue;
                }

                Long marca;
                synchronized (pendientes) {
                    marca = pendientes.pollFirst();
                }

                // Respuesta a un sondeo: solo actualiza la latencia (sin log).
                if (marca != null && marca != MARCA_COMANDO) {
                    double ms = (System.nanoTime() - marca) / 1_000_000.0;
                    nodo.setLatenciaMs(ms);
                    servidor.actualizarLatencia(idCliente, ms);
                    continue;
                }

                servidor.agregarLog(
                        "RESPUESTA DEL NODO #"
                        + idCliente
                        + ":\n"
                        + respuesta
                );
            }

        } catch (IOException e) {

            if (registrado) {
                servidor.agregarLog(
                        "Nodo #"
                        + idCliente
                        + " se ha desconectado."
                );
            } else {
                servidor.agregarLog(
                        "Conexión rechazada desde "
                        + ipCliente
                        + ": "
                        + describirFalloSaludo(e)
                );
            }

        } finally {

            // Solo un nodo que llegó a registrarse hay que dar de baja.
            if (registrado) {
                if (nodo != null) {
                    nodo.desconectar();
                }

                servidor.removerCliente(idCliente);
            }

            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Saludo de seguridad con el agente. Orden del protocolo:
     *   1. Negociación TLS (el canal queda cifrado).
     *   2. El agente envía su token; el servidor responde @@OK o @@DENEGADO.
     *   3. Solo si fue @@OK, el agente envía su sistema operativo y usuario.
     *
     * Cada paso tiene un tiempo máximo: una conexión que se queda callada no puede
     * mantener un hilo del servidor ocupado.
     *
     * @return {sistemaOperativo, usuario}, o null si el token fue rechazado.
     */
    private String[] realizarSaludo(
            DataInputStream entrada,
            DataOutputStream destino,
            String ipCliente) throws IOException {

        socket.setSoTimeout(CanalSeguro.TIMEOUT_SALUDO_MS);

        // Falla cerrado: sin TLS no se atiende a nadie.
        if (!(socket instanceof SSLSocket)) {
            throw new IOException("la conexión no usa TLS");
        }
        ((SSLSocket) socket).startHandshake();

        String token = entrada.readUTF();

        if (!seguridad.tokenCoincide(token)) {
            servidor.agregarLog(
                    "Conexión rechazada desde "
                    + ipCliente
                    + ": token de acceso incorrecto."
            );
            destino.writeUTF(CanalSeguro.ACCESO_DENEGADO);
            destino.flush();
            return null;
        }

        destino.writeUTF(CanalSeguro.ACCESO_OK);
        destino.flush();

        String sistemaOperativo = entrada.readUTF();
        String usuario = entrada.readUTF();

        // Saludo terminado: el agente puede quedarse callado todo el tiempo que quiera.
        socket.setSoTimeout(0);

        return new String[]{sistemaOperativo, usuario};
    }

    private static String describirFalloSaludo(IOException e) {
        if (e instanceof SSLException) {
            return "falló la negociación TLS (" + e.getMessage() + ").";
        }
        if (e instanceof SocketTimeoutException) {
            return "no completó el saludo a tiempo.";
        }
        if (e instanceof EOFException) {
            return "cerró la conexión antes de autenticarse.";
        }
        return e.getMessage();
    }

    /**
     * Interpreta "@@ALERTA|METRICA|valor|umbral|texto" y "@@RECUPERADO|..." enviados
     * por el monitoreo continuo del agente.
     */
    void procesarMensajeNoSolicitado(String mensaje) {
        if (mensaje.startsWith("@@METRICA|")) {
            procesarMetrica(mensaje);
            return;
        }

        String[] partes = mensaje.split("\\|", 5);
        boolean esAlerta = partes[0].equals("@@ALERTA");
        boolean esRecuperado = partes[0].equals("@@RECUPERADO");

        if ((!esAlerta && !esRecuperado) || partes.length < 5) {
            servidor.agregarLog("Mensaje no reconocido del nodo #" + idCliente
                    + " (se ignora): " + mensaje);
            return;
        }

        if (nodo != null) {
            nodo.marcarAlerta(partes[1], esAlerta);
        }
        servidor.alertaRecibida(idCliente, esAlerta, partes[1], partes[4]);
    }

    /**
     * Interpreta "@@METRICA|cpu|ram|disco", la lectura periódica del agente (REQ-02):
     * la guarda como última métrica del nodo y la registra en el CSV del servidor.
     */
    private void procesarMetrica(String mensaje) {
        String[] partes = mensaje.split("\\|");
        if (partes.length != 4 || nodo == null) {
            servidor.agregarLog("Métrica mal formada del nodo #" + idCliente
                    + " (se ignora): " + mensaje);
            return;
        }
        try {
            Metrica metrica = new Metrica(
                    Double.parseDouble(partes[1]),
                    Double.parseDouble(partes[2]),
                    Double.parseDouble(partes[3]));
            nodo.actualizarMetrica(metrica);
            servidor.metricaRecibida(idCliente, nodo.getIp(), metrica);
        } catch (IllegalArgumentException e) {
            // NumberFormatException (texto no numérico) o valor fuera de 0-100.
            servidor.agregarLog("Métrica inválida del nodo #" + idCliente
                    + " (se ignora): " + mensaje);
        }
    }

    /**
     * Envía una orden al nodo mediante Socket.
     */
    public void enviarComando(String comando) {

        if (salida == null) {
            servidor.agregarLog(
                    "No existe una salida activa para el nodo #"
                    + idCliente
            );
            return;
        }

        try {

            synchronized (pendientes) {
                salida.writeUTF(comando);
                salida.flush();
                pendientes.addLast(MARCA_COMANDO);
            }

        } catch (IOException e) {

            servidor.agregarLog(
                    "Error al enviar comando al nodo #"
                    + idCliente
                    + ": "
                    + e.getMessage()
            );
        }
    }

    /**
     * Mide la latencia del canal con el agente (ida y vuelta de un mensaje
     * mínimo). Si hay un comando en curso no se sondea: su respuesta llegaría
     * detrás del comando y mediría el tiempo de ejecución, no la red.
     */
    public void sondearLatencia() {
        synchronized (pendientes) {
            if (salida == null || nodo == null || !pendientes.isEmpty()) {
                return;
            }
            try {
                long inicio = System.nanoTime();
                salida.writeUTF(CMD_LATENCIA);
                salida.flush();
                pendientes.addLast(inicio);
            } catch (IOException e) {
                // Si el nodo se cayó, el hilo de lectura lo detecta y lo remueve.
            }
        }
    }

    public Nodo getNodo() {
        return nodo;
    }

    public int getIdCliente() {
        return idCliente;
    }
}

