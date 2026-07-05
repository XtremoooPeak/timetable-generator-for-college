import java.util.*;

/**
 * AI-driven timetable generation service using Groq Llama 3.3.
 * Implements a validation-repair loop: generate → validate → repair (up to MAX_RETRIES).
 * Falls back to deterministic local solver if AI fails.
 */
public class AIService {
    private static final int MAX_RETRIES = 5;

    @SuppressWarnings("unchecked")
    public static String generate(
            List<Map<String, Object>> courses,
            List<Map<String, Object>> faculty,
            List<Map<String, Object>> rooms,
            String dept, String semester, String section,
            Map<String, Object> constraints,
            String customCommand,
            List<String> days,
            List<String> slots,
            String scheduleType
    ) throws Exception {
        String systemPrompt = PromptBuilder.buildSystemPrompt(scheduleType);
        String userPrompt   = PromptBuilder.buildUserPrompt(
                courses, faculty, rooms, dept, semester, section,
                constraints, customCommand, days, slots);

        Map<String, Object> parsedResponse        = null;
        List<Map<String, Object>> generatedTimetable = null;
        ValidationEngine.ValidationResult validation = null;

        String currentUserPrompt = userPrompt;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            System.out.println("[AIService] === Attempt " + attempt + "/" + MAX_RETRIES + " ===");

            String aiResponse;
            try {
                aiResponse = GroqClient.callGroq(systemPrompt, currentUserPrompt);
            } catch (Exception e) {
                System.err.println("[AIService] Groq API call failed on attempt " + attempt + ": " + e.getMessage());
                if (attempt == MAX_RETRIES) throw e;
                Thread.sleep(2000L * attempt);
                continue;
            }

            // Parse the response JSON
            try {
                parsedResponse = ResponseParser.parseResponse(aiResponse);
            } catch (Exception e) {
                System.err.println("[AIService] JSON parse failed on attempt " + attempt + ": " + e.getMessage());
                // Prompt AI to fix its JSON
                currentUserPrompt = "Your previous response was not valid JSON. Parse error: " + e.getMessage() + "\n\n" +
                        "Please output ONLY a valid JSON object matching this schema:\n" +
                        "{\"timetable\": [{\"day\":\"Monday\",\"slot\":\"8:00-9:00\",\"course\":\"CS301\",\"faculty\":\"F01\",\"room\":\"R101\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#1a4a8a\"}]}\n\n" +
                        "Original task:\n" + userPrompt;
                Thread.sleep(1500L);
                continue;
            }

            // Extract timetable array
            generatedTimetable = extractTimetableArray(parsedResponse);
            if (generatedTimetable == null || generatedTimetable.isEmpty()) {
                System.err.println("[AIService] Attempt " + attempt + ": No timetable array found in response. Keys: " + parsedResponse.keySet());
                currentUserPrompt = "Your previous response was missing the 'timetable' array or it was empty.\n\n" +
                        "REQUIRED: Your response MUST have a 'timetable' key with an array of schedule entries.\n" +
                        "Original task:\n" + userPrompt;
                Thread.sleep(1500L);
                continue;
            }

            // Normalize data types (semester must be int, etc.)
            generatedTimetable = normalizeTimetableEntries(generatedTimetable, dept, semester);

            // Validate the generated timetable
            validation = ValidationEngine.validate(generatedTimetable, courses, faculty, rooms);

            System.out.println("[AIService] Attempt " + attempt + " result: " +
                    generatedTimetable.size() + " entries | " +
                    validation.hardCount + " hard violations | " +
                    validation.softCount + " soft violations | " +
                    "Score: " + validation.overallScore + "%");

            // Success conditions: 0 hard + 0 soft in first 3 attempts, 0 hard only after
            boolean success = attempt <= 3
                    ? (validation.hardCount == 0 && validation.softCount == 0)
                    : (validation.hardCount == 0);

            if (success) {
                System.out.println("[AIService] ✓ SUCCESS on attempt " + attempt);
                break;
            }

            // Build repair prompt for next attempt
            if (attempt < MAX_RETRIES) {
                currentUserPrompt = PromptBuilder.buildRepairPrompt(
                        userPrompt,
                        JsonUtil.toJson(generatedTimetable),
                        validation.conflicts,
                        validation.softViolations
                );
                Thread.sleep(2500L); // Rate limit buffer
            }
        }

        if (generatedTimetable == null) {
            throw new RuntimeException("AI failed to generate a timetable after " + MAX_RETRIES + " attempts.");
        }
        if (validation == null) {
            validation = ValidationEngine.validate(generatedTimetable, courses, faculty, rooms);
        }

        // Overwrite AI-reported scores with our validated scores (prevents hallucinated scores)
        parsedResponse.put("timetable", generatedTimetable);
        parsedResponse.put("conflicts", validation.conflicts);
        parsedResponse.put("softConstraintViolations", validation.softViolations);
        parsedResponse.put("facultyWorkload", TimetableController.calculateFacultyWorkload(generatedTimetable, faculty));
        parsedResponse.put("roomUtilization", TimetableController.calculateRoomUtilization(generatedTimetable, rooms));

        Map<String, Object> scoreMap = new LinkedHashMap<>();
        scoreMap.put("hardViolations", validation.hardCount);
        scoreMap.put("softViolations", validation.softCount);
        scoreMap.put("overallScore", validation.overallScore);
        parsedResponse.put("score", scoreMap);
        parsedResponse.put("generator", "AI (Groq Llama 3.3-70B Versatile)");
        parsedResponse.put("entries", generatedTimetable.size());

        return JsonUtil.toJson(parsedResponse);
    }

    /**
     * Safely extract the timetable array from parsed response.
     * Handles various key names the AI might use.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractTimetableArray(Map<String, Object> parsed) {
        for (String key : new String[]{"timetable", "schedule", "entries", "result", "data"}) {
            Object val = parsed.get(key);
            if (val instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map) {
                        result.add((Map<String, Object>) item);
                    }
                }
                if (!result.isEmpty()) {
                    if (!"timetable".equals(key)) {
                        System.out.println("[AIService] Note: timetable was found under key '" + key + "', normalized.");
                    }
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Normalize timetable entry data types for consistency.
     * - semester → int
     * - section → String
     * - dept → use provided dept if missing
     */
    private static List<Map<String, Object>> normalizeTimetableEntries(
            List<Map<String, Object>> entries, String dept, String semester) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        int semInt = Integer.parseInt(semester);

        for (Map<String, Object> entry : entries) {
            Map<String, Object> e = new LinkedHashMap<>(entry);

            // Semester must be int
            Object sem = e.get("semester");
            if (sem instanceof String s) {
                try { e.put("semester", Integer.parseInt(s.trim())); } catch (NumberFormatException ex) { e.put("semester", semInt); }
            } else if (sem instanceof Number n) {
                e.put("semester", n.intValue());
            } else {
                e.put("semester", semInt);
            }

            // Dept fallback
            if (e.get("dept") == null || e.get("dept").toString().isBlank()) {
                e.put("dept", dept);
            }

            // Section must be a String
            Object sec = e.get("section");
            if (sec != null) {
                e.put("section", sec.toString());
            }

            // Ensure color is set
            if (e.get("color") == null || e.get("color").toString().isBlank()) {
                e.put("color", "#1a4a8a");
            }

            normalized.add(e);
        }
        return normalized;
    }
}
