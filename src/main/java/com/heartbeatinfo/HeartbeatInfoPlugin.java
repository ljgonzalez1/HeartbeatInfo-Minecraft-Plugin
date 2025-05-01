package com.heartbeatinfo;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HeartbeatInfoPlugin
 *
 * Crea un servidor HTTP embebido que responde dinámicamente según config.yml:
 *  /heartbeat -> JSON con los campos definidos en config en endpoints.heartbeat
 *  /info      -> JSON con los campos definidos en config en endpoints.info
 *
 * El plugin genera automáticamente la carpeta de datos y configura config.yml por defecto
 * si no existe, usando saveDefaultConfig().
 */
public class HeartbeatInfoPlugin extends JavaPlugin {
    private HttpServer httpServer;
    // Patrón para detectar variables %var% o %var,format%
    private static final Pattern VAR_PATTERN = Pattern.compile("%([^,%]+)(?:,([^%]+))?%");

    @Override
    public void onEnable() {
        // Asegurar carpeta de datos y config por defecto
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        saveDefaultConfig();

        FileConfiguration config = getConfig();
        ConfigurationSection root = config.getConfigurationSection("HeartbeatInfoConfig");
        if (root == null) {
            getLogger().severe("[HeartbeatInfo] No se encontró sección HeartbeatInfoConfig en config.yml");
            return;
        }

        int port = root.getInt("ApiPort", 8081);
        ConfigurationSection endpoints = root.getConfigurationSection("endpoints");
        if (endpoints == null) {
            getLogger().warning("[HeartbeatInfo] No se encontró sección endpoints en config.yml");
        }

        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.setExecutor(Executors.newCachedThreadPool());

            // Registrar handlers dinámicamente
            if (endpoints != null) {
                for (String name : endpoints.getKeys(false)) {
                    List<?> list = endpoints.getList(name);
                    if (list != null && !list.isEmpty()) {
                        httpServer.createContext("/" + name, new DynamicHandler(list));
                        getLogger().info("[HeartbeatInfo] Endpoint '/" + name + "' registrado");
                    }
                }
            }

            httpServer.start();
            getLogger().info("[HeartbeatInfo] HTTP server started on port " + port);
        } catch (IOException e) {
            getLogger().severe("[HeartbeatInfo] Error al iniciar HTTP server: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
            getLogger().info("[HeartbeatInfo] HTTP server stopped");
        }
    }

    /**
     * Handler dinámico que construye JSON según configuración.
     */
    private static class DynamicHandler implements HttpHandler {
        private final List<?> configEntries;

        DynamicHandler(List<?> configEntries) {
            this.configEntries = configEntries;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // Valores base a reemplazar
            int online = Bukkit.getOnlinePlayers().size();
            int capacity = Bukkit.getMaxPlayers();
            double tps = getServerTPS();
            String motd = Bukkit.getMotd().replace("\"", "\\\"");
            String status = "online";

            try {
                StringBuilder sb = new StringBuilder();
                sb.append('{');
                boolean first = true;
                // Cada elemento de la lista es un Map con una sola entrada
                for (Object item : configEntries) {
                    if (!(item instanceof Map)) continue;
                    Map<?,?> map = (Map<?,?>) item;
                    for (Map.Entry<?,?> entry : map.entrySet()) {
                        String key = entry.getKey().toString();
                        String raw = entry.getValue().toString();

                        String value;
                        Matcher m = VAR_PATTERN.matcher(raw);
                        if (m.matches()) {
                            String var = m.group(1);
                            String fmt = m.group(2);
                            value = resolveVariable(var, fmt, online, capacity, tps, motd, status);
                        } else {
                            value = raw;
                            if (!isNumeric(raw) && !"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
                                value = '"' + raw + '"';
                            }
                        }

                        if (!first) sb.append(','); else first = false;
                        sb.append('"').append(key).append('"').append(':').append(value);
                    }
                }
                sb.append('}');

                byte[] resp = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        }

        private static boolean isNumeric(String s) {
            try { Double.parseDouble(s); return true; } catch (Exception e) { return false; }
        }

        private static String resolveVariable(String var, String fmt, int online, int capacity, double tps, String motd, String status) {
            switch (var) {
                case "online": return String.valueOf(online);
                case "capacity": return String.valueOf(capacity);
                case "status": return '"' + status + '"';
                case "tps": {
                    double v = tps;
                    if (fmt != null) {
                        return String.format(Locale.US, "%" + fmt, v);
                    }
                    return String.format(Locale.US, "%.2f", v);
                }
                case "motd": return '"' + motd + '"';
                default: return "null";
            }
        }

        private static double getServerTPS() {
            try {
                Object server = Bukkit.getServer();
                Class<?> paperClass = Class.forName("com.destroystokyo.paper.PaperServer");
                if (paperClass.isInstance(server)) {
                    double[] t = (double[]) paperClass.getMethod("getTPS").invoke(server);
                    return t[0];
                }
            } catch (Throwable ignored) {}
            return -1;
        }
    }
}
