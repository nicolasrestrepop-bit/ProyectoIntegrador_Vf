package com.proyecto.cliente;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/**
 * Monitoreo continuo del nodo con alertas por umbral.
 *
 * Un hilo propio toma una muestra cada cierto tiempo (CPU, RAM, disco,
 * temperatura y latencia al gateway), la compara con los umbrales y envía al
 * Servidor Central mensajes NO solicitados. Todos empiezan con "@@" para que
 * ManejadorCliente no los confunda con la respuesta de un comando:
 *
 *   @@ALERTA|METRICA|valor|umbral|texto       (se superó el umbral)
 *   @@RECUPERADO|METRICA|valor|umbral|texto   (volvió a la normalidad)
 *   @@METRICA|cpu|ram|disco                   (lectura periódica, cada muestra)
 *
 * Para no inundar al servidor: una alerta solo se envía tras varias muestras
 * consecutivas por encima del umbral, se recuerda cada 5 minutos si persiste y
 * se da por recuperada tras 2 muestras normales seguidas.
 *
 * Los umbrales se cambian con los comandos MONITOREO_CONFIG, MONITOREO_ON,
 * MONITOREO_OFF y MONITOREO_ESTADO, y se guardan en un archivo junto al agente.
 */
public final class MonitorContinuo {

    /** Canal por el que AgenteCliente envía al servidor los mensajes "@@...". */
    public interface Emisor {
        void enviar(String mensaje) throws IOException;
    }

    static final String PREFIJO_ALERTA = "@@ALERTA";
    static final String PREFIJO_RECUPERADO = "@@RECUPERADO";
    static final String PREFIJO_METRICA = "@@METRICA";

    private static final int INTERVALO_DEFECTO_S = 10;
    private static final int MUESTRAS_DEFECTO = 3;
    private static final int MUESTRAS_RECUPERACION = 2;
    private static final long RECORDATORIO_MS = 5 * 60 * 1000L;
    private static final String ARCHIVO_CONFIG = "agente_umbrales.properties";

    private static final String USO = "Uso: MONITOREO_CONFIG clave=valor ...  Claves: cpu, ram, disco (1-100 %), "
            + "temp (30-120 °C), lat (1-10000 ms), intervalo (5-3600 s), muestras (1-20). "
            + "Use off para desactivar un umbral y 'defecto' para restaurar los valores iniciales. "
            + "Ej.: MONITOREO_CONFIG cpu=85 ram=90 disco=90 temp=80 lat=150";

    /** Una magnitud vigilada y su estado de alerta. */
    private static final class Medida {
        final String clave;
        final String nombre;
        final String unidad;
        final double minimo;
        final double maximo;
        final double defecto;

        double umbral;                 // 0 = desactivada
        int consecutivosAltos;
        int consecutivosNormales;
        boolean enAlerta;
        long ultimoAviso;
        double ultimoValor = Double.NaN;
        String ultimoDetalle = "sin medir";
        boolean disponible;            // false = el equipo no entrega este dato

        Medida(String clave, String nombre, String unidad, double minimo, double maximo, double defecto) {
            this.clave = clave;
            this.nombre = nombre;
            this.unidad = unidad;
            this.minimo = minimo;
            this.maximo = maximo;
            this.defecto = defecto;
            this.umbral = defecto;
        }
    }

    private static final Medida CPU = new Medida("cpu", "CPU", "%", 1, 100, 90);
    private static final Medida RAM = new Medida("ram", "RAM", "%", 1, 100, 90);
    private static final Medida DISCO = new Medida("disco", "DISCO", "%", 1, 100, 90);
    private static final Medida TEMPERATURA = new Medida("temp", "TEMPERATURA", " °C", 30, 120, 85);
    private static final Medida LATENCIA = new Medida("lat", "LATENCIA", " ms", 1, 10000, 150);
    private static final Medida[] MEDIDAS = {CPU, RAM, DISCO, TEMPERATURA, LATENCIA};

    private static final Object CERROJO = new Object();

    private static Emisor emisor;
    private static Thread hilo;
    private static boolean ejecutando;
    private static boolean activo = true;
    private static int intervaloS = INTERVALO_DEFECTO_S;
    private static int muestrasParaAlertar = MUESTRAS_DEFECTO;

    private static MantenimientoSO.Muestra ultimaMuestra;
    private static long ultimaMuestraMs;
    private static int alertasEnviadas;
    private static int fallosDeMuestreo;
    private static String ultimoFallo = "";
    private static String estadoArchivo = "";

    private MonitorContinuo() {
    }

    // ------------------------------------------------------------------
    //  CICLO DE VIDA
    // ------------------------------------------------------------------

    /** Arranca el hilo de monitoreo (lo llama AgenteCliente al conectarse). */
    public static void iniciar(Emisor canal) {
        synchronized (CERROJO) {
            if (hilo != null && hilo.isAlive()) {
                emisor = canal;
                return;
            }
            emisor = canal;
            cargarConfiguracion();
            ejecutando = true;
            hilo = new Thread(MonitorContinuo::bucle, "monitor-continuo");
            hilo.setDaemon(true);
            hilo.start();
        }
    }

    /** Detiene el hilo (conexión terminada). */
    public static void detener() {
        synchronized (CERROJO) {
            ejecutando = false;
            emisor = null;
            CERROJO.notifyAll();
        }
    }

    private static void bucle() {
        while (true) {
            boolean medir;
            boolean medirLatencia;
            long esperaMs;
            synchronized (CERROJO) {
                if (!ejecutando) return;
                medir = activo;
                medirLatencia = LATENCIA.umbral > 0;
                esperaMs = intervaloS * 1000L;
            }

            if (medir) {
                try {
                    MantenimientoSO.Muestra m = MantenimientoSO.tomarMuestra(medirLatencia);
                    procesarMuestra(m);
                } catch (Throwable t) {
                    synchronized (CERROJO) {
                        fallosDeMuestreo++;
                        ultimoFallo = t.getClass().getSimpleName()
                                + (t.getMessage() == null ? "" : ": " + t.getMessage());
                    }
                }
            }

            synchronized (CERROJO) {
                if (!ejecutando) return;
                try {
                    CERROJO.wait(esperaMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  EVALUACIÓN DE UMBRALES
    // ------------------------------------------------------------------

    /** Compara una muestra con los umbrales y emite las alertas que correspondan. */
    static void procesarMuestra(MantenimientoSO.Muestra m) {
        synchronized (CERROJO) {
            ultimaMuestra = m;
            ultimaMuestraMs = System.currentTimeMillis();

            emitirMetrica(m);

            evaluar(CPU, m.cpu, false, "uso de CPU " + f1(m.cpu) + "%");
            evaluar(RAM, m.ram, false, "uso de RAM " + f1(m.ram) + "%");
            evaluar(DISCO, m.disco, false,
                    "disco " + m.discoUnidad + " al " + f1(m.disco) + "%");
            evaluar(TEMPERATURA, m.temperatura, false, "temperatura " + f1(m.temperatura) + " °C");

            if (!m.latenciaIntentada) {
                evaluar(LATENCIA, Double.NaN, false, "no se mide (umbral desactivado)");
            } else if (m.gateway == null) {
                evaluar(LATENCIA, Double.NaN, false, "no se detectó el gateway; latencia no disponible");
            } else if (m.latenciaSinRespuesta) {
                evaluar(LATENCIA, Double.NaN, true,
                        "el gateway " + m.gateway + " no responde a ping (muchos routers bloquean ICMP)");
            } else {
                evaluar(LATENCIA, m.latencia, false,
                        "latencia al gateway " + m.gateway + ": " + f1(m.latencia) + " ms");
            }
        }
    }

    /** Solo se llama con el cerrojo tomado. */
    private static void evaluar(Medida k, double valor, boolean sinRespuesta, String detalle) {
        boolean hayDato = sinRespuesta || !Double.isNaN(valor);
        k.disponible = hayDato;
        k.ultimoValor = valor;
        k.ultimoDetalle = hayDato ? detalle : (k == LATENCIA ? detalle : "el equipo no entrega este dato");

        // Sin dato o umbral desactivado: no se alerta ni se inventa un valor.
        if (!hayDato || k.umbral <= 0) {
            k.consecutivosAltos = 0;
            k.consecutivosNormales = 0;
            return;
        }

        long ahora = System.currentTimeMillis();
        boolean excede = sinRespuesta || valor >= k.umbral;
        String valorTexto = sinRespuesta ? "sin respuesta" : f1(valor);
        String texto = detalle + " (umbral " + num(k.umbral) + k.unidad + ")";

        if (excede) {
            k.consecutivosAltos++;
            k.consecutivosNormales = 0;
            if (!k.enAlerta && k.consecutivosAltos >= muestrasParaAlertar) {
                k.enAlerta = true;
                k.ultimoAviso = ahora;
                emitir(PREFIJO_ALERTA, k, valorTexto, texto + ", " + k.consecutivosAltos
                        + " muestras seguidas");
            } else if (k.enAlerta && ahora - k.ultimoAviso >= RECORDATORIO_MS) {
                k.ultimoAviso = ahora;
                emitir(PREFIJO_ALERTA, k, valorTexto, texto + ", sigue en alerta");
            }
        } else {
            k.consecutivosNormales++;
            k.consecutivosAltos = 0;
            if (k.enAlerta && k.consecutivosNormales >= MUESTRAS_RECUPERACION) {
                k.enAlerta = false;
                emitir(PREFIJO_RECUPERADO, k, valorTexto, texto + ", volvió a la normalidad");
            }
        }
    }

    /**
     * Envía al servidor la lectura periódica "@@METRICA|cpu|ram|disco" (REQ-02).
     * Se manda en cada muestra (por defecto cada 10 s), haya o no alerta. Si alguna
     * de las tres lecturas no está disponible se omite ese ciclo. Con el cerrojo tomado.
     */
    private static void emitirMetrica(MantenimientoSO.Muestra m) {
        if (emisor == null) return;
        if (Double.isNaN(m.cpu) || Double.isNaN(m.ram) || Double.isNaN(m.disco)) return;
        try {
            emisor.enviar(PREFIJO_METRICA + "|"
                    + String.format(Locale.ROOT, "%.1f|%.1f|%.1f",
                            limitar(m.cpu, 0, 100), limitar(m.ram, 0, 100), limitar(m.disco, 0, 100)));
        } catch (IOException e) {
            // La conexión se cayó: el bucle principal del agente lo detecta y termina.
        }
    }

    /** Solo se llama con el cerrojo tomado. */
    private static void emitir(String tipo, Medida k, String valor, String texto) {
        if (emisor == null) return;
        String limpio = texto.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
        try {
            emisor.enviar(tipo + "|" + k.nombre + "|" + valor + "|" + num(k.umbral) + "|" + limpio);
            if (PREFIJO_ALERTA.equals(tipo)) alertasEnviadas++;
        } catch (IOException e) {
            // La conexión se cayó: el bucle principal del agente lo detecta y termina.
        }
    }

    /** Cierra las alertas abiertas de una medida (se desactivó); con el cerrojo tomado. */
    private static void cerrarAlerta(Medida k, String motivo) {
        if (k.enAlerta) {
            k.enAlerta = false;
            emitir(PREFIJO_RECUPERADO, k, "n/d", k.nombre + ": " + motivo);
        }
        k.consecutivosAltos = 0;
        k.consecutivosNormales = 0;
    }

    // ------------------------------------------------------------------
    //  COMANDOS (los invoca MantenimientoSO)
    // ------------------------------------------------------------------

    private static boolean enEjecucion() {
        return hilo != null && hilo.isAlive();
    }

    static String activar(boolean encender) {
        synchronized (CERROJO) {
            if (!enEjecucion()) {
                return "ADVERTENCIA: El monitoreo continuo no está en ejecución en este agente.";
            }
            activo = encender;
            if (!encender) {
                for (Medida k : MEDIDAS) cerrarAlerta(k, "monitoreo desactivado");
            }
            String guardado = guardarConfiguracion();
            CERROJO.notifyAll();
            return "ÉXITO [MONITOREO]: Monitoreo continuo " + (encender ? "ACTIVADO" : "DESACTIVADO")
                    + (encender ? " (primera muestra en curso)." : ". No se tomarán muestras ni se enviarán alertas.")
                    + "\n" + guardado;
        }
    }

    static String configurar(String[] args) {
        if (args.length == 0) {
            return "ADVERTENCIA: Faltan los umbrales. " + USO;
        }
        synchronized (CERROJO) {
            if (!enEjecucion()) {
                return "ADVERTENCIA: El monitoreo continuo no está en ejecución en este agente.";
            }

            // Se valida todo antes de aplicar: o se aplica completo o no cambia nada.
            double[] umbrales = new double[MEDIDAS.length];
            for (int i = 0; i < MEDIDAS.length; i++) umbrales[i] = MEDIDAS[i].umbral;
            int intervalo = intervaloS;
            int muestras = muestrasParaAlertar;

            for (String crudo : args) {
                String t = crudo.trim().toLowerCase(Locale.ROOT).replaceAll("[,;]+$", "");
                if (t.isEmpty()) continue;

                if (t.equals("defecto")) {
                    for (int i = 0; i < MEDIDAS.length; i++) umbrales[i] = MEDIDAS[i].defecto;
                    intervalo = INTERVALO_DEFECTO_S;
                    muestras = MUESTRAS_DEFECTO;
                    continue;
                }

                int igual = t.indexOf('=');
                if (igual <= 0 || igual == t.length() - 1) {
                    return "ADVERTENCIA: Parámetro inválido '" + crudo + "' (se espera clave=valor). "
                            + "No se cambió nada. " + USO;
                }
                String clave = t.substring(0, igual);
                String valor = t.substring(igual + 1);

                try {
                    if (clave.equals("intervalo")) {
                        intervalo = enteroEnRango(valor, 5, 3600, "intervalo (segundos)");
                    } else if (clave.equals("muestras")) {
                        muestras = enteroEnRango(valor, 1, 20, "muestras");
                    } else {
                        int indice = indiceMedida(clave);
                        if (indice < 0) {
                            return "ADVERTENCIA: Clave desconocida '" + clave + "'. No se cambió nada. " + USO;
                        }
                        Medida k = MEDIDAS[indice];
                        if (valor.equals("off") || valor.equals("no") || valor.equals("0")) {
                            umbrales[indice] = 0;
                        } else {
                            double v = Double.parseDouble(valor.replace(',', '.'));
                            if (Double.isNaN(v) || v < k.minimo || v > k.maximo) {
                                return "ADVERTENCIA: " + k.clave + "=" + valor + " fuera de rango ("
                                        + num(k.minimo) + "-" + num(k.maximo) + k.unidad.trim()
                                        + "). No se cambió nada.";
                            }
                            umbrales[indice] = v;
                        }
                    }
                } catch (NumberFormatException e) {
                    return "ADVERTENCIA: Valor inválido en '" + crudo + "'. No se cambió nada. " + USO;
                }
            }

            for (int i = 0; i < MEDIDAS.length; i++) {
                MEDIDAS[i].umbral = umbrales[i];
                if (umbrales[i] <= 0) cerrarAlerta(MEDIDAS[i], "umbral desactivado");
            }
            intervaloS = intervalo;
            muestrasParaAlertar = muestras;
            String guardado = guardarConfiguracion();
            CERROJO.notifyAll();

            return "ÉXITO [MONITOREO]: Configuración aplicada.\n" + resumenConfiguracion() + "\n" + guardado;
        }
    }

    static String estado() {
        synchronized (CERROJO) {
            if (!enEjecucion()) {
                return "ADVERTENCIA: El monitoreo continuo no está en ejecución en este agente.";
            }

            StringBuilder activas = new StringBuilder();
            for (Medida k : MEDIDAS) {
                if (k.enAlerta) {
                    if (activas.length() > 0) activas.append(", ");
                    activas.append(k.nombre);
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(activas.length() > 0
                    ? "ALERTA MONITOREO: Alertas activas en este nodo: " + activas + "\n"
                    : "ÉXITO [MONITOREO CONTINUO]\n");
            sb.append("Estado: ").append(activo ? "ACTIVO" : "DESACTIVADO").append("\n");
            sb.append(resumenConfiguracion()).append("\n");

            if (ultimaMuestra == null) {
                sb.append("Última muestra: aún no hay ninguna.\n");
            } else {
                long edad = Math.max(0, (System.currentTimeMillis() - ultimaMuestraMs) / 1000);
                sb.append("Última muestra: hace ").append(edad).append(" s (fuente: ")
                  .append(ultimaMuestra.fuente).append(")\n");
                for (Medida k : MEDIDAS) {
                    sb.append("  ").append(k.nombre).append(": ").append(k.ultimoDetalle);
                    if (!k.disponible) {
                        sb.append("\n");
                        continue;
                    }
                    if (k.umbral <= 0) {
                        sb.append(" | umbral desactivado");
                    } else {
                        sb.append(" | ").append(k.enAlerta ? "EN ALERTA" : "normal");
                    }
                    sb.append("\n");
                }
            }
            sb.append("Alertas enviadas desde que arrancó el agente: ").append(alertasEnviadas);
            if (fallosDeMuestreo > 0) {
                sb.append("\nFallos de muestreo: ").append(fallosDeMuestreo)
                  .append(" (último: ").append(ultimoFallo).append(")");
            }
            if (!estadoArchivo.isEmpty()) sb.append("\n").append(estadoArchivo);
            return sb.toString();
        }
    }

    private static String resumenConfiguracion() {
        StringBuilder sb = new StringBuilder("Umbrales: ");
        for (int i = 0; i < MEDIDAS.length; i++) {
            Medida k = MEDIDAS[i];
            if (i > 0) sb.append(" | ");
            sb.append(k.clave).append("=").append(k.umbral <= 0 ? "off" : num(k.umbral) + k.unidad);
        }
        sb.append("\nIntervalo: ").append(intervaloS).append(" s | Muestras seguidas para alertar: ")
          .append(muestrasParaAlertar).append(" | Recordatorio: cada 5 min si persiste");
        return sb.toString();
    }

    private static int indiceMedida(String clave) {
        switch (clave) {
            case "cpu": return 0;
            case "ram": return 1;
            case "disco": return 2;
            case "temp":
            case "temperatura": return 3;
            case "lat":
            case "latencia": return 4;
            default: return -1;
        }
    }

    private static int enteroEnRango(String valor, int minimo, int maximo, String nombre) {
        int v = Integer.parseInt(valor);
        if (v < minimo || v > maximo) {
            throw new NumberFormatException(nombre + " fuera de rango");
        }
        return v;
    }

    // ------------------------------------------------------------------
    //  PERSISTENCIA (archivo junto al agente)
    // ------------------------------------------------------------------

    private static File archivoConfiguracion() {
        return new File(System.getProperty("user.dir", "."), ARCHIVO_CONFIG);
    }

    /** Solo se llama con el cerrojo tomado. */
    private static void cargarConfiguracion() {
        File archivo = archivoConfiguracion();
        if (!archivo.isFile()) {
            estadoArchivo = "Configuración: valores iniciales (aún no se guardó ninguna).";
            return;
        }
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(archivo)) {
            p.load(in);
            activo = !"false".equals(p.getProperty("activo", "true"));
            intervaloS = (int) limitar(Double.parseDouble(p.getProperty("intervalo",
                    String.valueOf(INTERVALO_DEFECTO_S))), 5, 3600);
            muestrasParaAlertar = (int) limitar(Double.parseDouble(p.getProperty("muestras",
                    String.valueOf(MUESTRAS_DEFECTO))), 1, 20);
            for (Medida k : MEDIDAS) {
                double v = Double.parseDouble(p.getProperty(k.clave, String.valueOf(k.defecto)));
                k.umbral = v <= 0 ? 0 : limitar(v, k.minimo, k.maximo);
            }
            estadoArchivo = "Configuración cargada de " + archivo.getAbsolutePath();
        } catch (IOException | RuntimeException e) {
            estadoArchivo = "Aviso: no se pudo leer " + archivo.getAbsolutePath()
                    + " (" + e.getMessage() + "); se usan los valores iniciales.";
        }
    }

    /** Solo se llama con el cerrojo tomado. @return texto para mostrar al usuario. */
    private static String guardarConfiguracion() {
        File archivo = archivoConfiguracion();
        Properties p = new Properties();
        p.setProperty("activo", String.valueOf(activo));
        p.setProperty("intervalo", String.valueOf(intervaloS));
        p.setProperty("muestras", String.valueOf(muestrasParaAlertar));
        for (Medida k : MEDIDAS) p.setProperty(k.clave, num(k.umbral));
        try (FileOutputStream out = new FileOutputStream(archivo)) {
            p.store(out, "Umbrales del monitoreo continuo del agente");
            estadoArchivo = "Configuración guardada en " + archivo.getAbsolutePath();
            return "Guardado en " + archivo.getAbsolutePath() + " (se conserva al reiniciar el agente).";
        } catch (IOException e) {
            estadoArchivo = "Aviso: la configuración no se pudo guardar (" + e.getMessage() + ").";
            return "Aviso: no se pudo guardar en " + archivo.getAbsolutePath() + " (" + e.getMessage()
                    + "); el cambio vale solo hasta que se reinicie el agente.";
        }
    }

    // ------------------------------------------------------------------
    //  FORMATO
    // ------------------------------------------------------------------

    private static double limitar(double v, double minimo, double maximo) {
        return Math.max(minimo, Math.min(maximo, v));
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 90 -> "90"; 87.5 -> "87.5". */
    private static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
