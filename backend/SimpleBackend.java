import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimpleBackend {
    private static final int PORT = 8080;
    private static final Path DATA_DIR = Path.of("backend", "data");
    private static final List<String> COLLECTIONS = List.of("courses", "faculty", "rooms");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Map<String, LinkedHashMap<String, String>> store = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA_DIR);
        loadStore();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api", SimpleBackend::handleApi);
        server.setExecutor(null);
        server.start();

        System.out.println("[SimpleBackend] Server started at http://localhost:" + PORT + "/api");
        System.out.println("[SimpleBackend] Available collections: /api/courses, /api/faculty, /api/rooms");
        System.out.println("[SimpleBackend] Timetable endpoints: /api/timetable, /api/timetable/generate, /api/timetable/validate, /api/timetable/suggest");
        System.out.println("[SimpleBackend] AI endpoints: /api/timetable/auto-fix, /api/timetable/chat");
        System.out.println("[SimpleBackend] Export endpoints: /api/export/csv, /api/export/json");
        System.out.println("[SimpleBackend] Analytics: /api/analytics");
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        addCors(exchange.getResponseHeaders());

        // Handle preflight CORS — MUST return immediately after sending
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        // ─── Health ───────────────────────────────────────────────────────────
        if ("/api/health".equals(path)) {
            sendJson(exchange, 200, "{\"status\":\"ok\",\"version\":\"2.0\"}");
            return;
        }

        // ─── Analytics ────────────────────────────────────────────────────────
        if ("/api/analytics".equals(path)) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                String response = TimetableController.getAnalytics();
                sendJson(exchange, 200, response);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        // ─── Export ───────────────────────────────────────────────────────────
        if (path.startsWith("/api/export/")) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String format = path.substring("/api/export/".length());
            String query = exchange.getRequestURI().getQuery();
            try {
                String response = TimetableController.exportTimetable(format, query);
                String contentType = "csv".equalsIgnoreCase(format)
                        ? "text/csv; charset=utf-8"
                        : "application/json; charset=utf-8";
                exchange.getResponseHeaders().set("Content-Type", contentType);
                if ("csv".equalsIgnoreCase(format)) {
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"timetable.csv\"");
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        // ─── Timetable sub-routes ─────────────────────────────────────────────

        if ("/api/timetable/generate".equals(path)) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String body = readBody(exchange);
            try {
                String response = TimetableController.generateTimetable(body);
                sendJson(exchange, 200, response);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        if ("/api/timetable/validate".equals(path)) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String body = readBody(exchange);
            try {
                String response = TimetableController.validateTimetable(body);
                sendJson(exchange, 200, response);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        if ("/api/timetable/suggest".equals(path)) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String body = readBody(exchange);
            try {
                String response = TimetableController.getSuggestions(body);
                sendJson(exchange, 200, response);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        if ("/api/timetable/clear".equals(path)) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String body = readBody(exchange);
            try {
                String response = TimetableController.clearTimetable(body);
                sendJson(exchange, 200, response);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;  // ← BUG FIX: was missing, causing double-response crash
        }

        if ("/api/timetable/auto-fix".equals(path)) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String body = readBody(exchange);
            try {
                String response = TimetableController.autoFixTimetable(body);
                sendJson(exchange, 200, response);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        if ("/api/timetable/chat".equals(path)) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String body = readBody(exchange);
            try {
                String response = TimetableController.handleChatCommand(body);
                sendJson(exchange, 200, response);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        if ("/api/timetable".equals(path)) {
            String method = exchange.getRequestMethod();
            try {
                if ("GET".equals(method)) {
                    String response = TimetableController.getTimetable();
                    sendJson(exchange, 200, response);
                } else if ("POST".equals(method)) {
                    String body = readBody(exchange);
                    String response = TimetableController.saveTimetable(body);
                    sendJson(exchange, 200, response);
                } else {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        // ─── CSV Import ───────────────────────────────────────────────────────
        if (path.startsWith("/api/import/")) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            String collection = path.substring("/api/import/".length());
            String body = readBody(exchange);
            try {
                String response = TimetableController.handleCsvImport(collection, body);
                sendJson(exchange, 200, response);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
            }
            return;
        }

        // ─── CRUD Collections ─────────────────────────────────────────────────
        String[] parts = path.split("/");
        if (parts.length < 3 || !COLLECTIONS.contains(parts[2])) {
            sendJson(exchange, 404, "{\"error\":\"Unknown endpoint: " + escapeJson(path) + "\"}");
            return;
        }

        String collection = parts[2];
        String id = parts.length >= 4 ? decodePath(parts[3]) : null;
        String method = exchange.getRequestMethod();

        try {
            switch (method) {
                case "GET"    -> handleGet(exchange, collection, id);
                case "POST"   -> handlePost(exchange, collection);
                case "PUT"    -> handlePut(exchange, collection, id);
                case "DELETE" -> handleDelete(exchange, collection, id);
                default       -> sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
        } catch (Exception ex) {
            ex.printStackTrace();
            sendJson(exchange, 500, "{\"error\":\"Server error: " + escapeJson(ex.getMessage()) + "\"}");
        }
    }

    // ─── CRUD Handlers ────────────────────────────────────────────────────────

    private static void handleGet(HttpExchange exchange, String collection, String id) throws IOException {
        LinkedHashMap<String, String> items = store.get(collection);
        if (id == null) {
            sendJson(exchange, 200, "[" + String.join(",", items.values()) + "]");
            return;
        }
        String item = items.get(id);
        if (item == null) {
            sendJson(exchange, 404, "{\"error\":\"Item not found\"}");
            return;
        }
        sendJson(exchange, 200, item);
    }

    private static void handlePost(HttpExchange exchange, String collection) throws IOException {
        String body = readBody(exchange);
        String id = extractId(body);
        store.get(collection).put(id, body);
        saveCollection(collection);
        sendJson(exchange, 201, body);
    }

    private static void handlePut(HttpExchange exchange, String collection, String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Missing item id");
        }
        String body = readBody(exchange);
        String bodyId = extractId(body);
        if (!id.equals(bodyId)) {
            throw new IllegalArgumentException("Path id and JSON id must match");
        }
        store.get(collection).put(id, body);
        saveCollection(collection);
        sendJson(exchange, 200, body);
    }

    private static void handleDelete(HttpExchange exchange, String collection, String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Missing item id");
        }
        if (!store.get(collection).containsKey(id)) {
            sendJson(exchange, 404, "{\"error\":\"Item not found\"}");
            return;
        }
        store.get(collection).remove(id);
        saveCollection(collection);
        sendJson(exchange, 200, "{\"deleted\":\"" + escapeJson(id) + "\"}");
    }

    // ─── Store I/O ────────────────────────────────────────────────────────────

    static void loadStore() throws IOException {
        for (String collection : COLLECTIONS) {
            LinkedHashMap<String, String> items = new LinkedHashMap<>();
            Path file = DATA_DIR.resolve(collection + ".json");

            if (Files.exists(file)) {
                for (String object : splitJsonObjects(Files.readString(file, StandardCharsets.UTF_8))) {
                    try {
                        items.put(extractId(object), object);
                    } catch (IllegalArgumentException e) {
                        System.err.println("[SimpleBackend] Skipping invalid object in " + collection + ": " + e.getMessage());
                    }
                }
            } else {
                for (String object : seedData(collection)) {
                    items.put(extractId(object), object);
                }
            }

            store.put(collection, items);
            saveCollection(collection);
        }
        System.out.println("[SimpleBackend] Store loaded: " +
                store.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue().size())
                        .reduce((a, b) -> a + ", " + b).orElse("empty"));
    }

    static void saveCollection(String collection) throws IOException {
        Files.writeString(
            DATA_DIR.resolve(collection + ".json"),
            "[\n  " + String.join(",\n  ", store.get(collection).values()) + "\n]\n",
            StandardCharsets.UTF_8
        );
    }

    static List<String> splitJsonObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\' && inString) { escaped = true; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (ch == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static String extractId(String json) {
        Matcher matcher = ID_PATTERN.matcher(json);
        if (!matcher.find() || matcher.group(1).isBlank()) {
            throw new IllegalArgumentException("JSON item must contain a non-empty string id field");
        }
        return matcher.group(1);
    }

    // ─── HTTP Utilities ───────────────────────────────────────────────────────

    private static String readBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Request body is empty");
        }
        return body;
    }

    private static void addCors(Headers headers) {
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");
        headers.add("Access-Control-Max-Age", "86400");
    }

    static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String decodePath(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    static String escapeJson(String value) {
        if (value == null) return "null";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ─── Seed Data ────────────────────────────────────────────────────────────

    private static List<String> seedData(String collection) {
        return switch (collection) {
            case "courses" -> List.of(
                "{\"id\":\"CS201\",\"name\":\"Data Structures\",\"credits\":4,\"dept\":\"CSE\",\"type\":\"Theory\",\"semester\":3,\"sections\":[\"A\",\"B\"]}",
                "{\"id\":\"CS301\",\"name\":\"Algorithms\",\"credits\":4,\"dept\":\"CSE\",\"type\":\"Theory\",\"semester\":5,\"sections\":[\"A\",\"B\"]}",
                "{\"id\":\"CS302\",\"name\":\"Database Systems\",\"credits\":4,\"dept\":\"CSE\",\"type\":\"Theory\",\"semester\":5,\"sections\":[\"A\",\"B\"]}",
                "{\"id\":\"CS401\",\"name\":\"Machine Learning\",\"credits\":3,\"dept\":\"CSE\",\"type\":\"Theory\",\"semester\":7,\"sections\":[\"A\"]}",
                "{\"id\":\"CS402\",\"name\":\"Computer Networks\",\"credits\":4,\"dept\":\"CSE\",\"type\":\"Theory\",\"semester\":7,\"sections\":[\"A\",\"B\"]}",
                "{\"id\":\"CS501\",\"name\":\"AI & Deep Learning\",\"credits\":3,\"dept\":\"CSE\",\"type\":\"Theory\",\"semester\":7,\"sections\":[\"A\"]}",
                "{\"id\":\"CS-LAB1\",\"name\":\"DS Lab\",\"credits\":2,\"dept\":\"CSE\",\"type\":\"Lab\",\"semester\":3,\"sections\":[\"A\",\"B\"]}",
                "{\"id\":\"CS-LAB2\",\"name\":\"DB Lab\",\"credits\":2,\"dept\":\"CSE\",\"type\":\"Lab\",\"semester\":5,\"sections\":[\"A\",\"B\"]}",
                "{\"id\":\"IT301\",\"name\":\"Web Technologies\",\"credits\":4,\"dept\":\"IT\",\"type\":\"Theory\",\"semester\":5,\"sections\":[\"A\"]}",
                "{\"id\":\"IT201\",\"name\":\"OS Concepts\",\"credits\":4,\"dept\":\"IT\",\"type\":\"Theory\",\"semester\":3,\"sections\":[\"A\"]}"
            );
            case "faculty" -> List.of(
                "{\"id\":\"F01\",\"name\":\"Dr. Priya Sharma\",\"dept\":\"CSE\",\"designation\":\"Associate Professor\",\"courses\":[\"CS301\",\"CS401\"],\"maxHoursPerDay\":5,\"unavailableSlots\":[]}",
                "{\"id\":\"F02\",\"name\":\"Dr. Amit Gupta\",\"dept\":\"CSE\",\"designation\":\"Professor\",\"courses\":[\"CS201\",\"CS501\"],\"maxHoursPerDay\":5,\"unavailableSlots\":[]}",
                "{\"id\":\"F03\",\"name\":\"Dr. Neha Joshi\",\"dept\":\"IT\",\"designation\":\"Assistant Professor\",\"courses\":[\"IT301\",\"IT201\"],\"maxHoursPerDay\":5,\"unavailableSlots\":[]}",
                "{\"id\":\"F04\",\"name\":\"Prof. Rajesh Kumar\",\"dept\":\"CSE\",\"designation\":\"Assistant Professor\",\"courses\":[\"CS302\",\"CS402\",\"CS-LAB1\",\"CS-LAB2\"],\"maxHoursPerDay\":5,\"unavailableSlots\":[]}"
            );
            case "rooms" -> List.of(
                "{\"id\":\"R101\",\"name\":\"Room 101\",\"type\":\"Lecture\",\"capacity\":60,\"block\":\"A\"}",
                "{\"id\":\"R102\",\"name\":\"Room 102\",\"type\":\"Lecture\",\"capacity\":60,\"block\":\"A\"}",
                "{\"id\":\"R201\",\"name\":\"Room 201\",\"type\":\"Lecture\",\"capacity\":80,\"block\":\"B\"}",
                "{\"id\":\"R202\",\"name\":\"Room 202\",\"type\":\"Lecture\",\"capacity\":80,\"block\":\"B\"}",
                "{\"id\":\"LAB1\",\"name\":\"CS Lab 1\",\"type\":\"Lab\",\"capacity\":40,\"block\":\"C\"}",
                "{\"id\":\"LAB2\",\"name\":\"CS Lab 2\",\"type\":\"Lab\",\"capacity\":40,\"block\":\"C\"}",
                "{\"id\":\"SEM1\",\"name\":\"Seminar Hall\",\"type\":\"Seminar\",\"capacity\":120,\"block\":\"D\"}"
            );
            default -> List.of();
        };
    }
}
