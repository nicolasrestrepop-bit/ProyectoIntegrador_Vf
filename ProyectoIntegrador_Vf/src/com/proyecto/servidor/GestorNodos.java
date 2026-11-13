package com.proyecto.servidor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Se encarga de administrar los nodos conectados
 * al servidor central.
 *
 * Esta clase separa la gestión de los clientes
 * de la interfaz y de la lógica principal del servidor.
 */
public class GestorNodos {

    private final Map<Integer, ManejadorCliente> nodosActivos;

    public GestorNodos() {
        nodosActivos =
                new ConcurrentHashMap<>();
    }

    /**
     * Registra un nodo conectado.
     */
    public void registrarNodo(
            int idNodo,
            ManejadorCliente manejador) {

        if (manejador == null) {
            throw new IllegalArgumentException(
                    "El manejador del nodo no puede ser null."
            );
        }

        nodosActivos.put(
                idNodo,
                manejador
        );
    }

    /**
     * Elimina un nodo de la lista de nodos activos.
     */
    public ManejadorCliente removerNodo(
            int idNodo) {

        return nodosActivos.remove(idNodo);
    }

    /**
     * Busca un nodo por su identificador.
     */
    public ManejadorCliente obtenerNodo(
            int idNodo) {

        return nodosActivos.get(idNodo);
    }

    /**
     * Indica si un nodo está conectado.
     */
    public boolean existeNodo(
            int idNodo) {

        return nodosActivos.containsKey(idNodo);
    }

    /**
     * Devuelve la cantidad de nodos conectados.
     */
    public int cantidadNodos() {

        return nodosActivos.size();
    }

    /**
     * Devuelve una copia de los nodos activos.
     *
     * Se devuelve una lista nueva para no exponer
     * directamente la estructura interna.
     */
    public List<ManejadorCliente> obtenerNodos() {

        return new ArrayList<>(
                nodosActivos.values()
        );
    }

    /**
     * Devuelve los nodos junto con sus identificadores.
     */
    public Map<Integer, ManejadorCliente>
            obtenerMapaNodos() {

        return Map.copyOf(
                nodosActivos
        );
    }

    /**
     * Elimina todos los nodos registrados.
     */
    public void limpiar() {

        nodosActivos.clear();
    }
}