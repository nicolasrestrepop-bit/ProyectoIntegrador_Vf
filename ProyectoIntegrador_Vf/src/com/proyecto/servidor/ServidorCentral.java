package com.proyecto.servidor;

import javax.swing.*;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.proyecto.modelo.Nodo;
import com.proyecto.seguridad.CanalSeguro;
import com.proyecto.seguridad.ConfiguracionSeguridad;
import java.net.ServerSocket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServidorCentral extends JFrame {

    private static final int PUERTO = 5000;
    private static final int MAX_CLIENTES = 5;
    private static final int INTERVALO_LATENCIA_S = 5;
    private final AtomicInteger siguienteIdNodo = new AtomicInteger(1);
    private final GestorNodos gestorNodos = new GestorNodos();
    private final GestorMantenimiento gestorMantenimiento;
    private final GestorReportes gestorReportes;
    private final ScheduledExecutorService muestreoLatencia =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread hilo = new Thread(r, "muestreo-latencia");
                hilo.setDaemon(true);
                return hilo;
            });

    // Navegación por Cartas (CardLayout)
    private CardLayout cardLayout;
    private JPanel panelContenidoCartas;

    // Botones Sidebar
    private List<JButton> botonesSidebar = new ArrayList<>();

    // Componentes de la UI
    private DefaultTableModel modeloTablaDashboard;
    private DefaultTableModel modeloTablaRed;
    private JLabel lblDispositivosActivos;
    private JLabel lblLatenciaMedia;
    private JLabel lblAlertasActivas;
    private JLabel lblUltimoEscaneo;
    private JComboBox<String> comboTareas;
    private JTextField txtRangoIPDashboard;
    private JTextField txtRangoIPVistaRed;
    private JTextArea areaLogsConsola;
    private JTextArea areaDetallesRedAmpliada;
    private JTextArea areaLogsMantenimiento;
    private JTextArea areaLogsMantenimientoRed;
    private JComboBox<String> comboNodosObjetivoSO;
    private JComboBox<String> comboNodosObjetivoRed;
    private JTextArea areaReporteEnPantalla;
    private String vistaMantenimientoActiva = "SO";

    // Resultado de la selección de nodo en los combos "Nodo objetivo".
    private static final int NODO_TODOS = 0;
    private static final int NODO_NINGUNO = -1;
    private static final Pattern PATRON_ID_NODO = Pattern.compile("^NODO ID:\\s*(\\d+)");

    // Columnas de la tabla de dispositivos (dashboard y monitoreo de red).
    private static final int COL_ID = 0;
    private static final int COL_ESTADO = 3;
    private static final int COL_CPU = 4;
    private static final int COL_RAM = 5;
    private static final int COL_LATENCIA = 6;
    private static final String SIN_DATO = "—";

    /** Ventanas de gráfica abiertas, por id de nodo. Solo se usa en el EDT. */
    private final Map<Integer, VentanaGraficaNodo> ventanasGrafica = new java.util.HashMap<>();

    // Paleta visual renovada: clara, limpia y fácil de leer.
    private final Color COLOR_FONDO_PRINCIPAL = new Color(238, 243, 250);
    private final Color COLOR_SIDEBAR = new Color(8, 12, 24);
    private final Color COLOR_TARJETA = Color.WHITE;
    private final Color COLOR_BORDE = new Color(226, 232, 240);
    private final Color COLOR_AZUL_ACENTO = new Color(37, 99, 235);
    private final Color COLOR_AZUL_SUAVE = new Color(239, 246, 255);
    private final Color COLOR_AZUL_OSCURO = EstilosUI.AZUL_OSCURO;
    private final Color COLOR_AZUL_CIELO = EstilosUI.AZUL_CIELO;
    private final Color COLOR_MARINO = EstilosUI.MARINO;
    private final Color COLOR_VERDE_ESTADO = new Color(22, 163, 74);
    private final Color COLOR_NARANJA = new Color(234, 88, 12);
    private final Color COLOR_ALERTA = new Color(220, 38, 38);
    private final Color COLOR_TEXTO_BLANCO = new Color(15, 23, 42);
    private final Color COLOR_TEXTO_GRIS = new Color(71, 85, 105);
    private final Color COLOR_TEXTO_SIDEBAR = new Color(226, 232, 240);

    public ServidorCentral() {
        gestorMantenimiento =
            new GestorMantenimiento(
                gestorNodos,
                this
            );

        gestorReportes =
            new GestorReportes(
                gestorNodos
            );

        setTitle("PROYECTO INTEGRADOR - SISTEMA CENTRAL DE MONITOREO Y GESTIÓN DE RED");
        setSize(1300, 850);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // 1. BARRA LATERAL (SIDEBAR)
        add(crearBarraLateral(), BorderLayout.WEST);

        // 2. CONTENEDOR CARDLAYOUT
        cardLayout = new CardLayout();
        panelContenidoCartas = new JPanel(cardLayout);
        panelContenidoCartas.setBackground(COLOR_FONDO_PRINCIPAL);

        // Registro de vistas
        panelContenidoCartas.add(crearVistaDashboard(), "DASHBOARD");
        panelContenidoCartas.add(crearVistaMonitoreoRed(), "MONITOREO DE RED");
        panelContenidoCartas.add(crearVistaMantenimientoSO(), "MANTENIMIENTO SO");
        panelContenidoCartas.add(crearVistaMantenimientoRed(), "MANTENIMIENTO RED");
        panelContenidoCartas.add(crearPanelReportes(), "REPORTES");

        add(panelContenidoCartas, BorderLayout.CENTER);

        iniciarMuestreoLatencia();
    }

    // --- LATENCIA DE LOS NODOS (columna "LATENCIA (ms)" y KPI) ---

    /** Cada pocos segundos pide a cada agente un eco mínimo y mide su ida y vuelta. */
    private void iniciarMuestreoLatencia() {
        muestreoLatencia.scheduleWithFixedDelay(() -> {
            try {
                for (ManejadorCliente manejador : gestorNodos.obtenerNodos()) {
                    manejador.sondearLatencia();
                }
            } catch (Throwable t) {
                // Un fallo puntual no debe detener el muestreo.
            }
        }, 2, INTERVALO_LATENCIA_S, TimeUnit.SECONDS);
    }

    /** Llamado por ManejadorCliente al recibir la respuesta de un sondeo. */
    public void actualizarLatencia(int idNodo, double ms) {
        SwingUtilities.invokeLater(() -> {
            // Una respuesta tardía de un nodo ya desconectado no debe pisar su "—".
            if (!gestorNodos.existeNodo(idNodo)) return;

            String id = String.format("%03d", idNodo);
            String texto = String.format(Locale.ROOT, "%.1f", ms);
            actualizarCeldaTabla(modeloTablaDashboard, id, COL_LATENCIA, texto);
            actualizarCeldaTabla(modeloTablaRed, id, COL_LATENCIA, texto);
            recalcularLatenciaMedia();
        });
    }

    private final RegistroMetricas registroMetricas = new RegistroMetricas("metricas.csv");
    private final java.util.Set<Integer> nodosConMetrica = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Llamado por ManejadorCliente al recibir la lectura periódica de un agente.
     * Se guarda en metricas.csv; en el log solo se avisa de la primera de cada nodo
     * para no llenarlo (una lectura cada ~10 s por nodo).
     */
    public void metricaRecibida(int idNodo, String ip, com.proyecto.modelo.Metrica metrica) {
        try {
            registroMetricas.registrar(idNodo, ip, metrica);
        } catch (java.io.IOException e) {
            agregarLog("No se pudo escribir metricas.csv: " + e.getMessage());
        }
        if (nodosConMetrica.add(idNodo)) {
            agregarLog(String.format(java.util.Locale.ROOT,
                    "Nodo #%03d envía métricas periódicas (CPU %.1f%%, RAM %.1f%%, Disco %.1f%%). Se registran en metricas.csv.",
                    idNodo, metrica.getCpu(), metrica.getRam(), metrica.getDisco()));
        }

        SwingUtilities.invokeLater(() -> {
            // Una lectura tardía de un nodo ya desconectado no debe pisar su "—".
            if (!gestorNodos.existeNodo(idNodo)) return;

            ManejadorCliente manejador = gestorNodos.obtenerNodo(idNodo);
            Nodo nodo = manejador == null ? null : manejador.getNodo();
            if (nodo == null) return;

            String id = String.format("%03d", idNodo);
            for (DefaultTableModel modelo : new DefaultTableModel[]{modeloTablaDashboard, modeloTablaRed}) {
                actualizarCeldaTabla(modelo, id, COL_CPU, metrica.getCpu());
                actualizarCeldaTabla(modelo, id, COL_RAM, metrica.getRam());
                actualizarCeldaTabla(modelo, id, COL_ESTADO, calcularEstado(nodo));
            }

            VentanaGraficaNodo ventana = ventanasGrafica.get(idNodo);
            if (ventana != null) ventana.actualizar();
        });
    }

    /**
     * Texto de la columna ESTADO de un nodo conectado:
     *   "ALERTA: CPU, RAM"   (rojo)  el agente confirmó una alerta (umbral sostenido).
     *   "ADVERTENCIA: CPU"   (ámbar) la última lectura supera el 70 %, sin alerta confirmada.
     *   "Online"             (verde) todo normal.
     */
    private String calcularEstado(Nodo nodo) {
        if (nodo.getCantidadAlertas() > 0) {
            return "ALERTA: " + nodo.resumenAlertas();
        }
        com.proyecto.modelo.Metrica ultima = nodo.getUltimaMetrica();
        if (ultima != null) {
            List<String> altas = new ArrayList<>();
            if (NivelMetrica.de(ultima.getCpu()) != NivelMetrica.NORMAL) altas.add("CPU");
            if (NivelMetrica.de(ultima.getRam()) != NivelMetrica.NORMAL) altas.add("RAM");
            if (!altas.isEmpty()) {
                return "ADVERTENCIA: " + String.join(", ", altas);
            }
        }
        return "Online";
    }

    /** Abre (o trae al frente) la gráfica en vivo del nodo indicado. Solo en el EDT. */
    private void abrirGraficaNodo(int idNodo) {
        VentanaGraficaNodo abierta = ventanasGrafica.get(idNodo);
        if (abierta != null) {
            abierta.toFront();
            return;
        }

        ManejadorCliente manejador = gestorNodos.obtenerNodo(idNodo);
        Nodo nodo = manejador == null ? null : manejador.getNodo();
        if (nodo == null) {
            JOptionPane.showMessageDialog(this,
                    "El nodo #" + String.format("%03d", idNodo) + " ya no está conectado.",
                    "Nodo no disponible", JOptionPane.WARNING_MESSAGE);
            return;
        }

        VentanaGraficaNodo ventana = new VentanaGraficaNodo(this, nodo, () -> ventanasGrafica.remove(idNodo));
        ventanasGrafica.put(idNodo, ventana);
        ventana.setVisible(true);
    }

    /**
     * Llamado por ManejadorCliente al recibir una alerta (o su recuperación) del
     * monitoreo continuo de un agente. Se ve en el log, en la columna ESTADO y en
     * el KPI "Alertas activas".
     */
    public void alertaRecibida(int idNodo, boolean esAlerta, String metrica, String texto) {
        agregarLog((esAlerta ? "[ALERTA] " : "[RECUPERADO] ") + "NODO #" + String.format("%03d", idNodo)
                + " " + metrica + ": " + texto);

        SwingUtilities.invokeLater(() -> {
            // Un mensaje tardío de un nodo ya desconectado no debe revivir su estado.
            if (!gestorNodos.existeNodo(idNodo)) return;

            ManejadorCliente manejador = gestorNodos.obtenerNodo(idNodo);
            Nodo nodo = manejador == null ? null : manejador.getNodo();
            if (nodo == null) return;

            String id = String.format("%03d", idNodo);
            String estado = calcularEstado(nodo);
            actualizarCeldaTabla(modeloTablaDashboard, id, COL_ESTADO, estado);
            actualizarCeldaTabla(modeloTablaRed, id, COL_ESTADO, estado);
            recalcularAlertasActivas();
        });
    }

    /** Total de alertas activas de los nodos conectados. Solo en el EDT. */
    private void recalcularAlertasActivas() {
        if (lblAlertasActivas == null) return;
        int total = 0;
        for (ManejadorCliente manejador : gestorNodos.obtenerNodos()) {
            Nodo nodo = manejador.getNodo();
            if (nodo != null) total += nodo.getCantidadAlertas();
        }
        lblAlertasActivas.setText(String.valueOf(total));
        lblAlertasActivas.setForeground(total > 0 ? COLOR_ALERTA : COLOR_TEXTO_BLANCO);
    }

    /** Promedio de la última latencia de los nodos conectados. Solo en el EDT. */
    private void recalcularLatenciaMedia() {
        double suma = 0;
        int medidos = 0;
        for (ManejadorCliente manejador : gestorNodos.obtenerNodos()) {
            Nodo nodo = manejador.getNodo();
            if (nodo != null && nodo.tieneLatencia()) {
                suma += nodo.getLatenciaMs();
                medidos++;
            }
        }
        lblLatenciaMedia.setText(medidos == 0
                ? "—"
                : String.format(Locale.ROOT, "%.1f ms", suma / medidos));
    }

    private void actualizarCeldaTabla(DefaultTableModel modelo, String idBuscado, int columna, Object valor) {
        if (modelo == null) return;
        for (int i = 0; i < modelo.getRowCount(); i++) {
            if (idBuscado.equals(modelo.getValueAt(i, COL_ID))) {
                modelo.setValueAt(valor, i, columna);
                return;
            }
        }
    }

    // --- BARRA LATERAL ---
    private JPanel crearBarraLateral() {
        JPanel sidebar = new EstilosUI.PanelDegradado(null, true, 0,
                EstilosUI.NEGRO, new Color(9, 18, 44), EstilosUI.AZUL_PROFUNDO);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(260, 0));
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(30, 58, 138)));

        JPanel marca = new JPanel(new BorderLayout(12, 0));
        marca.setOpaque(false);
        marca.setBorder(BorderFactory.createEmptyBorder(24, 18, 24, 12));
        JPanel textosMarca = new JPanel(new GridLayout(2, 1, 0, 2));
        textosMarca.setOpaque(false);
        JLabel nombre = new JLabel("PROYECTO INTEGRADOR");
        nombre.setFont(new Font("Segoe UI", Font.BOLD, 12));
        nombre.setForeground(Color.WHITE);
        JLabel lema = new JLabel("Monitoreo central de red");
        lema.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lema.setForeground(EstilosUI.AZUL_CLARO);
        textosMarca.add(nombre);
        textosMarca.add(lema);
        marca.add(new EstilosUI.LogoRed(), BorderLayout.WEST);
        marca.add(textosMarca, BorderLayout.CENTER);
        marca.setMaximumSize(new Dimension(260, 86));
        marca.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(marca);

        JLabel lblSeccion = new JLabel("MENÚ PRINCIPAL");
        lblSeccion.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblSeccion.setForeground(new Color(96, 165, 250));
        lblSeccion.setBorder(BorderFactory.createEmptyBorder(4, 24, 10, 10));
        lblSeccion.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(lblSeccion);

        sidebar.add(crearBotonMenu("Inicio / Dashboard", "DASHBOARD", true));
        sidebar.add(crearBotonMenu("Monitoreo de red", "MONITOREO DE RED", false));
        sidebar.add(crearBotonMenu("Mantenimiento de SO", "MANTENIMIENTO SO", false));
        sidebar.add(crearBotonMenu("Mantenimiento de red", "MANTENIMIENTO RED", false));
        sidebar.add(crearBotonMenu("Reportes", "REPORTES", false));

        sidebar.add(Box.createVerticalGlue());

        EstilosUI.Tarjeta estadoServidor = new EstilosUI.Tarjeta(new FlowLayout(FlowLayout.LEFT, 8, 8), 14, false);
        estadoServidor.setBackground(new Color(255, 255, 255, 18));
        estadoServidor.setColorBorde(new Color(255, 255, 255, 40));
        JLabel punto = new JLabel("●");
        punto.setForeground(new Color(74, 222, 128));
        JLabel infoServidor = new JLabel("Puerto " + PUERTO + "  ·  máx. " + MAX_CLIENTES + " nodos");
        infoServidor.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        infoServidor.setForeground(EstilosUI.AZUL_CLARO);
        estadoServidor.add(punto);
        estadoServidor.add(infoServidor);
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setBorder(BorderFactory.createEmptyBorder(10, 14, 18, 14));
        pie.add(estadoServidor, BorderLayout.CENTER);
        pie.setMaximumSize(new Dimension(260, 72));
        pie.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(pie);
        return sidebar;
    }

    private JButton crearBotonMenu(String texto, String claveVista, boolean seleccionado) {
        JButton btn = new EstilosUI.BotonMenu(texto);
        btn.setMaximumSize(new Dimension(260, 48));
        btn.setPreferredSize(new Dimension(260, 48));
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setBorder(BorderFactory.createEmptyBorder(0, 30, 0, 8));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        aplicarEstadoBotonMenu(btn, seleccionado);

        btn.addActionListener(e -> {
            cardLayout.show(panelContenidoCartas, claveVista);
            if ("REPORTES".equals(claveVista)) {
                refrescarReporteEnPantalla();
            }
            for (JButton b : botonesSidebar) {
                aplicarEstadoBotonMenu(b, false);
            }
            aplicarEstadoBotonMenu(btn, true);
        });
        botonesSidebar.add(btn);
        return btn;
    }

    private void aplicarEstadoBotonMenu(JButton btn, boolean seleccionado) {
        if (btn instanceof EstilosUI.BotonMenu) {
            ((EstilosUI.BotonMenu) btn).setSeleccionado(seleccionado);
        }
        btn.setForeground(seleccionado ? Color.WHITE : COLOR_TEXTO_SIDEBAR);
        btn.setFont(new Font("Segoe UI", seleccionado ? Font.BOLD : Font.PLAIN, 12));
    }

    // --- VISTA 1: DASHBOARD ---
    private JPanel crearVistaDashboard() {
        JPanel panel = new JPanel(new BorderLayout(18, 18));
        panel.setBackground(COLOR_FONDO_PRINCIPAL);
        panel.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        panel.add(crearHeaderYTitulo("PANEL DE CONTROL"), BorderLayout.NORTH);

        JPanel contenido = new JPanel();
        contenido.setOpaque(false);
        contenido.setLayout(new BoxLayout(contenido, BoxLayout.Y_AXIS));

        JPanel kpis = crearPanelMétricasKPI();
        kpis.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
        contenido.add(kpis);
        contenido.add(Box.createVerticalStrut(14));

        JPanel escaneo = crearPanelEscaneoRedQuick();
        escaneo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 105));
        contenido.add(escaneo);
        contenido.add(Box.createVerticalStrut(14));

        modeloTablaDashboard = crearModeloTabla();
        JScrollPane tabla = (JScrollPane) crearContenedorTabla(modeloTablaDashboard);
        tabla.setPreferredSize(new Dimension(0, 300));
        tabla.setMinimumSize(new Dimension(0, 190));
        tabla.setMaximumSize(new Dimension(Integer.MAX_VALUE, 340));
        contenido.add(tabla);
        contenido.add(Box.createVerticalStrut(12));

        areaLogsConsola = crearAreaConsola(false);
        JScrollPane logs = new JScrollPane(areaLogsConsola);
        logs.setBorder(crearBordeConTitulo("Actividad reciente"));
        logs.setPreferredSize(new Dimension(0, 120));
        logs.setMinimumSize(new Dimension(0, 90));
        logs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
        contenido.add(logs);

        panel.add(contenido, BorderLayout.CENTER);
        return panel;
    }

    // --- VISTA 2: MONITOREO DE RED EXTENDIDO Y DEDICADO ---
    private JPanel crearVistaMonitoreoRed() {
        JPanel panel = new JPanel(new BorderLayout(18, 18));
        panel.setBackground(COLOR_FONDO_PRINCIPAL);
        panel.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        panel.add(crearHeaderYTitulo("MONITOREO Y DIAGNÓSTICO DE RED"), BorderLayout.NORTH);

        JPanel controles = new JPanel(new BorderLayout(12, 12));
        controles.setBackground(COLOR_TARJETA);
        controles.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_BORDE),
                BorderFactory.createEmptyBorder(15, 16, 15, 16)));

        JPanel textos = new JPanel(new GridLayout(2, 1, 0, 3));
        textos.setOpaque(false);
        JLabel titulo = new JLabel("Escaneo de subred");
        titulo.setFont(new Font("Segoe UI", Font.BOLD, 15));
        titulo.setForeground(COLOR_TEXTO_BLANCO);
        JLabel ayuda = new JLabel("Usa el escaneo rápido para encontrar equipos o la telemetría para consultar un nodo conectado.");
        ayuda.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        ayuda.setForeground(COLOR_TEXTO_GRIS);
        textos.add(titulo);
        textos.add(ayuda);

        txtRangoIPVistaRed = crearCampoTexto("192.168.1.0/24");
        JButton btnSuperficial = crearBotonAccion("Escaneo rápido", COLOR_AZUL_ACENTO);
        JButton btnAvanzado = crearBotonAccion("Diagnóstico avanzado", COLOR_AZUL_OSCURO);
        btnSuperficial.addActionListener(e -> ejecutarEscaneoSuperficialRed(txtRangoIPVistaRed.getText().trim(), btnSuperficial));
        btnAvanzado.addActionListener(e -> ejecutarTelemetriaAvanzadaRed());

        JPanel acciones = new JPanel(new BorderLayout(10, 0));
        acciones.setOpaque(false);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        botones.setOpaque(false);
        botones.add(btnSuperficial);
        botones.add(btnAvanzado);
        acciones.add(txtRangoIPVistaRed, BorderLayout.CENTER);
        acciones.add(botones, BorderLayout.EAST);

        controles.add(textos, BorderLayout.NORTH);
        controles.add(acciones, BorderLayout.CENTER);

        modeloTablaRed = crearModeloTabla();
        JComponent tabla = crearContenedorTabla(modeloTablaRed);

        areaDetallesRedAmpliada = crearAreaConsola(true);
        JScrollPane resultados = new JScrollPane(areaDetallesRedAmpliada);
        resultados.setBorder(crearBordeConTitulo("Resultados y telemetría detallada"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabla, resultados);
        split.setOpaque(false);
        split.setBorder(null);
        split.setDividerLocation(260);
        split.setDividerSize(6);

        JPanel centro = new JPanel(new BorderLayout(0, 14));
        centro.setOpaque(false);
        centro.add(controles, BorderLayout.NORTH);
        centro.add(split, BorderLayout.CENTER);
        panel.add(centro, BorderLayout.CENTER);
        return panel;
    }

    private JPanel crearVistaEnConstruccion(String tituloVista) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(COLOR_FONDO_PRINCIPAL);
        JLabel lbl = new JLabel("SECCIÓN " + tituloVista + " - EN DESARROLLO", SwingConstants.CENTER);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lbl.setForeground(COLOR_TEXTO_GRIS);
        panel.add(lbl, BorderLayout.CENTER);
        return panel;
    }

    private JPanel crearPanelReportes() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        panel.setBackground(COLOR_FONDO_PRINCIPAL);

        JPanel header = new EstilosUI.PanelDegradado(new BorderLayout(), false, 20,
                EstilosUI.NEGRO, EstilosUI.AZUL_PROFUNDO, EstilosUI.AZUL);
        header.setBorder(BorderFactory.createEmptyBorder(18, 26, 18, 22));
        JLabel titulo = new JLabel("Reportes y auditoría");
        titulo.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titulo.setForeground(Color.WHITE);
        JLabel subtitulo = new JLabel("Consulta la actividad registrada y guarda una copia del reporte.");
        subtitulo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitulo.setForeground(EstilosUI.AZUL_CLARO);
        JPanel textosReporte = new JPanel(new GridLayout(2, 1, 0, 4));
        textosReporte.setOpaque(false);
        textosReporte.add(titulo);
        textosReporte.add(subtitulo);
        header.add(textosReporte, BorderLayout.CENTER);

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        botones.setOpaque(false);
        JButton btnRefrescar = crearBotonAccion("Actualizar", COLOR_MARINO);
        JButton btnExportar = crearBotonAccion("Guardar .txt", COLOR_AZUL_OSCURO);
        botones.add(btnRefrescar);
        botones.add(btnExportar);
        header.add(botones, BorderLayout.EAST);

        areaReporteEnPantalla = new JTextArea();
        areaReporteEnPantalla.setEditable(false);
        areaReporteEnPantalla.setFont(new Font("Consolas", Font.PLAIN, 13));
        areaReporteEnPantalla.setBackground(EstilosUI.NEGRO);
        areaReporteEnPantalla.setForeground(new Color(226, 232, 240));
        areaReporteEnPantalla.setMargin(new Insets(15, 15, 15, 15));
        JScrollPane scrollReporte = new JScrollPane(areaReporteEnPantalla);
        scrollReporte.setBorder(crearBordeConTitulo("Reporte del sistema"));

        panel.add(header, BorderLayout.NORTH);
        panel.add(scrollReporte, BorderLayout.CENTER);
        btnRefrescar.addActionListener(e -> refrescarReporteEnPantalla());
        btnExportar.addActionListener(e -> GeneradorReportes.exportarAArchivo(areaReporteEnPantalla.getText(), "reporte_sistema.txt"));
        return panel;
    }

    /**
     * Genera y vuelca el reporte consolidado directamente en el JTextArea de la pestaña Reportes.
     */
    public void refrescarReporteEnPantalla() {
        String logs = (areaLogsMantenimiento != null)
            ? areaLogsMantenimiento.getText()
            : "";

        String reporte =
            gestorReportes.construirReporte(logs);

        if (areaReporteEnPantalla != null) {
            areaReporteEnPantalla.setText(reporte);
            areaReporteEnPantalla.setCaretPosition(0);
        }
    }

    // --- COMPONENTES AUXILIARES ---
    private JPanel crearHeaderYTitulo(String titulo) {
        JPanel header = new EstilosUI.PanelDegradado(new BorderLayout(15, 8), false, 20,
                EstilosUI.NEGRO, EstilosUI.AZUL_PROFUNDO, EstilosUI.AZUL);
        header.setBorder(BorderFactory.createEmptyBorder(18, 26, 18, 22));

        JPanel textos = new JPanel(new GridLayout(2, 1, 0, 4));
        textos.setOpaque(false);
        JLabel lblTitulo = new JLabel(titulo);
        lblTitulo.setFont(new Font("Segoe UI", Font.BOLD, 24));
        lblTitulo.setForeground(Color.WHITE);
        JLabel subtitulo = new JLabel("Supervisa los nodos conectados y ejecuta las tareas necesarias desde un solo lugar.");
        subtitulo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitulo.setForeground(EstilosUI.AZUL_CLARO);
        textos.add(lblTitulo);
        textos.add(subtitulo);

        EstilosUI.Tarjeta estado = new EstilosUI.Tarjeta(new FlowLayout(FlowLayout.LEFT, 8, 6), 26, false);
        estado.setBackground(new Color(255, 255, 255, 30));
        estado.setColorBorde(new Color(255, 255, 255, 80));
        JLabel punto = new JLabel("●");
        punto.setForeground(new Color(74, 222, 128));
        JLabel estadoTexto = new JLabel("Sistema operativo");
        estadoTexto.setFont(new Font("Segoe UI", Font.BOLD, 11));
        estadoTexto.setForeground(Color.WHITE);
        estado.add(punto);
        estado.add(estadoTexto);
        JPanel envoltorio = new JPanel(new GridBagLayout());
        envoltorio.setOpaque(false);
        envoltorio.add(estado);

        header.add(textos, BorderLayout.CENTER);
        header.add(envoltorio, BorderLayout.EAST);
        return header;
    }

    private JPanel crearPanelMétricasKPI() {
        JPanel panelKpi = new JPanel(new GridLayout(1, 4, 14, 0));
        panelKpi.setOpaque(false);

        lblDispositivosActivos = new JLabel("0 / " + MAX_CLIENTES);
        lblLatenciaMedia = new JLabel("—");
        lblUltimoEscaneo = new JLabel("Sin escaneo");
        lblAlertasActivas = new JLabel("0");

        panelKpi.add(crearTarjetaKpi("DISPOSITIVOS ACTIVOS", lblDispositivosActivos, COLOR_AZUL_ACENTO));
        panelKpi.add(crearTarjetaKpi("LATENCIA MEDIA", lblLatenciaMedia, COLOR_AZUL_CIELO));
        panelKpi.add(crearTarjetaKpi("ÚLTIMO ESCANEO", lblUltimoEscaneo, COLOR_MARINO));
        panelKpi.add(crearTarjetaKpi("ALERTAS ACTIVAS", lblAlertasActivas, COLOR_ALERTA));
        return panelKpi;
    }

    private JPanel crearTarjetaKpi(String titulo, JLabel lblValor, Color colorAcento) {
        JPanel card = new EstilosUI.Tarjeta(new BorderLayout(12, 8));
        card.setBackground(COLOR_TARJETA);
        card.setBorder(BorderFactory.createEmptyBorder(15, 16, 18, 16));

        JPanel barra = new JPanel();
        barra.setBackground(colorAcento);
        barra.setPreferredSize(new Dimension(5, 1));

        JPanel textos = new JPanel(new GridLayout(2, 1, 0, 3));
        textos.setOpaque(false);
        JLabel lblTit = new JLabel(titulo);
        lblTit.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblTit.setForeground(COLOR_TEXTO_GRIS);
        lblValor.setFont(new Font("Segoe UI", Font.BOLD, 22));
        lblValor.setForeground(COLOR_TEXTO_BLANCO);
        textos.add(lblTit);
        textos.add(lblValor);
        card.add(barra, BorderLayout.WEST);
        card.add(textos, BorderLayout.CENTER);
        return card;
    }

    private JPanel crearPanelEscaneoRedQuick() {
        JPanel panel = new EstilosUI.Tarjeta(new BorderLayout(14, 14));
        panel.setBackground(COLOR_TARJETA);
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 21, 18));

        JPanel encabezado = new JPanel(new BorderLayout());
        encabezado.setOpaque(false);
        JLabel titulo = new JLabel("Escaneo rápido de red");
        titulo.setFont(new Font("Segoe UI", Font.BOLD, 16));
        titulo.setForeground(COLOR_TEXTO_BLANCO);
        JLabel ayuda = new JLabel("Comprueba qué dispositivos responden en el rango indicado.");
        ayuda.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        ayuda.setForeground(COLOR_TEXTO_GRIS);
        encabezado.add(titulo, BorderLayout.NORTH);
        encabezado.add(ayuda, BorderLayout.SOUTH);

        JPanel controles = new JPanel(new BorderLayout(10, 0));
        controles.setOpaque(false);
        txtRangoIPDashboard = crearCampoTexto("192.168.1.0/24");
        JButton btnEscaneo = crearBotonAccion("Iniciar escaneo", COLOR_AZUL_ACENTO);
        btnEscaneo.addActionListener(e -> ejecutarEscaneoSuperficialRed(txtRangoIPDashboard.getText().trim(), btnEscaneo));
        controles.add(txtRangoIPDashboard, BorderLayout.CENTER);
        controles.add(btnEscaneo, BorderLayout.EAST);

        panel.add(encabezado, BorderLayout.NORTH);
        panel.add(controles, BorderLayout.CENTER);
        return panel;
    }

    private JPanel crearPanelTareasMantenimiento() {
        JPanel panel = new EstilosUI.Tarjeta(new BorderLayout(10, 10));
        panel.setBackground(COLOR_TARJETA);
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 18, 15));
        JLabel lblTit = new JLabel("Acciones rápidas de sistema");
        lblTit.setFont(new Font("Segoe UI", Font.BOLD, 14));
        lblTit.setForeground(COLOR_TEXTO_BLANCO);
        comboTareas = new JComboBox<>(new String[]{"LIMPIAR_TEMP", "OPTIMIZAR_MEMORIA", "DIAGNOSTICO_SISTEMA"});
        comboTareas.setBackground(Color.WHITE);
        comboTareas.setForeground(COLOR_TEXTO_BLANCO);
        JButton btnEjecutar = crearBotonAccion("Ejecutar", COLOR_AZUL_ACENTO);
        btnEjecutar.addActionListener(e -> ejecutarAccionEnNodo());
        panel.add(lblTit, BorderLayout.NORTH);
        panel.add(comboTareas, BorderLayout.CENTER);
        panel.add(btnEjecutar, BorderLayout.EAST);
        return panel;
    }

    private JTextArea crearAreaConsola(boolean red) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(EstilosUI.NEGRO);
        area.setForeground(new Color(226, 232, 240));
        area.setFont(new Font("Consolas", Font.PLAIN, 12));
        area.setMargin(new Insets(10, 12, 10, 12));
        if (red) areaLogsMantenimientoRed = area;
        else areaLogsMantenimiento = area;
        return area;
    }

    private TitledBorder crearBordeConTitulo(String titulo) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225), 1, true), titulo,
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 11), COLOR_AZUL_OSCURO);
    }

    private JTextField crearCampoTexto(String texto) {
        JTextField campo = new JTextField(texto);
        campo.setBackground(Color.WHITE);
        campo.setForeground(COLOR_TEXTO_BLANCO);
        campo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        final javax.swing.border.Border normal = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225), 1, true),
                BorderFactory.createEmptyBorder(7, 10, 7, 10));
        final javax.swing.border.Border enfocado = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_AZUL_ACENTO, 2, true),
                BorderFactory.createEmptyBorder(6, 9, 6, 9));
        campo.setBorder(normal);
        campo.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { campo.setBorder(enfocado); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { campo.setBorder(normal); }
        });
        return campo;
    }

    private JButton crearBotonAccion(String texto, Color color) {
        JButton btn = new EstilosUI.BotonModerno(texto, color);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btn.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        return btn;
    }

    private JPanel crearVistaMantenimientoSO() {
        vistaMantenimientoActiva = "SO";
        JPanel panel = crearPanelMantenimientoBase("Mantenimiento de sistema operativo",
                "Ejecuta tareas de limpieza, optimización y diagnóstico sobre los nodos conectados.");

        JPanel tarjetas = new JPanel(new GridLayout(2, 2, 14, 14));
        tarjetas.setOpaque(false);
        tarjetas.add(crearTarjetaMantenimiento("Limpieza de temporales",
                "Elimina archivos temporales accesibles del usuario sin tocar archivos personales.", "LIMPIAR_TEMP", COLOR_AZUL_ACENTO));
        tarjetas.add(crearTarjetaMantenimiento("Optimización de memoria",
                "Libera la memoria del propio agente e informa la RAM real del nodo antes y después, con los procesos que más consumen. No cierra procesos de otras aplicaciones.", "OPTIMIZAR_MEMORIA", COLOR_AZUL_CIELO));
        tarjetas.add(crearTarjetaMantenimiento("Diagnóstico del sistema",
                "Estado del equipo, procesos que más consumen, tiempo encendido y monitoreo continuo con alertas por umbral.",
                new AccionTarjeta[]{
                        new AccionTarjeta("Diagnóstico completo", "DIAGNOSTICO_SISTEMA"),
                        new AccionTarjeta("Top procesos por CPU", "TOP_CPU"),
                        new AccionTarjeta("Top procesos por memoria", "TOP_MEMORIA"),
                        new AccionTarjeta("Tiempo encendido", "TIEMPO_ENCENDIDO"),
                        new AccionTarjeta("Monitoreo: estado y umbrales", "MONITOREO_ESTADO"),
                        new AccionTarjeta("Monitoreo: cambiar umbrales (campo)", "MONITOREO_CONFIG",
                                "Umbrales (ej.: cpu=85 ram=90 disco=90 temp=80 lat=150)",
                                "cpu=90 ram=90 disco=90 temp=85 lat=150"),
                        new AccionTarjeta("Monitoreo: activar", "MONITOREO_ON"),
                        new AccionTarjeta("Monitoreo: desactivar", "MONITOREO_OFF")
                }, COLOR_MARINO));
        tarjetas.add(crearTarjetaMantenimiento("Reporte del nodo",
                "Genera información básica del equipo para la auditoría y seguimiento del proyecto. El inventario añade fabricante, modelo, BIOS, módulos de RAM, discos, gráficos, red y aplicaciones instaladas.",
                new AccionTarjeta[]{
                        new AccionTarjeta("Reporte del nodo", "REPORTE_NODO"),
                        new AccionTarjeta("Inventario de hardware y software", "INVENTARIO")
                }, COLOR_AZUL_OSCURO));

        JPanel centro = new JPanel(new BorderLayout(0, 14));
        centro.setOpaque(false);
        centro.add(crearSelectorNodo(), BorderLayout.NORTH);
        centro.add(envolverTarjetas(tarjetas), BorderLayout.CENTER);
        centro.add(crearConsolaMantenimiento(false), BorderLayout.SOUTH);
        panel.add(centro, BorderLayout.CENTER);
        return panel;
    }

    private JPanel crearVistaMantenimientoRed() {
        vistaMantenimientoActiva = "RED";
        JPanel panel = crearPanelMantenimientoBase("Mantenimiento y diagnóstico de red",
                "Realiza comprobaciones de conectividad y solicita información de red a los nodos.");

        JPanel tarjetas = new JPanel(new GridLayout(1, 2, 14, 14));
        tarjetas.setOpaque(false);
        tarjetas.add(crearTarjetaMantenimiento("Diagnóstico de red",
                "Revisa interfaces, IP, MTU, MAC, conectividad y DNS del nodo seleccionado. También consulta los servidores DNS y el gateway, el tráfico por interfaz (bytes/s y totales) y las conexiones activas.",
                new AccionTarjeta[]{
                        new AccionTarjeta("Interfaces, IP y conectividad", "MONITOREAR_RED"),
                        new AccionTarjeta("DNS y gateway", "DNS_GATEWAY"),
                        new AccionTarjeta("Tráfico por interfaz", "TRAFICO_RED"),
                        new AccionTarjeta("Conexiones activas", "CONEXIONES_ACTIVAS")
                }, COLOR_AZUL_ACENTO));
        tarjetas.add(crearTarjetaMantenimiento("Prueba de conectividad",
                "Hace ping al gateway real del nodo y a 8.8.8.8 por separado (pérdida y latencia), con una serie corta o larga (20 muestras). Prueba puertos TCP de un host (escriba el host y los puertos en el campo de la tarjeta) y purga la caché DNS del sistema.",
                new AccionTarjeta[]{
                        new AccionTarjeta("Ping al gateway e Internet", "PING_GATEWAY"),
                        new AccionTarjeta("Pérdida y latencia (20 muestras)", "PING_EXTENDIDO"),
                        new AccionTarjeta("Prueba de puertos (host y puertos)", "PROBAR_PUERTOS",
                                "Host y puertos (ej.: 192.168.1.1 22,80,443 o 20-25)", "8.8.8.8 53,443"),
                        new AccionTarjeta("Purgar caché DNS", "FLUSH_DNS")
                }, COLOR_AZUL_CIELO));

        JPanel centro = new JPanel(new BorderLayout(0, 14));
        centro.setOpaque(false);
        centro.add(crearSelectorNodo(), BorderLayout.NORTH);
        centro.add(envolverTarjetas(tarjetas), BorderLayout.CENTER);
        centro.add(crearConsolaMantenimiento(true), BorderLayout.SOUTH);
        panel.add(centro, BorderLayout.CENTER);
        return panel;
    }

    /** Si la ventana es baja, aparece una barra de desplazamiento en vez de aplastar el texto. */
    private JScrollPane envolverTarjetas(JPanel tarjetas) {
        JScrollPane scroll = new JScrollPane(tarjetas,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JPanel crearPanelMantenimientoBase(String titulo, String descripcion) {
        JPanel panel = new JPanel(new BorderLayout(18, 18));
        panel.setBackground(COLOR_FONDO_PRINCIPAL);
        panel.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));

        JPanel header = new EstilosUI.PanelDegradado(new GridLayout(2, 1, 0, 4), false, 20,
                EstilosUI.NEGRO, EstilosUI.AZUL_PROFUNDO, EstilosUI.AZUL);
        header.setBorder(BorderFactory.createEmptyBorder(18, 26, 18, 22));
        JLabel t = new JLabel(titulo);
        t.setFont(new Font("Segoe UI", Font.BOLD, 24));
        t.setForeground(Color.WHITE);
        JLabel d = new JLabel(descripcion);
        d.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        d.setForeground(EstilosUI.AZUL_CLARO);
        header.add(t);
        header.add(d);
        panel.add(header, BorderLayout.NORTH);
        return panel;
    }

    private JPanel crearSelectorNodo() {
        JPanel panel = new EstilosUI.Tarjeta(new BorderLayout(12, 0));
        panel.setBackground(COLOR_TARJETA);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 15, 15, 15));
        JLabel lbl = new JLabel("Nodo objetivo");
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lbl.setForeground(COLOR_TEXTO_BLANCO);

        JComboBox<String> combo = new JComboBox<>();
        combo.addItem("TODOS LOS NODOS ACTIVOS");
        combo.setBackground(Color.WHITE);
        combo.setForeground(COLOR_TEXTO_BLANCO);
        combo.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        if ("SO".equals(vistaMantenimientoActiva)) {
            comboNodosObjetivoSO = combo;
        } else {
            comboNodosObjetivoRed = combo;
        }
        actualizarComboEspecifico(combo);

        JButton actualizar = crearBotonAccion("Actualizar nodos", COLOR_AZUL_ACENTO);
        actualizar.addActionListener(e -> actualizarComboEspecifico(combo));
        panel.add(lbl, BorderLayout.WEST);
        panel.add(combo, BorderLayout.CENTER);
        panel.add(actualizar, BorderLayout.EAST);
        return panel;
    }

    private void actualizarComboEspecifico(JComboBox<String> combo) {
        String seleccion = (String) combo.getSelectedItem();
        combo.removeAllItems();
        combo.addItem("TODOS LOS NODOS ACTIVOS");
        gestorNodos.obtenerNodos().stream()
                .sorted((a, b) -> Integer.compare(a.getIdCliente(), b.getIdCliente()))
                .forEach(manejador -> {
                    Nodo nodo = manejador.getNodo();
                    if (nodo != null) {
                        combo.addItem(String.format("NODO ID: %03d - %s", nodo.getId(), nodo.getNombreVisible()));
                    }
                });
        if (seleccion != null) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (seleccion.equals(combo.getItemAt(i))) {
                    combo.setSelectedIndex(i);
                    return;
                }
            }
        }
        combo.setSelectedIndex(0);
    }

    private JPanel crearConsolaMantenimiento(boolean red) {
        JTextArea area = crearAreaConsola(red);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(0, 120));
        scroll.setBorder(crearBordeConTitulo(red ? "Salida de mantenimiento de red" : "Salida de mantenimiento de SO"));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(scroll);
        return panel;
    }

    /** Acción de una tarjeta: se elige en una lista dentro de la tarjeta (sin botones extra). */
    private static final class AccionTarjeta {
        final String etiqueta;
        final String comando;
        /** Texto del campo de la tarjeta si la acción necesita un parámetro; null si no. */
        final String etiquetaParametro;
        final String valorSugerido;

        AccionTarjeta(String etiqueta, String comando) {
            this(etiqueta, comando, null, "");
        }

        AccionTarjeta(String etiqueta, String comando, String etiquetaParametro, String valorSugerido) {
            this.etiqueta = etiqueta;
            this.comando = comando;
            this.etiquetaParametro = etiquetaParametro;
            this.valorSugerido = valorSugerido;
        }

        @Override
        public String toString() {
            return etiqueta;
        }
    }

    private JPanel crearTarjetaMantenimiento(String titulo, String descripcion, String comando, Color color) {
        return crearTarjetaMantenimiento(titulo, descripcion,
                new AccionTarjeta[]{new AccionTarjeta(titulo, comando)}, color);
    }

    private JPanel crearTarjetaMantenimiento(String titulo, String descripcion, AccionTarjeta[] acciones, Color color) {
        // La vista que se está construyendo decide qué selector de nodo usa la tarjeta.
        final boolean esRed = "RED".equals(vistaMantenimientoActiva);

        JPanel tarjeta = new EstilosUI.Tarjeta(new BorderLayout(10, 10));
        tarjeta.setBackground(COLOR_TARJETA);
        tarjeta.setBorder(BorderFactory.createEmptyBorder(16, 16, 19, 16));

        JPanel encabezado = new JPanel(new BorderLayout(8, 0));
        encabezado.setOpaque(false);
        JPanel punto = new JPanel();
        punto.setBackground(color);
        punto.setPreferredSize(new Dimension(5, 1));
        JLabel t = new JLabel(titulo);
        t.setFont(new Font("Segoe UI", Font.BOLD, 14));
        t.setForeground(COLOR_TEXTO_BLANCO);
        encabezado.add(punto, BorderLayout.WEST);
        encabezado.add(t, BorderLayout.CENTER);

        JTextArea desc = new JTextArea(descripcion);
        desc.setWrapStyleWord(true);
        desc.setLineWrap(true);
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setForeground(COLOR_TEXTO_GRIS);
        desc.setFont(new Font("Segoe UI", Font.PLAIN, 11));

        // Si alguna acción necesita un parámetro (host, puertos), la tarjeta lleva un campo de texto.
        final JTextField campoParametro = java.util.Arrays.stream(acciones)
                .anyMatch(a -> a.etiquetaParametro != null) ? crearCampoTexto("") : null;
        final boolean conCampo = campoParametro != null;

        JPanel pie = new JPanel();
        pie.setOpaque(false);
        pie.setLayout(conCampo ? new BoxLayout(pie, BoxLayout.Y_AXIS) : new GridLayout(0, 1, 0, 8));

        final JComboBox<AccionTarjeta> selector = acciones.length > 1
                ? new JComboBox<>(acciones) : null;
        if (selector != null) {
            selector.setBackground(Color.WHITE);
            selector.setForeground(COLOR_TEXTO_BLANCO);
            selector.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            agregarFilaPie(pie, selector, conCampo);
        }

        if (conCampo) {
            for (AccionTarjeta a : acciones) {
                if (a.etiquetaParametro != null) {
                    campoParametro.setText(a.valorSugerido);
                    break;
                }
            }
            // El campo solo se habilita cuando la acción elegida usa parámetro.
            Runnable ajustarCampo = () -> {
                AccionTarjeta elegida = selector != null
                        ? (AccionTarjeta) selector.getSelectedItem() : acciones[0];
                boolean aplica = elegida != null && elegida.etiquetaParametro != null;
                campoParametro.setEnabled(aplica);
                campoParametro.setBackground(aplica ? Color.WHITE : new Color(241, 245, 249));
                campoParametro.setBorder(BorderFactory.createCompoundBorder(
                        crearBordeConTitulo(aplica ? elegida.etiquetaParametro
                                : "Parámetro (no aplica a esta acción)"),
                        BorderFactory.createEmptyBorder(2, 6, 4, 6)));
            };
            if (selector != null) selector.addActionListener(e -> ajustarCampo.run());
            ajustarCampo.run();
            agregarFilaPie(pie, campoParametro, true);
        }

        JButton ejecutar = crearBotonAccion("Ejecutar tarea", color);
        ejecutar.addActionListener(e -> {
            AccionTarjeta accion = selector != null
                    ? (AccionTarjeta) selector.getSelectedItem() : acciones[0];
            String comando = accion.comando;
            String nombre = accion.etiqueta;
            if (accion.etiquetaParametro != null) {
                String valor = campoParametro.getText().trim().replaceAll("\\s+", " ");
                if (valor.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                            "Escriba en el campo de la tarjeta: " + accion.etiquetaParametro + ".",
                            "Falta un parámetro", JOptionPane.WARNING_MESSAGE);
                    campoParametro.requestFocusInWindow();
                    return;
                }
                comando = comando + " " + valor;
                nombre = nombre + ": " + valor;
            }
            ejecutarTareaDesdeTarjeta(comando, nombre, esRed);
        });
        agregarFilaPie(pie, ejecutar, conCampo);

        tarjeta.add(encabezado, BorderLayout.NORTH);
        tarjeta.add(desc, BorderLayout.CENTER);
        tarjeta.add(pie, BorderLayout.SOUTH);
        // Alto mínimo según el contenido: si la ventana es baja, la descripción no se
        // aplasta contra la lista (74 = márgenes + título + separaciones).
        int alturaPie = conCampo ? 122 : (selector != null ? 68 : 32);
        int lineasDescripcion = (int) Math.ceil(descripcion.length() / 80.0);
        tarjeta.setPreferredSize(new Dimension(200, 74 + alturaPie + lineasDescripcion * 14));
        return tarjeta;
    }

    /** Agrega una fila al pie de la tarjeta (con BoxLayout hay que fijar alineación y alto). */
    private void agregarFilaPie(JPanel pie, JComponent componente, boolean modoCaja) {
        if (modoCaja) {
            componente.setAlignmentX(Component.LEFT_ALIGNMENT);
            componente.setMaximumSize(new Dimension(Integer.MAX_VALUE, componente.getPreferredSize().height));
            if (pie.getComponentCount() > 0) pie.add(Box.createVerticalStrut(8));
        }
        pie.add(componente);
    }

    private void ejecutarTareaDesdeTarjeta(String comando, String nombre, boolean esRed) {
        if (gestorNodos.cantidadNodos() == 0) {
            JOptionPane.showMessageDialog(this, "No hay ningún cliente conectado para ejecutar esta tarea.", "Sin nodos conectados", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JComboBox<String> combo = esRed ? comboNodosObjetivoRed : comboNodosObjetivoSO;
        enviarSegunSeleccion(combo, comando, nombre);
    }

    /**
     * Envía un comando al nodo elegido en el combo indicado, o a todos los
     * nodos activos si está seleccionada la opción "TODOS LOS NODOS ACTIVOS".
     */
    private void enviarSegunSeleccion(JComboBox<String> combo, String comando, String nombre) {
        int idNodo = obtenerNodoSeleccionado(combo);
        if (idNodo == NODO_NINGUNO) return;

        if (idNodo == NODO_TODOS) {
            int cantidad = gestorNodos.cantidadNodos();
            if (cantidad > 1) {
                int respuesta = JOptionPane.showConfirmDialog(this,
                        "La tarea [" + nombre + "] se ejecutará en los " + cantidad + " nodos conectados.\n¿Desea continuar?",
                        "Ejecutar en todos los nodos", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (respuesta != JOptionPane.YES_OPTION) return;
            }
            agregarLog(">>> EJECUTANDO [" + nombre + "] EN TODOS LOS NODOS (" + cantidad + ")");
            gestorMantenimiento.ejecutarEnTodosLosNodos(comando);
            return;
        }

        ManejadorCliente cliente = gestorNodos.obtenerNodo(idNodo);
        if (cliente == null) {
            JOptionPane.showMessageDialog(this, "El nodo seleccionado ya no está conectado.", "Nodo no disponible", JOptionPane.WARNING_MESSAGE);
            actualizarComboEspecifico(combo);
            return;
        }
        agregarLog(">>> EJECUTANDO [" + nombre + "] EN NODO " + String.format("%03d", idNodo));
        cliente.enviarComando(comando);
    }

    private DefaultTableModel crearModeloTabla() {
        String[] columnas = {"ID NODO", "DIRECCIÓN IP", "HOST / CLIENTE", "ESTADO", "CPU", "RAM", "LATENCIA (ms)", "ÚLTIMA CONEXIÓN"};
        return new DefaultTableModel(columnas, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private JComponent crearContenedorTabla(DefaultTableModel modelo) {
        JTable tabla = new JTable(modelo);
        tabla.setBackground(COLOR_TARJETA);
        tabla.setForeground(COLOR_TEXTO_BLANCO);
        tabla.setGridColor(COLOR_BORDE);
        tabla.setSelectionBackground(COLOR_AZUL_SUAVE);
        tabla.setSelectionForeground(COLOR_TEXTO_BLANCO);
        tabla.setRowHeight(34);
        tabla.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        tabla.setShowGrid(false);
        tabla.setIntercellSpacing(new Dimension(0, 0));

        final Color filaPar = COLOR_TARJETA;
        final Color filaImpar = new Color(246, 249, 254);

        tabla.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
                lbl.setForeground(COLOR_TEXTO_BLANCO);
                lbl.setBackground(isSelected ? COLOR_AZUL_SUAVE : (row % 2 == 0 ? filaPar : filaImpar));
                return lbl;
            }
        });

        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.getTableHeader().setPreferredSize(new Dimension(0, 38));
        tabla.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, false, false, row, column);
                lbl.setOpaque(true);
                lbl.setBackground(EstilosUI.MARINO);
                lbl.setForeground(Color.WHITE);
                lbl.setFont(new Font("Segoe UI", Font.BOLD, 10));
                lbl.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 3, 0, EstilosUI.AZUL_VIVO),
                        BorderFactory.createEmptyBorder(0, 12, 0, 12)));
                lbl.setHorizontalAlignment(column == COL_ESTADO ? SwingConstants.CENTER : SwingConstants.LEFT);
                return lbl;
            }
        });

        tabla.getColumnModel().getColumn(COL_ESTADO).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
                lbl.setHorizontalAlignment(SwingConstants.CENTER);
                lbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
                String texto = value == null ? "" : value.toString();
                if ("Online".equals(texto)) {
                    lbl.setForeground(NivelMetrica.NORMAL.getColor());
                    lbl.setText("● Online");
                } else if (texto.startsWith("ALERTA")) {
                    // Alerta confirmada por el monitoreo continuo del agente.
                    lbl.setForeground(NivelMetrica.CRITICO.getColor());
                    lbl.setText("▲ " + texto);
                } else if (texto.startsWith("ADVERTENCIA")) {
                    // Última lectura en zona ámbar o roja, aún sin alerta confirmada.
                    lbl.setForeground(NivelMetrica.ADVERTENCIA.getColor());
                    lbl.setText("▲ " + texto);
                } else {
                    // Gris para no confundir un nodo desconectado con uno en alerta.
                    lbl.setForeground(COLOR_TEXTO_GRIS);
                    lbl.setText("● Offline");
                }
                lbl.setBackground(isSelected ? COLOR_AZUL_SUAVE : (row % 2 == 0 ? filaPar : filaImpar));
                return lbl;
            }
        });

        CeldaPorcentaje celdaPorcentaje = new CeldaPorcentaje(filaPar, filaImpar, COLOR_AZUL_SUAVE);
        tabla.getColumnModel().getColumn(COL_CPU).setCellRenderer(celdaPorcentaje);
        tabla.getColumnModel().getColumn(COL_RAM).setCellRenderer(celdaPorcentaje);

        int[] anchos = {72, 104, 120, 192, 116, 116, 104, 136};
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }

        // Doble clic sobre un nodo: abre su gráfica de CPU y RAM en vivo.
        tabla.setToolTipText("Doble clic sobre un nodo para ver su gráfica de CPU y RAM en vivo");
        tabla.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                int fila = tabla.rowAtPoint(e.getPoint());
                if (fila < 0) return;
                Object id = tabla.getModel().getValueAt(tabla.convertRowIndexToModel(fila), COL_ID);
                try {
                    abrirGraficaNodo(Integer.parseInt(id.toString()));
                } catch (NumberFormatException ignored) {
                }
            }
        });

        JScrollPane scroll = new JScrollPane(tabla);
        scroll.getViewport().setBackground(COLOR_TARJETA);
        scroll.setBorder(crearBordeConTitulo("Dispositivos registrados  ·  doble clic en un nodo para ver su gráfica en vivo"));
        return scroll;
    }

    // --- ACCIONES Y ESCANEOS ---
    private void ejecutarEscaneoSuperficialRed(String rango, JButton botonInvocador) {
        agregarLog(">>> INICIANDO ESCANEO SUPERFICIAL DE SUBRED EN: " + rango);
        if (botonInvocador != null) botonInvocador.setEnabled(false);

        EscaneadorRed.escanearSubred(rango, new EscaneadorRed.EscaneoListener() {
            @Override
            public void onDispositivoEncontrado(String ip, String hostName, long latenciaMs) {
                agregarLog("  [+] Nodo activo hallado: IP=" + ip + " | Host=" + hostName + " | Latencia=" + latenciaMs + " ms");
                SwingUtilities.invokeLater(() -> {
                    if (areaDetallesRedAmpliada != null) {
                        areaDetallesRedAmpliada.append("→ [ESCANEO SUPERFICIAL] IP Detectada: " + ip + " | Host: " + hostName + " | Respuesta Ping: " + latenciaMs + " ms\n");
                    }
                });
            }

            @Override
            public void onEscaneoCompletado(int totalEncontrados) {
                SwingUtilities.invokeLater(() -> {
                    if (botonInvocador != null) botonInvocador.setEnabled(true);
                    lblUltimoEscaneo.setText(new SimpleDateFormat("HH:mm:ss").format(new Date()));
                    agregarLog("=== ESCANEO SUPERFICIAL COMPLETADO. Dispositivos hallados: " + totalEncontrados + " ===");
                });
            }
        });
    }

    private void ejecutarTelemetriaAvanzadaRed() {
        if (gestorNodos.cantidadNodos() == 0) {
            JOptionPane.showMessageDialog(this, "Debe haber al menos un Agente Cliente conectado vía Socket para solicitar Telemetría Avanzada.", "Atención", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Usa el selector "Nodo objetivo" de la vista Mantenimiento de red.
        int idNodo = obtenerNodoSeleccionado(comboNodosObjetivoRed);
        if (idNodo == NODO_NINGUNO) return;

        String destino = idNodo == NODO_TODOS
                ? "TODOS LOS NODOS"
                : "NODO [" + String.format("%03d", idNodo) + "]";

        agregarLog(">>> SOLICITANDO TELEMETRÍA AVANZADA DE RED A: " + destino);
        if (areaDetallesRedAmpliada != null) {
            areaDetallesRedAmpliada.append("\n========================================================================\n");
            areaDetallesRedAmpliada.append(" SOLICITANDO DIAGNÓSTICO PROFUNDO DE TELEMETRÍA A " + destino + "\n");
            areaDetallesRedAmpliada.append("========================================================================\n");
        }

        if (idNodo == NODO_TODOS) {
            gestorMantenimiento.ejecutarEnTodosLosNodos("MONITOREAR_RED");
            return;
        }

        ManejadorCliente cliente = gestorNodos.obtenerNodo(idNodo);
        if (cliente != null) {
            cliente.enviarComando("MONITOREAR_RED");
        }
    }

    private void ejecutarAccionEnNodo() {
        if (gestorNodos.cantidadNodos() == 0) {
            JOptionPane.showMessageDialog(this, "No hay ningún cliente conectado en este momento.", "Atención", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Usa el selector "Nodo objetivo" de la vista Mantenimiento de SO.
        String accion = (String) comboTareas.getSelectedItem();
        enviarSegunSeleccion(comboNodosObjetivoSO, accion, accion);
    }

    public int getCantidadNodos() {
        return gestorNodos.cantidadNodos();
    }

        public void registrarCliente(
            int idNodo,
            ManejadorCliente manejador,
            Nodo nodo) {

        gestorNodos.registrarNodo(
            idNodo,
            manejador
        );

        SwingUtilities.invokeLater(() -> {

            lblDispositivosActivos.setText(
                gestorNodos.cantidadNodos()
                + " / "
                + MAX_CLIENTES
            );

            String horaActual =
                new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm"
                ).format(new Date());

            String sistema =
                nodo.getSistemaOperativo();

            String sistemaCorto =
                sistema == null
                    ? "SO"
                    : sistema.split(" ")[0];

            Object[] fila = new Object[]{

                String.format(
                    "%03d",
                    nodo.getId()
                ),

                nodo.getIp(),

                nodo.getUsuario()
                    + "-"
                    + sistemaCorto,

                "Online",

                SIN_DATO,   // CPU: llega con la primera lectura (~10 s)

                SIN_DATO,   // RAM

                SIN_DATO,   // latencia

                horaActual
            };

            if (modeloTablaDashboard != null) {
            modeloTablaDashboard.addRow(fila);
            }

            if (modeloTablaRed != null) {
            modeloTablaRed.addRow(fila);
            }

            if (comboNodosObjetivoSO != null) actualizarComboEspecifico(comboNodosObjetivoSO);
            if (comboNodosObjetivoRed != null) actualizarComboEspecifico(comboNodosObjetivoRed);
        });
        }

    public void removerCliente(int idNodo) {
        ManejadorCliente manejador =
            gestorNodos.removerNodo(idNodo);

        if (manejador != null
            && manejador.getNodo() != null) {

            manejador.getNodo().desconectar();
        }

        SwingUtilities.invokeLater(() -> {

            lblDispositivosActivos.setText(
                gestorNodos.cantidadNodos()
                + " / "
                + MAX_CLIENTES
            );

            String idBuscado =
                String.format("%03d", idNodo);

            actualizarEstadoTabla(
                modeloTablaDashboard,
                idBuscado
            );

            actualizarEstadoTabla(
                modeloTablaRed,
                idBuscado
            );

            if (comboNodosObjetivoSO != null) actualizarComboEspecifico(comboNodosObjetivoSO);
            if (comboNodosObjetivoRed != null) actualizarComboEspecifico(comboNodosObjetivoRed);
            recalcularLatenciaMedia();
            recalcularAlertasActivas();

            VentanaGraficaNodo ventana = ventanasGrafica.get(idNodo);
            if (ventana != null) ventana.marcarDesconectado();
        });
    }

    private void actualizarEstadoTabla(DefaultTableModel modelo, String idBuscado) {
        if (modelo == null) return;
        for (int i = 0; i < modelo.getRowCount(); i++) {
            if (modelo.getValueAt(i, COL_ID).equals(idBuscado)) {
                modelo.setValueAt("Offline", i, COL_ESTADO);
                modelo.setValueAt(SIN_DATO, i, COL_CPU);
                modelo.setValueAt(SIN_DATO, i, COL_RAM);
                modelo.setValueAt(SIN_DATO, i, COL_LATENCIA);
                break;
            }
        }
    }

    /**
     * Lee la selección del combo "Nodo objetivo" indicado.
     *
     * @return NODO_TODOS si está elegida la opción "TODOS LOS NODOS ACTIVOS",
     *         el ID del nodo si hay uno concreto elegido y conectado, o
     *         NODO_NINGUNO si no hay selección válida (ya se avisó al usuario).
     */
    private int obtenerNodoSeleccionado(JComboBox<String> combo) {
        if (combo == null) return NODO_NINGUNO;

        Object seleccionado = combo.getSelectedItem();
        if (seleccionado == null || combo.getSelectedIndex() <= 0) {
            return NODO_TODOS;
        }

        // El texto es "NODO ID: 002 - usuario @ 192.168.1.23": solo se toma
        // el número que sigue a "NODO ID:" (no los dígitos de la IP).
        Matcher m = PATRON_ID_NODO.matcher(seleccionado.toString());
        if (m.find()) {
            try {
                int id = Integer.parseInt(m.group(1));
                if (gestorNodos.existeNodo(id)) return id;
            } catch (NumberFormatException ignored) {}
        }

        JOptionPane.showMessageDialog(this, "El nodo seleccionado ya no está conectado.", "Nodo no disponible", JOptionPane.WARNING_MESSAGE);
        actualizarComboEspecifico(combo);
        return NODO_NINGUNO;
    }

    public void agregarLog(String mensaje) {
    SwingUtilities.invokeLater(() -> {
        String hora = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String linea = "[" + hora + "] " + mensaje + "\n";
        
        if (areaLogsConsola != null) {
            areaLogsConsola.append(linea);
            areaLogsConsola.setCaretPosition(areaLogsConsola.getDocument().getLength());
        }
        if (areaDetallesRedAmpliada != null) {
            areaDetallesRedAmpliada.append(linea);
            areaDetallesRedAmpliada.setCaretPosition(areaDetallesRedAmpliada.getDocument().getLength());
        }
        if (areaLogsMantenimiento != null) {
            areaLogsMantenimiento.append(linea);
            areaLogsMantenimiento.setCaretPosition(areaLogsMantenimiento.getDocument().getLength());
        }
        if (areaLogsMantenimientoRed != null) {
            areaLogsMantenimientoRed.append(linea);
            areaLogsMantenimientoRed.setCaretPosition(areaLogsMantenimientoRed.getDocument().getLength());
        }
    });
}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {

            EstilosUI.instalarTema();

            // Seguridad de la transmisión: token + certificado TLS. Si algo falta o está
            // mal, se avisa y no se abre el servidor (nunca se escucha sin cifrado).
            ConfiguracionSeguridad seguridad;
            ServerSocket socketEscucha;
            try {
                seguridad = ConfiguracionSeguridad.cargar(ConfiguracionSeguridad.Rol.SERVIDOR);
                socketEscucha = CanalSeguro.crearServidor(PUERTO, seguridad);
            } catch (ConfiguracionSeguridad.ErrorConfiguracion e) {
                JOptionPane.showMessageDialog(null, e.getMessage(),
                        "Seguridad no configurada", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
                return;
            } catch (java.io.IOException | java.security.GeneralSecurityException e) {
                JOptionPane.showMessageDialog(null,
                        "No se pudo abrir el puerto " + PUERTO + " con TLS:\n" + e.getMessage()
                        + "\n\nRevise que el puerto no esté ocupado (otro Servidor Central abierto) "
                        + "y que seguridad/servidor/ contenga los certificados generados.",
                        "Error al iniciar el servidor", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
                return;
            }

            ServidorCentral gui = new ServidorCentral();
            gui.setVisible(true);

            ServidorSocket servidorSocket =
                    new ServidorSocket(
                            socketEscucha,
                            MAX_CLIENTES,
                            gui.siguienteIdNodo,
                            gui,
                            seguridad
                    );

            new Thread(servidorSocket).start();
        });
    }
    // --- VISTA 3: CENTRO OPERATIVO DE TAREAS DE MANTENIMIENTO ---






}