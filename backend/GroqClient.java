import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public class GroqClient {

    // Llama 3.3 70B Versatile for high-quality scheduling
    private static final String API_URL  = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL    = "llama-3.3-70b-versatile";
    private static final int    MAX_TOKENS = 8192;
    private static final int    MAX_RETRIES = 3;

    public static String getApiKey() {
        // 1. System environment variable
        String envKey = System.getenv("GROQ_API_KEY");
        if (envKey != null && !envKey.isBlank()) return envKey;

        // 2. Workspace .env file
        for (Path candidate : List.of(Path.of(".env"), Path.of("../.env"), Path.of("backend/.env"))) {
            try {
                if (Files.exists(candidate)) {
                    for (String line : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                        line = line.trim();
                        if (line.startsWith("GROQ_API_KEY=")) {
                            String val = line.substring("GROQ_API_KEY=".length())
                                    .replace("\"", "").replace("'", "").trim();
                            if (!val.isBlank()) return val;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[GroqClient] Error reading " + candidate + ": " + e.getMessage());
            }
        }

        return "";
    }

    /**
     * Call the Groq Llama 3.3 API with automatic retry on rate-limit / server errors.
     * Returns the content string from the first choice.
     */
    public static String callGroq(String systemPrompt, String userPrompt) throws Exception {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("GROQ_API_KEY is not set. Add it to the .env file in the project root.");
        }

        // Build request payload
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", MODEL);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.put("temperature", 0.1);

        // Force JSON output
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_object");
        requestBody.put("response_format", responseFormat);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user",   "content", userPrompt));
        requestBody.put("messages", messages);

        String jsonPayload = JsonUtil.toJson(requestBody);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // Retry loop with exponential backoff
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                System.out.println("[GroqClient] Attempt " + attempt + " → HTTP " + status);

                if (status == 200) {
                    return extractContent(response.body());
                }

                // Rate limited — back off and retry
                if (status == 429 || status == 503) {
                    long waitMs = (long) Math.pow(2, attempt) * 1500L;
                    System.err.println("[GroqClient] HTTP " + status + ", waiting " + waitMs + "ms before retry...");
                    Thread.sleep(waitMs);
                    lastException = new RuntimeException("Groq API HTTP " + status + ": " + response.body().substring(0, Math.min(200, response.body().length())));
                    continue;
                }

                // Other error — don't retry
                throw new RuntimeException("Groq API error (" + status + "): " + response.body());

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Groq API call interrupted", ie);
            } catch (RuntimeException re) {
                if (!re.getMessage().startsWith("Groq API HTTP")) throw re;
                lastException = re;
            }
        }

        throw new RuntimeException("Groq API failed after " + MAX_RETRIES + " attempts. Last error: " +
                (lastException != null ? lastException.getMessage() : "unknown"));
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(String responseBody) {
        Map<String, Object> responseMap = (Map<String, Object>) JsonUtil.parse(responseBody);
        List<?> choices = (List<?>) responseMap.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Empty choices in Groq response");
        }
        Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        String content = (String) message.get("content");
        if (content == null || content.isBlank()) {
            throw new RuntimeException("Empty content in Groq response message");
        }
        return content;
    }
}
