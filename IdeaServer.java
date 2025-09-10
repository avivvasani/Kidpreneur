// IdeaServer.java
// Java 11+ recommended. Uses only JDK classes (com.sun.net.httpserver).
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class IdeaServer {
    private static final Path DEFAULT_BASE_DIR = Paths.get(System.getProperty("user.home"), "Documents", "Ideas - Kidpreneur");
    
    public static void main(String[] args) throws Exception {
        int port;
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("Please enter the port number for the server: ");
            String portInput = scanner.nextLine();
            port = Integer.parseInt(portInput);
        } catch (NumberFormatException e) {
            System.err.println("❌ Invalid port number. Please enter a valid integer.");
            return;
        }

        Path baseDir = DEFAULT_BASE_DIR;
        if (args.length > 0) {
            baseDir = Paths.get(args[0]);
        }
        
        if (!Files.exists(baseDir)) Files.createDirectories(baseDir);

        System.out.println("🚀 Initializing HTTP server on port " + port);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        
        server.createContext("/submit", new SubmitHandler(baseDir));
        server.createContext("/health", exchange -> {
            addCors(exchange);
            String ok = "{\"status\":\"ok\",\"time\":\""+LocalDateTime.now()+"\"}";
            send(exchange, 200, ok, "application/json");
        });

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        System.out.println("✅ Server is running. Data will be saved to: " + baseDir.toAbsolutePath());
        System.out.println("Press Ctrl+C to stop.");

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Shutting down server gracefully...");
            server.stop(3); // wait 3 seconds to finish pending requests
            System.out.println("Server has stopped.");
        }));
    }

    // ---- HANDLERS ----

    static class SubmitHandler implements HttpHandler {
        private final Path baseDir;
        public SubmitHandler(Path baseDir) {
            this.baseDir = baseDir;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            addCors(ex);
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                send(ex, 204, "");
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "Method not allowed");
                return;
            }

            Headers reqH = ex.getRequestHeaders();
            String contentType = reqH.getFirst("Content-Type");
            if (contentType == null || !contentType.contains("multipart/form-data")) {
                send(ex, 400, "Content-Type must be multipart/form-data");
                return;
            }

            String boundary = getBoundary(contentType);
            if (boundary == null) { send(ex, 400, "Boundary not found"); return; }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream is = ex.getRequestBody();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            byte[] all = baos.toByteArray();
            baos.close();

            byte[] bBoundary = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            List<Integer> partOffsets = indexOfAll(all, bBoundary);
            if (partOffsets.size() < 2) { send(ex, 400, "No multipart parts found"); return; }

            Map<String, String> fields = new LinkedHashMap<>();
            List<UploadedFile> files = new ArrayList<>();

            for (int i = 0; i < partOffsets.size() - 1; i++) {
                int start = partOffsets.get(i) + bBoundary.length;
                if (start + 2 < all.length && all[start] == 13 && all[start+1] == 10) start += 2;
                int end = partOffsets.get(i+1) - 2;
                if (end <= start) continue;
                byte[] part = Arrays.copyOfRange(all, start, end);

                int headerEnd = indexOf(part, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                if (headerEnd < 0) continue;
                String headerText = new String(Arrays.copyOf(part, headerEnd), StandardCharsets.ISO_8859_1);
                byte[] bodyBytes = Arrays.copyOfRange(part, headerEnd + 4, part.length);

                String contentDisp = headerLine(headerText, "Content-Disposition");
                String contentTypePart = headerLine(headerText, "Content-Type");
                String name = parseDispositionParam(contentDisp, "name");
                String filename = parseDispositionParam(contentDisp, "filename");

                if (filename != null && !filename.isEmpty()) {
                    filename = sanitizeFilename(filename);
                    files.add(new UploadedFile(name, filename, contentTypePart, bodyBytes));
                } else if (name != null) {
                    String bodyText = new String(bodyBytes, StandardCharsets.UTF_8).trim();
                    fields.put(name, bodyText);
                }
            }

            String nameVal = safe(fields.getOrDefault("name", "unknown"));
            String ideaVal = safe(fields.getOrDefault("ideaName", "idea"));
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String folderName = sanitizeFilename(nameVal.replaceAll("\\s+", "_") + "_" + ideaVal.replaceAll("\\s+", "_") + "_" + timestamp);
            Path projDir = baseDir.resolve(folderName);
            Files.createDirectories(projDir);

            // Write structured data to data.json
            Map<String, Object> submissionData = new LinkedHashMap<>();
            submissionData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            submissionData.putAll(fields);

            List<Map<String, String>> fileInfo = files.stream()
                .map(uf -> Map.of("filename", uf.filename, "field", uf.field, "contentType", uf.contentType))
                .collect(Collectors.toList());
            if (!fileInfo.isEmpty()) {
                 submissionData.put("files", fileInfo);
            }

            Path jsonFile = projDir.resolve("data.json");
            try (BufferedWriter bw = Files.newBufferedWriter(jsonFile, StandardCharsets.UTF_8)) {
                bw.write(mapToJson(submissionData));
            }

            // Write data to data.txt
            Path txtFile = projDir.resolve("data.txt");
            try (BufferedWriter bw = Files.newBufferedWriter(txtFile, StandardCharsets.UTF_8)) {
                bw.write("Submission Date: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                bw.newLine();
                bw.newLine();
                for (Map.Entry<String, String> entry : fields.entrySet()) {
                    bw.write(entry.getKey() + ": " + entry.getValue());
                    bw.newLine();
                }
                if (!fileInfo.isEmpty()) {
                    bw.newLine();
                    bw.write("Submitted Files:");
                    bw.newLine();
                    for (Map<String, String> fileMap : fileInfo) {
                        bw.write("- " + fileMap.get("filename") + " (" + fileMap.get("contentType") + ")");
                        bw.newLine();
                    }
                }
            }

            for (UploadedFile uf : files) {
                Path dest = projDir.resolve(uf.filename);
                Files.write(dest, uf.content);
            }
            
            send(ex, 200, "✅ Idea stored: " + folderName);
            System.out.println("Successfully received submission: " + folderName);
        }
    }

    // ---- utilities ----

    static void addCors(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Headers", "Content-Type");
        h.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        h.add("X-Content-Type-Options", "nosniff");
        h.add("X-Frame-Options", "DENY");
        h.add("Content-Security-Policy", "default-src 'self'");
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body, "text/plain");
    }

    static void send(HttpExchange ex, int code, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static List<Integer> indexOfAll(byte[] array, byte[] target) {
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i <= array.length - target.length; i++) {
            boolean ok = true;
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) res.add(i);
        }
        return res;
    }

    static int indexOf(byte[] array, byte[] target) {
        for (int i = 0; i <= array.length - target.length; i++) {
            boolean ok = true;
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    static String getBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) return part.substring(9).replaceAll("^\"|\"$", "");
        }
        return null;
    }

    static String headerLine(String headerText, String key) {
        for (String line : headerText.split("\r\n")) {
            if (line.toLowerCase().startsWith(key.toLowerCase() + ":")) {
                return line.substring(line.indexOf(":") + 1).trim();
            }
        }
        return null;
    }

    static String parseDispositionParam(String dispo, String param) {
        if (dispo == null) return null;
        for (String part : dispo.split(";")) {
            part = part.trim();
            if (part.startsWith(param + "=")) {
                String val = part.substring(part.indexOf('=') + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                return val;
            }
        }
        return null;
    }

    static String sanitizeFilename(String in) {
        if (in == null) return "file";
        String s = in.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "_");
        s = s.replaceAll("\\s+", "_");
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    static String safe(String in) {
        return in == null ? "" : in.trim();
    }
    
    // Completely rewritten JSON serialization methods to be type-safe.
    static String mapToJson(Map<String, Object> map) throws IOException {
        return jsonValue(map);
    }
    
    private static String jsonValue(Object value) throws IOException {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + escape((String) value) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                sb.append(jsonValue(entry.getKey())).append(":").append(jsonValue(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    sb.append(",");
                }
                sb.append(jsonValue(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        // Fallback for unknown types
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    static class UploadedFile {
        String field;
        String filename;
        String contentType;
        byte[] content;

        UploadedFile(String field, String filename, String contentType, byte[] content) {
            this.field = field;
            this.filename = filename;
            this.content = content;
            this.contentType = contentType;
        }
    }
}
