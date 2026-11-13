package com.proyecto.cliente;

import java.io.Console;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLException;
import com.proyecto.modelo.ResultadoMantenimiento;
import com.proyecto.seguridad.CanalSeguro;
import com.proyecto.seguridad.ConfiguracionSeguridad;

/**
 * Agente que se ejecuta en cada equipo (nodo) y recibe órdenes del
 * Servidor Central.
 *
 * Formas de indicar a qué servidor conectarse:
 *   1) Por argumentos:   java ... AgenteCliente 192.168.1.15
 *                        java ... AgenteCliente 192.168.1.15 5000
 *   2) Sin argumentos:   el programa pregunta la IP; si se presiona
 *                        ENTER usa 127.0.0.1 (el mismo equipo).
 */
public class AgenteCliente {

    private static final String IP_POR_DEFECTO = "127.0.0.1";
    private static final int PUERTO_POR_DEFECTO = 5000;
    private static final int TIMEOUT_CONEXION_MS = 5000;

    // Sondeo de latencia del servidor (control interno, no es un comando de
    // mantenimiento): se responde de inmediato y sin escribir en consola.
    private static final String CMD_LATENCIA = "@@LAT";
    private static final String RESPUESTA_LATENCIA = "@@PONG";

    // Mensajes no solicitados (alertas del monitoreo continuo): empiezan con "@@"
    // y los envía MonitorContinuo; ver MonitorContinuo.PREFIJO_ALERTA/RECUPERADO.

    public static void main(String[] args) {

        String ipServidor;
        int puerto = PUERTO_POR_DEFECTO;

        if (args.length >= 1 && !args[0].trim().isEmpty()) {
            ipServidor = args[0].trim();
        } else {
            ipServidor = solicitarIp();
        }

        if (args.length >= 2) {
            try {
                puerto = Integer.parseInt(args[1].trim());
                if (puerto < 1 || puerto > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                System.err.println("Puerto inválido: '" + args[1]
                        + "'. Debe ser un número entre 1 y 65535.");
                return;
            }
        }

        // Token compartido + certificado del servidor (truststore). Sin ellos no se conecta.
        ConfiguracionSeguridad seguridad;
        try {
            seguridad = ConfiguracionSeguridad.cargar(ConfiguracionSeguridad.Rol.AGENTE);
        } catch (ConfiguracionSeguridad.ErrorConfiguracion e) {
            System.err.println(e.getMessage());
            return;
        }

        System.out.println("Conectando de forma segura (TLS) con el Servidor Central en "
                + ipServidor + ":" + puerto + " ...");

        Socket socket;

        try {
            socket = CanalSeguro.conectar(ipServidor, puerto, TIMEOUT_CONEXION_MS, seguridad);
        } catch (SSLException e) {
            System.err.println("No se pudo establecer el canal seguro con " + ipServidor
                    + ":" + puerto + " (" + e.getMessage() + ")");
            System.err.println("Revise que:");
            System.err.println(" - El certificado de seguridad/agente sea el del servidor al que se conecta");
            System.err.println("   (si se regeneraron las credenciales, copie de nuevo la carpeta).");
            System.err.println(" - Esté conectándose al Servidor Central y no a otro equipo.");
            System.err.println(" - El servidor no haya alcanzado su máximo de nodos conectados.");
            return;
        } catch (GeneralSecurityException e) {
            System.err.println("No se pudo leer el certificado de seguridad/agente: " + e.getMessage());
            System.err.println("Revise la clave 'truststore.password' de seguridad.properties.");
            return;
        } catch (IOException e) {
            System.err.println("No se pudo conectar con " + ipServidor
                    + ":" + puerto + " (" + e.getMessage() + ")");
            System.err.println("Revise que:");
            System.err.println(" - El Servidor Central esté ejecutándose.");
            System.err.println(" - La IP sea la del equipo del servidor "
                    + "(en Windows: comando 'ipconfig').");
            System.err.println(" - Ambos equipos estén en la misma red.");
            System.err.println(" - El Firewall del servidor permita el puerto "
                    + puerto + ".");
            return;
        }

        try (socket;
             DataInputStream entrada = new DataInputStream(socket.getInputStream());
             DataOutputStream salida = new DataOutputStream(socket.getOutputStream())) {

            // 1. Autenticación: el token viaja dentro del canal ya cifrado.
            if (!autenticar(socket, entrada, salida, seguridad)) {
                return;
            }

            // 1a. Datos de identificación inicial (solo después de ser aceptado).
            salida.writeUTF(System.getProperty("os.name"));
            salida.writeUTF(System.getProperty("user.name"));
            salida.flush();

            // Terminado el saludo, esperar órdenes sin límite de tiempo.
            socket.setSoTimeout(0);

            System.out.println("Agente conectado al servidor. Esperando órdenes de mantenimiento...");

            // 1b. Monitoreo continuo: puede escribir en el socket mientras el hilo principal
            //     responde comandos, por eso todo envío se serializa sobre 'salida'.
            MonitorContinuo.iniciar(mensaje -> enviar(salida, mensaje));

            // 2. Escuchar comandos enviados desde el botón de la UI
            while (!socket.isClosed()) {
                String comandoRecibido = entrada.readUTF();

                if (CMD_LATENCIA.equals(comandoRecibido)) {
                    enviar(salida, RESPUESTA_LATENCIA);
                    continue;
                }

                System.out.println("Comando recibido del Servidor: " + comandoRecibido);

                // Ejecutar rutina de mantenimiento y obtener informe detallado
                ResultadoMantenimiento resultado =
                    MantenimientoSO.ejecutarAccionComoResultado(
                        comandoRecibido
                    );

                // Responder al Servidor con los parámetros de la ejecución
                enviar(salida, resultado.toString());
            }

        } catch (IOException e) {
            System.err.println("Conexión finalizada con el Servidor Central: " + e.getMessage());
        } finally {
            MonitorContinuo.detener();
        }
    }

    /**
     * Envía el token y espera el veredicto del servidor (@@OK / @@DENEGADO).
     * Usa el tiempo límite que dejó CanalSeguro.conectar, por si el servidor no responde.
     *
     * @return true si el servidor aceptó al agente.
     */
    private static boolean autenticar(
            Socket socket,
            DataInputStream entrada,
            DataOutputStream salida,
            ConfiguracionSeguridad seguridad) throws IOException {

        salida.writeUTF(seguridad.getToken());
        salida.flush();

        String veredicto;
        try {
            veredicto = entrada.readUTF();
        } catch (EOFException | SocketException e) {
            System.err.println("El servidor cerró la conexión durante el acceso.");
            System.err.println("Posible causa: ya hay el máximo de nodos conectados.");
            return false;
        } catch (SocketTimeoutException e) {
            System.err.println("El servidor no respondió a la autenticación a tiempo.");
            return false;
        }

        if (!CanalSeguro.ACCESO_OK.equals(veredicto)) {
            System.err.println("El servidor rechazó el acceso: token incorrecto.");
            System.err.println("Copie de nuevo la carpeta seguridad/agente desde el servidor.");
            return false;
        }

        return true;
    }

    /** Único punto de escritura en el socket (hilo principal y monitoreo). */
    private static void enviar(DataOutputStream salida, String mensaje) throws IOException {
        synchronized (salida) {
            salida.writeUTF(mensaje);
            salida.flush();
        }
    }

    /**
     * Pregunta la IP del servidor por consola.
     * ENTER (o consola sin entrada disponible) = IP por defecto.
     */
    private static String solicitarIp() {
        Console consola = System.console();
        if (consola == null) {
            return IP_POR_DEFECTO;
        }

        try {
            String texto = consola.readLine("IP del Servidor Central [ENTER = "
                    + IP_POR_DEFECTO + "]: ");
            if (texto != null) {
                String valor = texto.trim();
                if (!valor.isEmpty()) {
                    return valor;
                }
            }
        } catch (Exception ignorado) {
            // Sin consola interactiva o lectura disponible: se usa la IP por defecto.
        }

        return IP_POR_DEFECTO;
    }
}