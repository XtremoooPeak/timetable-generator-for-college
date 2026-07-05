import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Central controller for all timetable operations.
 * Handles generation, validation, CRUD, CSV import, AI auto-fix, analytics, and export.
 */
public class TimetableController {

    private static final Path TIMETABLE_FILE = Path.of("backend", "data", "timetable.json");
    private static final Path COURSES_FILE   = Path.of("backend", "data", "courses.json");
    private static final Path FACULTY_FILE   = Path.of("backend", "data", "faculty.json");
    private static final Path ROOMS_FILE     = Path.of("backend", "data", "rooms.json");

    static final List<String> DAYS  = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday");
    static final List<String> SLOTS = List.of("8:00-9:00", "9:00-10:00", "10:00-11:00", "11:00-12:00",
                                               "12:00-1:00", "2:00-3:00", "3:00-4:00", "4:00-5:00");

    private static final String[] COLORS = {
        "#1a4a8a","#c0392b","#1a7a46","#b7620a","#5a2d82",
        "#0d6e7a","#e67e22","#2ecc71","#3498db","#8e44ad",
        "#16a085","#d35400","#2980b9","#27ae60"
    };

    // ─── GET /api/timetable ─────────────────────────────────────────────────────
    public static String getTimetable() throws IOException {
        if (!Files.exists(TIMETABLE_FILE)) {
            String seed = getSampleTimetableJson();
            Files.createDirectories(TIMETABLE_FILE.getParent());
            Files.writeString(TIMETABLE_FILE, seed, StandardCharsets.UTF_8);
            return seed;
        }
        return Files.readString(TIMETABLE_FILE, StandardCharsets.UTF_8);
    }

    // ─── POST /api/timetable (save) ─────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public static String saveTimetable(String timetableJson) throws IOException {
        List<Map<String, Object>> newEntries = parseList(timetableJson);
        List<Map<String, Object>> existing   = loadDataList(TIMETABLE_FILE);

        List<Map<String, Object>> merged = mergeEntries(newEntries, existing);

        List<Map<String, Object>> courses = loadDataList(COURSES_FILE);
        List<Map<String, Object>> faculty = loadDataList(FACULTY_FILE);
        List<Map<String, Object>> rooms   = loadDataList(ROOMS_FILE);

        ValidationEngine.ValidationResult validation = ValidationEngine.validate(merged, courses, faculty, rooms);

        Files.createDirectories(TIMETABLE_FILE.getParent());
        Files.writeString(TIMETABLE_FILE, JsonUtil.toJson(merged), StandardCharsets.UTF_8);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "saved");
        resp.put("count", merged.size());
        resp.put("hardViolations", validation.hardCount);
        resp.put("softViolations", validation.softCount);
        resp.put("overallScore", validation.overallScore);
        resp.put("conflicts", validation.conflicts);
        resp.put("softConstraintViolations", validation.softViolations);
        return JsonUtil.toJson(resp);
    }

    // ─── POST /api/timetable/clear ──────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public static String clearTimetable(String paramsJson) throws IOException {
        Map<String, Object> params  = (Map<String, Object>) JsonUtil.parse(paramsJson);
        String semester = params.containsKey("semester") ? params.get("semester").toString() : "All";
        String dept     = params.containsKey("dept") ? (String) params.get("dept") : "All";

        List<Map<String, Object>> existing  = loadDataList(TIMETABLE_FILE);
        List<Map<String, Object>> remaining = new ArrayList<>();

        if (!"All".equalsIgnoreCase(semester) || !"All".equalsIgnoreCase(dept)) {
            for (Map<String, Object> entry : existing) {
                String entrySem  = entry.get("semester") != null ? entry.get("semester").toString() : "";
                String entryDept = entry.get("dept") != null ? (String) entry.get("dept") : "";

                boolean matchSem  = "All".equalsIgnoreCase(semester) || semester.equals(entrySem);
                boolean matchDept = "All".equalsIgnoreCase(dept) || dept.equalsIgnoreCase(entryDept);

                if (!(matchSem && matchDept)) remaining.add(entry);
            }
        }
        // If both are "All", remaining stays empty → clears everything

        Files.createDirectories(TIMETABLE_FILE.getParent());
        Files.writeString(TIMETABLE_FILE, JsonUtil.toJson(remaining), StandardCharsets.UTF_8);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "success");
        resp.put("cleared", existing.size() - remaining.size());
        resp.put("remaining", remaining.size());
        return JsonUtil.toJson(resp);
    }

    // ─── POST /api/timetable/validate ───────────────────────────────────────────
    public static String validateTimetable(String timetableJson) throws IOException {
        List<Map<String, Object>> timetable = parseList(timetableJson);
        List<Map<String, Object>> courses   = loadDataList(COURSES_FILE);
        List<Map<String, Object>> faculty   = loadDataList(FACULTY_FILE);
        List<Map<String, Object>> rooms     = loadDataList(ROOMS_FILE);

        ValidationEngine.ValidationResult result = ValidationEngine.validate(timetable, courses, faculty, rooms);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("hardViolations", result.hardCount);
        resp.put("softViolations", result.softCount);
        resp.put("overallScore", result.overallScore);
        resp.put("conflicts", result.conflicts);
        resp.put("softConstraintViolations", result.softViolations);
        resp.put("facultyWorkload", calculateFacultyWorkload(timetable, faculty));
        resp.put("roomUtilization", calculateRoomUtilization(timetable, rooms));
        return JsonUtil.toJson(resp);
    }

    // ─── POST /api/timetable/generate ───────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public static String generateTimetable(String paramsJson) throws Exception {
        Map<String, Object> params   = (Map<String, Object>) JsonUtil.parse(paramsJson);
        String dept         = (String) params.get("dept");
        String semester     = params.get("semester").toString();
        String section      = params.getOrDefault("section", "A").toString();
        String algorithm    = params.getOrDefault("algorithm", "local").toString();
        String scheduleType = params.getOrDefault("scheduleType", "tight").toString();
        String customCommand= (String) params.get("customCommand");

        Map<String, Object> constraints = params.containsKey("constraints")
                ? (Map<String, Object>) params.get("constraints")
                : new LinkedHashMap<>();

        List<Map<String, Object>> courses = loadDataList(COURSES_FILE);
        List<Map<String, Object>> faculty = loadDataList(FACULTY_FILE);
        List<Map<String, Object>> rooms   = loadDataList(ROOMS_FILE);

        // Filter courses to target dept + semester
        List<Map<String, Object>> filteredCourses = new ArrayList<>();
        for (Map<String, Object> c : courses) {
            String cDept = (String) c.get("dept");
            String cSem  = c.get("semester") != null ? c.get("semester").toString() : "";
            if (dept.equalsIgnoreCase(cDept) && semester.equals(cSem)) {
                filteredCourses.add(c);
            }
        }

        if (filteredCourses.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "No courses found for " + dept + " Semester " + semester + ". Please add courses first.");
            err.put("timetable", List.of());
            err.put("conflicts", List.of());
            err.put("score", Map.of("hardViolations", 0, "softViolations", 0, "overallScore", 100));
            return JsonUtil.toJson(err);
        }

        String apiKey = GroqClient.getApiKey();
        boolean useAI = "ai".equalsIgnoreCase(algorithm) && !apiKey.isEmpty();

        if (useAI) {
            System.out.println("[TimetableController] Using AI generation (Groq Llama 3.3)...");
            try {
                return AIService.generate(filteredCourses, faculty, rooms, dept, semester, section,
                        constraints, customCommand, DAYS, SLOTS, scheduleType);
            } catch (Exception e) {
                System.err.println("[TimetableController] AI generation failed, falling back to local solver: " + e.getMessage());
            }
        }

        System.out.println("[TimetableController] Using local greedy solver...");
        return generateWithLocalSolver(filteredCourses, faculty, rooms, dept, semester, section, constraints, scheduleType);
    }

    // ─── POST /api/timetable/auto-fix ───────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public static String autoFixTimetable(String requestBody) throws Exception {
        Map<String, Object> body         = (Map<String, Object>) JsonUtil.parse(requestBody);
        List<Map<String, Object>> currentTimetable = (List<Map<String, Object>>) body.get("currentTimetable");
        String dept     = (String) body.get("dept");
        String semester = body.get("semester") != null ? body.get("semester").toString() : "5";

        List<Map<String, Object>> courses = loadDataList(COURSES_FILE);
        List<Map<String, Object>> faculty = loadDataList(FACULTY_FILE);
        List<Map<String, Object>> rooms   = loadDataList(ROOMS_FILE);

        List<Map<String, Object>> filteredCourses = new ArrayList<>();
        for (Map<String, Object> c : courses) {
            String cDept = (String) c.get("dept");
            String cSem  = c.get("semester") != null ? c.get("semester").toString() : "";
            if (dept != null && dept.equalsIgnoreCase(cDept) && semester.equals(cSem)) {
                filteredCourses.add(c);
            }
        }

        String apiKey = GroqClient.getApiKey();
        List<Map<String, Object>> optimizedTimetable = null;

        if (!apiKey.isEmpty()) {
            String systemPrompt = "You are an AI Timetable Optimization Engine. " +
                    "Fix ALL constraint violations in the given timetable. " +
                    "Hard Constraints: No faculty clash, no room clash, no section clash, " +
                    "no lunch break (12:00-1:00) classes, no consecutive lab blocks broken. " +
                    "Return ONLY valid JSON: {\"timetable\": [...]}";

            String userPrompt = "Fix this timetable:\n" + JsonUtil.toJson(currentTimetable) + "\n\n" +
                    "Courses: " + JsonUtil.toJson(filteredCourses) + "\n" +
                    "Faculty: " + JsonUtil.toJson(faculty) + "\n" +
                    "Rooms: " + JsonUtil.toJson(rooms) + "\n" +
                    "Dept: " + dept + ", Semester: " + semester + "\n" +
                    "Return only JSON with key 'timetable'.";

            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    System.out.println("[TimetableController] Auto-fix AI attempt " + attempt + "...");
                    String aiResponse = GroqClient.callGroq(systemPrompt, userPrompt);
                    Map<String, Object> parsed = ResponseParser.parseResponse(aiResponse);

                    // Try multiple key names
                    List<Map<String, Object>> candidate = extractListFromMap(parsed);
                    if (candidate != null && !candidate.isEmpty()) {
                        ValidationEngine.ValidationResult val = ValidationEngine.validate(candidate, courses, faculty, rooms);
                        System.out.println("[TimetableController] Auto-fix attempt " + attempt + ": " + val.hardCount + " hard violations");
                        if (val.hardCount == 0) {
                            optimizedTimetable = candidate;
                            break;
                        }
                        if (attempt == 3) optimizedTimetable = candidate; // use best effort
                    }
                } catch (Exception e) {
                    System.err.println("[TimetableController] Auto-fix attempt " + attempt + " failed: " + e.getMessage());
                }
                if (attempt < 3) Thread.sleep(2000);
            }
        }

        // Fallback: rebuild with local solver
        if (optimizedTimetable == null) {
            System.out.println("[TimetableController] AI auto-fix unavailable, rebuilding with local solver...");
            String localResult = generateWithLocalSolver(filteredCourses, faculty, rooms,
                    dept, semester, "All", Map.of("maxPerDay", 5, "lunchBreak", true, "labContiguous", true), "tight");
            Map<String, Object> localParsed = (Map<String, Object>) JsonUtil.parse(localResult);
            optimizedTimetable = (List<Map<String, Object>>) localParsed.get("timetable");
        }

        // Merge back into full timetable (preserve other dept/semester entries)
        List<Map<String, Object>> existing = loadDataList(TIMETABLE_FILE);
        List<Map<String, Object>> merged   = new ArrayList<>();
        final String targetKey = (dept + "@" + semester).toLowerCase();
        for (Map<String, Object> entry : existing) {
            String k = (entry.get("dept") + "@" + entry.get("semester")).toLowerCase();
            if (!k.equals(targetKey)) merged.add(entry);
        }
        merged.addAll(optimizedTimetable);

        ValidationEngine.ValidationResult val = ValidationEngine.validate(merged, courses, faculty, rooms);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timetable", merged);
        resp.put("hardViolations", val.hardCount);
        resp.put("softViolations", val.softCount);
        resp.put("overallScore", val.overallScore);
        resp.put("conflicts", val.conflicts);
        resp.put("softConstraintViolations", val.softViolations);
        resp.put("facultyWorkload", calculateFacultyWorkload(merged, faculty));
        resp.put("roomUtilization", calculateRoomUtilization(merged, rooms));

        Map<String, Object> scoreMap = new LinkedHashMap<>();
        scoreMap.put("hardViolations", val.hardCount);
        scoreMap.put("softViolations", val.softCount);
        scoreMap.put("overallScore", val.overallScore);
        resp.put("score", scoreMap);

        return JsonUtil.toJson(resp);
    }

    // ─── POST /api/timetable/suggest ────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public static String getSuggestions(String requestBody) throws Exception {
        Map<String, Object> body = (Map<String, Object>) JsonUtil.parse(requestBody);

        List<Map<String, Object>> courses = loadDataList(COURSES_FILE);
        List<Map<String, Object>> faculty = loadDataList(FACULTY_FILE);
        List<Map<String, Object>> rooms   = loadDataList(ROOMS_FILE);

        return getLocalSuggestions(body, courses, faculty, rooms);
    }

    // ─── POST /api/timetable/chat ────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    public static String handleChatCommand(String requestBody) throws Exception {
        Map<String, Object> body = (Map<String, Object>) JsonUtil.parse(requestBody);
        String command = (String) body.get("command");
        List<Map<String, Object>> currentTimetable = (List<Map<String, Object>>) body.get("currentTimetable");

        List<Map<String, Object>> courses = loadDataList(COURSES_FILE);
        List<Map<String, Object>> faculty = loadDataList(FACULTY_FILE);
        List<Map<String, Object>> rooms   = loadDataList(ROOMS_FILE);

        String apiKey = GroqClient.getApiKey();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("AI Chat feature requires GROQ_API_KEY to be configured.");
        }

        String systemPrompt = """
                You are an AI Timetable Assistant. You have access to the current timetable and all registries.
                The user will either ask a question about the timetable or issue a command to modify it.
                
                Respond with a JSON object matching this schema:
                {
                  "type": "update" or "question",
                  "timetable": [optional, updated timetable array if type is 'update'],
                  "answer": "your answer if type is 'question'",
                  "message": "short summary of what you did"
                }
                
                Rules:
                - For questions: set type='question', write answer in 'answer' field, do NOT change timetable.
                - For commands (add/remove/move/reschedule/swap): set type='update', update the 'timetable' array.
                - Respect all hard constraints: no faculty/room/section clashes, no lunch break classes.
                - Return valid JSON only. No markdown.
                """;

        Map<String, Object> userPayload = new LinkedHashMap<>();
        userPayload.put("command", command);
        userPayload.put("currentTimetable", currentTimetable);
        userPayload.put("courses", courses);
        userPayload.put("faculty", faculty);
        userPayload.put("rooms", rooms);
        userPayload.put("days", DAYS);
        userPayload.put("slots", SLOTS);

        String aiResponse = GroqClient.callGroq(systemPrompt, JsonUtil.toJson(userPayload));
        Map<String, Object> parsed = (Map<String, Object>) JsonUtil.parse(aiResponse);
        String type = (String) parsed.get("type");

        if ("update".equals(type)) {
            List<Map<String, Object>> updatedTimetable = extractListFromMap(parsed);
            if (updatedTimetable != null) {
                ValidationEngine.ValidationResult result = ValidationEngine.validate(updatedTimetable, courses, faculty, rooms);
                parsed.put("timetable", updatedTimetable);
                parsed.put("conflicts", result.conflicts);
                parsed.put("softConstraintViolations", result.softViolations);
                parsed.put("facultyWorkload", calculateFacultyWorkload(updatedTimetable, faculty));
                parsed.put("roomUtilization", calculateRoomUtilization(updatedTimetable, rooms));
                Map<String, Object> scoreMap = new LinkedHashMap<>();
                scoreMap.put("hardViolations", result.hardCount);
                scoreMap.put("softViolations", result.softCount);
                scoreMap.put("overallScore", result.overallScore);
                parsed.put("score", scoreMap);
            }
        }

        return JsonUtil.toJson(parsed);
    }

    // ─── GET /api/analytics ─────────────────────────────────────────────────────
    public static String getAnalytics() throws IOException {
        List<Map<String, Object>> timetable = loadDataList(TIMETABLE_FILE);
        List<Map<String, Object>> courses   = loadDataList(COURSES_FILE);
        List<Map<String, Object>> faculty   = loadDataList(FACULTY_FILE);
        List<Map<String, Object>> rooms     = loadDataList(ROOMS_FILE);

        ValidationEngine.ValidationResult validation = ValidationEngine.validate(timetable, courses, faculty, rooms);

        // Department + semester breakdown
        Map<String, Integer> deptCount = new LinkedHashMap<>();
        Map<String, Integer> semCount  = new LinkedHashMap<>();
        for (Map<String, Object> entry : timetable) {
            String dept = (String) entry.get("dept");
            String sem  = entry.get("semester") != null ? entry.get("semester").toString() : "?";
            if (dept != null) deptCount.merge(dept, 1, Integer::sum);
            semCount.merge("Sem " + sem, 1, Integer::sum);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalCourses", courses.size());
        resp.put("totalFaculty", faculty.size());
        resp.put("totalRooms", rooms.size());
        resp.put("totalScheduledSlots", timetable.size());
        resp.put("hardViolations", validation.hardCount);
        resp.put("softViolations", validation.softCount);
        resp.put("overallScore", validation.overallScore);
        resp.put("conflicts", validation.conflicts);
        resp.put("softConstraintViolations", validation.softViolations);
        resp.put("facultyWorkload", calculateFacultyWorkload(timetable, faculty));
        resp.put("roomUtilization", calculateRoomUtilization(timetable, rooms));
        resp.put("deptBreakdown", deptCount);
        resp.put("semesterBreakdown", semCount);
        return JsonUtil.toJson(resp);
    }

    // ─── GET /api/export/csv ────────────────────────────────────────────────────
    public static String exportTimetable(String format, String query) throws IOException {
        List<Map<String, Object>> timetable = loadDataList(TIMETABLE_FILE);
        List<Map<String, Object>> courses   = loadDataList(COURSES_FILE);
        List<Map<String, Object>> faculty   = loadDataList(FACULTY_FILE);

        // Build lookup maps for human-readable names
        Map<String, String> courseNames  = new HashMap<>();
        Map<String, String> facultyNames = new HashMap<>();
        for (Map<String, Object> c : courses) courseNames.put(c.get("id").toString(), (String) c.get("name"));
        for (Map<String, Object> f : faculty) facultyNames.put(f.get("id").toString(), (String) f.get("name"));

        if ("csv".equalsIgnoreCase(format)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Day,Slot,Course ID,Course Name,Faculty ID,Faculty Name,Room,Section,Semester,Dept\n");
            for (Map<String, Object> entry : timetable) {
                String courseId  = (String) entry.getOrDefault("course", "");
                String facultyId = (String) entry.getOrDefault("faculty", "");
                sb.append(csvEscape((String) entry.getOrDefault("day", ""))).append(",");
                sb.append(csvEscape((String) entry.getOrDefault("slot", ""))).append(",");
                sb.append(csvEscape(courseId)).append(",");
                sb.append(csvEscape(courseNames.getOrDefault(courseId, courseId))).append(",");
                sb.append(csvEscape(facultyId)).append(",");
                sb.append(csvEscape(facultyNames.getOrDefault(facultyId, facultyId))).append(",");
                sb.append(csvEscape((String) entry.getOrDefault("room", ""))).append(",");
                sb.append(csvEscape(entry.getOrDefault("section", "").toString())).append(",");
                sb.append(csvEscape(entry.getOrDefault("semester", "").toString())).append(",");
                sb.append(csvEscape((String) entry.getOrDefault("dept", ""))).append("\n");
            }
            return sb.toString();
        }

        // Default: JSON
        return JsonUtil.toJson(timetable);
    }

    // ─── POST /api/import/{collection} ──────────────────────────────────────────
    public static String handleCsvImport(String collection, String csvContent) throws IOException {
        String[] lines = csvContent.split("\\r?\\n");
        if (lines.length <= 1) {
            throw new IllegalArgumentException("CSV content is empty or contains header only.");
        }

        String[] headers = lines[0].split(",");
        List<Map<String, Object>> importedItems = new ArrayList<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] values = splitCsvLine(line);
            Map<String, Object> item = new LinkedHashMap<>();

            for (int h = 0; h < headers.length; h++) {
                if (h >= values.length) continue;
                String header = headers[h].trim().replace("\"", "");
                String val    = values[h].trim().replace("\"", "");

                // Numeric fields — store as integers where applicable
                if ("credits".equalsIgnoreCase(header) || "studentsCount".equalsIgnoreCase(header)) {
                    try { item.put(header, (int) Double.parseDouble(val)); }
                    catch (NumberFormatException e) { item.put(header, val); }
                } else if ("semester".equalsIgnoreCase(header)) {
                    try { item.put(header, (int) Double.parseDouble(val)); }
                    catch (NumberFormatException e) { item.put(header, val); }
                } else if ("capacity".equalsIgnoreCase(header)) {
                    try { item.put(header, (int) Double.parseDouble(val)); }
                    catch (NumberFormatException e) { item.put(header, val); }
                } else if ("sections".equalsIgnoreCase(header) || "courses".equalsIgnoreCase(header)
                        || "unavailableSlots".equalsIgnoreCase(header)) {
                    String[] arr = val.split(";");
                    List<String> list = new ArrayList<>();
                    for (String a : arr) if (!a.trim().isEmpty()) list.add(a.trim());
                    item.put(header, list);
                } else {
                    item.put(header, val);
                }
            }

            if (item.containsKey("id") && !item.get("id").toString().isBlank()) {
                importedItems.add(item);
            }
        }

        Path targetFile = switch (collection) {
            case "courses" -> COURSES_FILE;
            case "faculty" -> FACULTY_FILE;
            case "rooms"   -> ROOMS_FILE;
            default -> throw new IllegalArgumentException("Invalid import collection: " + collection);
        };

        // Merge with existing (imported overwrites by ID)
        List<Map<String, Object>> existing = loadDataList(targetFile);
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> ex : existing) map.put(ex.get("id").toString(), ex);
        for (Map<String, Object> imp : importedItems) map.put(imp.get("id").toString(), imp);

        List<Map<String, Object>> merged = new ArrayList<>(map.values());
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, JsonUtil.toJson(merged), StandardCharsets.UTF_8);

        // Sync with in-memory store if available
        try {
            SimpleBackend.loadStore();
        } catch (Exception e) {
            // store reload is best-effort
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("importedCount", importedItems.size());
        result.put("totalCount", merged.size());
        return JsonUtil.toJson(result);
    }

    // ─── Local Greedy Constraint Solver ─────────────────────────────────────────
    interface FreeChecker {
        boolean test(String facId, String roomId, String secKey, String day, String slot);
    }

    private static String generateWithLocalSolver(
            List<Map<String, Object>> courses,
            List<Map<String, Object>> faculty,
            List<Map<String, Object>> rooms,
            String dept, String semester, String section,
            Map<String, Object> constraints,
            String scheduleType
    ) {
        List<Map<String, Object>> timetable   = new ArrayList<>();
        Map<String, Integer> roomClassCount   = new HashMap<>();
        Map<String, String> courseColorMap    = new HashMap<>();
        int colorIdx = 0;

        Set<String> occupiedFac  = new HashSet<>();
        Set<String> occupiedRoom = new HashSet<>();
        Set<String> occupiedSec  = new HashSet<>();

        boolean avoidSaturday = Boolean.TRUE.equals(constraints.get("avoidSaturday"));
        int maxPerDay = constraints.containsKey("maxPerDay")
                ? ((Number) constraints.get("maxPerDay")).intValue() : 5;

        List<String> activeDays = avoidSaturday
                ? DAYS.subList(0, 5) // Mon–Fri only
                : DAYS;

        // Daily lecture counter per faculty
        Map<String, Map<String, Integer>> facultyDayCount = new HashMap<>();

        FreeChecker isFree = (facId, roomId, secKey, day, slot) -> {
            if ("12:00-1:00".equals(slot)) return false;
            if (avoidSaturday && "Saturday".equals(day)) return false;
            if (occupiedFac.contains(facId + "@" + day + "@" + slot)) return false;
            if (occupiedRoom.contains(roomId + "@" + day + "@" + slot)) return false;
            if (occupiedSec.contains(secKey + "@" + day + "@" + slot)) return false;
            // Check faculty daily max
            int dailyCount = facultyDayCount.computeIfAbsent(facId, k -> new HashMap<>())
                    .getOrDefault(day, 0);
            return dailyCount < maxPerDay;
        };

        for (Map<String, Object> course : courses) {
            String cDept = (String) course.get("dept");
            String cSem  = course.get("semester") != null ? course.get("semester").toString() : "";

            if (!dept.equalsIgnoreCase(cDept) || !semester.equals(cSem)) continue;

            List<?> cSections = course.get("sections") instanceof List<?> l ? l : List.of("A");
            String courseType = course.get("type") != null ? (String) course.get("type") : "Theory";
            int credits = course.containsKey("credits") ? ((Number) course.get("credits")).intValue() : 3;

            String courseId = (String) course.get("id");
            final int colorIdxForLambda = colorIdx;
            String courseColor = courseColorMap.computeIfAbsent(courseId, k -> COLORS[colorIdxForLambda % COLORS.length]);
            colorIdx = courseColorMap.size();

            // Find assigned faculty
            String facId = findFacultyForCourse(courseId, faculty);

            // Determine sections to schedule
            List<String> targetSecs = new ArrayList<>();
            if ("All".equalsIgnoreCase(section)) {
                for (Object s : cSections) targetSecs.add(s.toString());
            } else if (cSections.contains(section)) {
                targetSecs.add(section);
            } else {
                continue;
            }

            for (String sec : targetSecs) {
                String secKey = semester + "-" + sec;

                if ("Lab".equalsIgnoreCase(courseType)) {
                    // Schedule 2-hour contiguous lab blocks
                    int labBlocks = Math.max(1, credits / 2);
                    for (int lb = 0; lb < labBlocks; lb++) {
                        scheduleLabBlock(timetable, occupiedFac, occupiedRoom, occupiedSec,
                                roomClassCount, facultyDayCount, activeDays, isFree,
                                facId, courseId, sec, secKey, semester, dept, courseColor, scheduleType);
                    }
                } else {
                    // Schedule theory lectures (1h each)
                    int scheduled = 0;
                    outer:
                    for (String day : activeDays) {
                        for (String slot : SLOTS) {
                            if (scheduled >= credits) break outer;
                            List<Map<String, Object>> sortedRooms = sortRoomsByLoad(rooms, roomClassCount);
                            for (Map<String, Object> room : sortedRooms) {
                                if ("Lab".equalsIgnoreCase((String) room.get("type"))) continue;
                                String roomId = (String) room.get("id");
                                if (isFree.test(facId, roomId, secKey, day, slot)) {
                                    timetable.add(createEntry(day, slot, courseId, facId, roomId, sec, semester, dept, courseColor));
                                    occupy(occupiedFac, occupiedRoom, occupiedSec, facultyDayCount,
                                            facId, roomId, secKey, day, slot);
                                    roomClassCount.merge(roomId, 1, Integer::sum);
                                    scheduled++;
                                    if ("easy".equalsIgnoreCase(scheduleType)) {
                                        blockNextSlot(occupiedFac, occupiedSec, facId, secKey, day, slot);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        ValidationEngine.ValidationResult result = ValidationEngine.validate(timetable, courses, faculty, rooms);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timetable", timetable);
        resp.put("conflicts", result.conflicts);
        resp.put("softConstraintViolations", result.softViolations);
        resp.put("facultyWorkload", calculateFacultyWorkload(timetable, faculty));
        resp.put("roomUtilization", calculateRoomUtilization(timetable, rooms));
        Map<String, Object> scoreMap = new LinkedHashMap<>();
        scoreMap.put("hardViolations", result.hardCount);
        scoreMap.put("softViolations", result.softCount);
        scoreMap.put("overallScore", result.overallScore);
        resp.put("score", scoreMap);
        resp.put("generator", "Local Greedy Constraint Solver");
        resp.put("entries", timetable.size());
        return JsonUtil.toJson(resp);
    }

    private static void scheduleLabBlock(
            List<Map<String, Object>> timetable,
            Set<String> occupiedFac, Set<String> occupiedRoom, Set<String> occupiedSec,
            Map<String, Integer> roomClassCount,
            Map<String, Map<String, Integer>> facultyDayCount,
            List<String> activeDays, FreeChecker isFree,
            String facId, String courseId, String sec, String secKey,
            String semester, String dept, String courseColor, String scheduleType) {

        for (String day : activeDays) {
            for (int i = 0; i < SLOTS.size() - 1; i++) {
                String slot1 = SLOTS.get(i);
                String slot2 = SLOTS.get(i + 1);
                if ("12:00-1:00".equals(slot1) || "12:00-1:00".equals(slot2)) continue;

                List<Map<String, Object>> sortedRooms = sortRoomsByLoad(null, roomClassCount);
                // Find a lab room
                for (Map<String, Object> room : sortedRooms) {
                    if (!"Lab".equalsIgnoreCase((String) room.get("type"))) continue;
                    String roomId = (String) room.get("id");
                    if (isFree.test(facId, roomId, secKey, day, slot1) &&
                        isFree.test(facId, roomId, secKey, day, slot2)) {

                        timetable.add(createEntry(day, slot1, courseId, facId, roomId, sec, semester, dept, courseColor));
                        timetable.add(createEntry(day, slot2, courseId, facId, roomId, sec, semester, dept, courseColor));

                        occupy(occupiedFac, occupiedRoom, occupiedSec, facultyDayCount, facId, roomId, secKey, day, slot1);
                        occupy(occupiedFac, occupiedRoom, occupiedSec, facultyDayCount, facId, roomId, secKey, day, slot2);
                        roomClassCount.merge(roomId, 2, Integer::sum);

                        if ("easy".equalsIgnoreCase(scheduleType)) {
                            blockNextSlot(occupiedFac, occupiedSec, facId, secKey, day, slot2);
                        }
                        return; // Scheduled successfully
                    }
                }
            }
        }
        System.err.println("[LocalSolver] Warning: Could not schedule lab block for course " + courseId + " section " + sec);
    }

    // ─── GET /api/timetable/suggest ─────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private static String getLocalSuggestions(
            Map<String, Object> request,
            List<Map<String, Object>> courses,
            List<Map<String, Object>> faculty,
            List<Map<String, Object>> rooms
    ) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        Map<String, Object> entry = (Map<String, Object>) request.get("entry");
        List<Map<String, Object>> currentTimetable = (List<Map<String, Object>>) request.get("currentTimetable");

        if (entry == null || currentTimetable == null) return "{\"suggestions\":[]}";

        String courseId  = (String) entry.get("course");
        String facultyId = (String) entry.get("faculty");
        String targetSec = entry.getOrDefault("section", "").toString();
        String targetSem = entry.getOrDefault("semester", "").toString();

        // Determine course type
        String courseType = "Theory";
        for (Map<String, Object> c : courses) {
            if (courseId.equals(c.get("id"))) {
                courseType = c.getOrDefault("type", "Theory").toString();
                break;
            }
        }
        boolean isLab = "Lab".equalsIgnoreCase(courseType);

        Set<String> busyFac  = new HashSet<>();
        Set<String> busyRoom = new HashSet<>();
        Set<String> busySec  = new HashSet<>();

        for (Map<String, Object> item : currentTimetable) {
            String d = (String) item.get("day");
            String s = (String) item.get("slot");
            busyFac.add(item.get("faculty") + "@" + d + "@" + s);
            busyRoom.add(item.get("room") + "@" + d + "@" + s);
            busySec.add(item.get("semester") + "-" + item.get("section") + "@" + d + "@" + s);
        }

        outer:
        for (String day : DAYS) {
            for (String slot : SLOTS) {
                if ("12:00-1:00".equals(slot)) continue;
                String facKey = facultyId + "@" + day + "@" + slot;
                String secKey = targetSem + "-" + targetSec + "@" + day + "@" + slot;
                if (busyFac.contains(facKey) || busySec.contains(secKey)) continue;

                for (Map<String, Object> room : rooms) {
                    String rType = (String) room.get("type");
                    boolean match = isLab ? "Lab".equalsIgnoreCase(rType) : !"Lab".equalsIgnoreCase(rType);
                    if (!match) continue;
                    String rId = (String) room.get("id");
                    if (!busyRoom.contains(rId + "@" + day + "@" + slot)) {
                        Map<String, Object> sug = new LinkedHashMap<>();
                        sug.put("action", "Move " + courseId + " from " + entry.get("day") + " " + entry.get("slot") + " → " + day + " " + slot + " in " + rId);
                        sug.put("reason", "Faculty " + facultyId + " and section " + targetSec + " are both free. Room " + rId + " is available.");
                        sug.put("newDay", day);
                        sug.put("newSlot", slot);
                        sug.put("newRoom", rId);
                        suggestions.add(sug);
                        if (suggestions.size() >= 5) break outer;
                        break;
                    }
                }
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("suggestions", suggestions);
        return JsonUtil.toJson(resp);
    }

    // ─── Analytics ──────────────────────────────────────────────────────────────
    public static List<Map<String, Object>> calculateFacultyWorkload(
            List<Map<String, Object>> timetable, List<Map<String, Object>> facultyList) {
        List<Map<String, Object>> workloads = new ArrayList<>();

        Map<String, Integer> lectureCount = new HashMap<>();
        Map<String, Integer> labHours     = new HashMap<>();
        Map<String, Map<String, Integer>> dailyDist = new HashMap<>();

        for (Map<String, Object> entry : timetable) {
            String facId = (String) entry.get("faculty");
            String day   = (String) entry.get("day");
            String room  = (String) entry.get("room");
            if (facId == null) continue;

            lectureCount.merge(facId, 1, Integer::sum);
            if (room != null && room.toLowerCase().contains("lab")) {
                labHours.merge(facId, 1, Integer::sum);
            }
            dailyDist.computeIfAbsent(facId, k -> new HashMap<>()).merge(day, 1, Integer::sum);
        }

        int totalSlots = DAYS.size() * (SLOTS.size() - 1); // exclude lunch

        for (Map<String, Object> fac : facultyList) {
            String id    = fac.get("id").toString();
            int total    = lectureCount.getOrDefault(id, 0);
            int labs     = labHours.getOrDefault(id, 0);
            int free     = totalSlots - total;
            double utilPct = Math.round(((double) total / totalSlots) * 1000.0) / 10.0;

            Map<String, Object> wl = new LinkedHashMap<>();
            wl.put("facultyId", id);
            wl.put("facultyName", fac.get("name") != null ? fac.get("name").toString() : id);
            wl.put("dept", fac.get("dept") != null ? fac.get("dept").toString() : "");
            wl.put("designation", fac.get("designation") != null ? fac.get("designation").toString() : "");
            wl.put("totalLectures", total);
            wl.put("labHours", labs);
            wl.put("freeSlots", free);
            wl.put("busySlots", total);
            wl.put("utilizationPercent", utilPct);
            wl.put("dailyWorkload", dailyDist.getOrDefault(id, Map.of()));
            workloads.add(wl);
        }

        return workloads;
    }

    public static List<Map<String, Object>> calculateRoomUtilization(
            List<Map<String, Object>> timetable, List<Map<String, Object>> roomsList) {
        List<Map<String, Object>> utilization = new ArrayList<>();
        Map<String, Integer> usage = new HashMap<>();

        for (Map<String, Object> entry : timetable) {
            String roomId = (String) entry.get("room");
            if (roomId != null) usage.merge(roomId, 1, Integer::sum);
        }

        int totalAvailableSlots = DAYS.size() * (SLOTS.size() - 1);

        for (Map<String, Object> room : roomsList) {
            String id = room.get("id").toString();
            int busy  = usage.getOrDefault(id, 0);
            int free  = totalAvailableSlots - busy;
            double pct = Math.round(((double) busy / totalAvailableSlots) * 1000.0) / 10.0;

            Map<String, Object> ut = new LinkedHashMap<>();
            ut.put("roomId", id);
            ut.put("roomName", room.get("name") != null ? room.get("name").toString() : id);
            ut.put("type", room.get("type") != null ? room.get("type").toString() : "");
            ut.put("capacity", room.get("capacity") != null ? room.get("capacity") : 0);
            ut.put("block", room.get("block") != null ? room.get("block").toString() : "");
            ut.put("occupancyPercent", pct);
            ut.put("busySlots", busy);
            ut.put("freeSlots", free);
            utilization.add(ut);
        }

        return utilization;
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private static String findFacultyForCourse(String courseId, List<Map<String, Object>> faculty) {
        for (Map<String, Object> f : faculty) {
            Object fCourses = f.get("courses");
            if (fCourses instanceof List<?> list && list.contains(courseId)) {
                return (String) f.get("id");
            }
        }
        return faculty.isEmpty() ? "F01" : (String) faculty.get(0).get("id");
    }

    private static List<Map<String, Object>> sortRoomsByLoad(
            List<Map<String, Object>> rooms, Map<String, Integer> roomClassCount) {
        List<Map<String, Object>> all = rooms != null ? new ArrayList<>(rooms) : new ArrayList<>();
        all.sort(Comparator.comparingInt(r -> roomClassCount.getOrDefault(
                r.get("id") != null ? r.get("id").toString() : "", 0)));
        return all;
    }

    private static void occupy(Set<String> occupiedFac, Set<String> occupiedRoom, Set<String> occupiedSec,
            Map<String, Map<String, Integer>> facultyDayCount,
            String facId, String roomId, String secKey, String day, String slot) {
        occupiedFac.add(facId + "@" + day + "@" + slot);
        occupiedRoom.add(roomId + "@" + day + "@" + slot);
        occupiedSec.add(secKey + "@" + day + "@" + slot);
        facultyDayCount.computeIfAbsent(facId, k -> new HashMap<>()).merge(day, 1, Integer::sum);
    }

    private static void blockNextSlot(Set<String> occupiedFac, Set<String> occupiedSec,
            String facId, String secKey, String day, String slot) {
        int idx = SLOTS.indexOf(slot);
        if (idx >= 0 && idx + 1 < SLOTS.size()) {
            String next = SLOTS.get(idx + 1);
            occupiedFac.add(facId + "@" + day + "@" + next);
            occupiedSec.add(secKey + "@" + day + "@" + next);
        }
    }

    private static Map<String, Object> createEntry(String day, String slot, String course,
            String faculty, String room, String section, String semester, String dept, String color) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("day", day);
        entry.put("slot", slot);
        entry.put("course", course);
        entry.put("faculty", faculty);
        entry.put("room", room);
        entry.put("section", section);
        try { entry.put("semester", Integer.parseInt(semester)); }
        catch (NumberFormatException e) { entry.put("semester", semester); }
        entry.put("dept", dept);
        entry.put("color", color);
        return entry;
    }

    private static List<Map<String, Object>> mergeEntries(
            List<Map<String, Object>> newEntries, List<Map<String, Object>> existing) {
        if (newEntries.isEmpty()) return new ArrayList<>();

        Set<String> incomingCombos = new HashSet<>();
        for (Map<String, Object> e : newEntries) {
            String d   = e.get("dept") != null ? e.get("dept").toString() : "";
            String sem = e.get("semester") != null ? e.get("semester").toString() : "";
            String sec = e.get("section") != null ? e.get("section").toString() : "";
            incomingCombos.add((d + "@" + sem + "@" + sec).toLowerCase());
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        if (incomingCombos.size() == 1) {
            String targetCombo = incomingCombos.iterator().next();
            for (Map<String, Object> entry : existing) {
                String d   = entry.get("dept") != null ? entry.get("dept").toString() : "";
                String sem = entry.get("semester") != null ? entry.get("semester").toString() : "";
                String sec = entry.get("section") != null ? entry.get("section").toString() : "";
                if (!(d + "@" + sem + "@" + sec).toLowerCase().equals(targetCombo)) {
                    merged.add(entry);
                }
            }
        }
        merged.addAll(newEntries);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractListFromMap(Map<String, Object> parsed) {
        for (String key : new String[]{"timetable", "schedule", "entries", "result", "data"}) {
            Object val = parsed.get(key);
            if (val instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map) result.add((Map<String, Object>) item);
                }
                if (!result.isEmpty()) return result;
            }
        }
        return null;
    }

    static List<Map<String, Object>> loadDataList(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        String content = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) return new ArrayList<>();
        return parseList(content);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseList(String json) {
        try {
            Object val = JsonUtil.parse(json);
            if (val instanceof List<?> list) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map) result.add((Map<String, Object>) item);
                }
                return result;
            }
        } catch (Exception e) {
            System.err.println("[TimetableController] Failed to parse JSON list: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String[] splitCsvLine(String line) {
        List<String> list = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                list.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        list.add(sb.toString());
        return list.toArray(new String[0]);
    }

    private static String getSampleTimetableJson() {
        return "[" +
            "{\"day\":\"Monday\",\"slot\":\"8:00-9:00\",\"course\":\"CS301\",\"faculty\":\"F01\",\"room\":\"R101\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#1a4a8a\"}," +
            "{\"day\":\"Monday\",\"slot\":\"9:00-10:00\",\"course\":\"CS302\",\"faculty\":\"F04\",\"room\":\"R201\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#c0392b\"}," +
            "{\"day\":\"Monday\",\"slot\":\"10:00-11:00\",\"course\":\"CS301\",\"faculty\":\"F01\",\"room\":\"R101\",\"section\":\"B\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#1a4a8a\"}," +
            "{\"day\":\"Monday\",\"slot\":\"2:00-3:00\",\"course\":\"CS-LAB2\",\"faculty\":\"F04\",\"room\":\"LAB1\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#b7620a\"}," +
            "{\"day\":\"Monday\",\"slot\":\"3:00-4:00\",\"course\":\"CS-LAB2\",\"faculty\":\"F04\",\"room\":\"LAB1\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#b7620a\"}," +
            "{\"day\":\"Tuesday\",\"slot\":\"8:00-9:00\",\"course\":\"CS302\",\"faculty\":\"F04\",\"room\":\"R201\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#c0392b\"}," +
            "{\"day\":\"Tuesday\",\"slot\":\"9:00-10:00\",\"course\":\"CS301\",\"faculty\":\"F01\",\"room\":\"R101\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#1a4a8a\"}," +
            "{\"day\":\"Tuesday\",\"slot\":\"10:00-11:00\",\"course\":\"CS302\",\"faculty\":\"F04\",\"room\":\"R201\",\"section\":\"B\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#c0392b\"}," +
            "{\"day\":\"Wednesday\",\"slot\":\"8:00-9:00\",\"course\":\"CS301\",\"faculty\":\"F01\",\"room\":\"R102\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#1a4a8a\"}," +
            "{\"day\":\"Wednesday\",\"slot\":\"11:00-12:00\",\"course\":\"CS302\",\"faculty\":\"F04\",\"room\":\"R201\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#c0392b\"}," +
            "{\"day\":\"Wednesday\",\"slot\":\"2:00-3:00\",\"course\":\"CS-LAB2\",\"faculty\":\"F04\",\"room\":\"LAB2\",\"section\":\"B\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#b7620a\"}," +
            "{\"day\":\"Wednesday\",\"slot\":\"3:00-4:00\",\"course\":\"CS-LAB2\",\"faculty\":\"F04\",\"room\":\"LAB2\",\"section\":\"B\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#b7620a\"}," +
            "{\"day\":\"Thursday\",\"slot\":\"8:00-9:00\",\"course\":\"CS302\",\"faculty\":\"F04\",\"room\":\"R101\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#c0392b\"}," +
            "{\"day\":\"Thursday\",\"slot\":\"9:00-10:00\",\"course\":\"CS301\",\"faculty\":\"F01\",\"room\":\"R201\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#1a4a8a\"}," +
            "{\"day\":\"Thursday\",\"slot\":\"10:00-11:00\",\"course\":\"CS302\",\"faculty\":\"F04\",\"room\":\"R101\",\"section\":\"B\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#c0392b\"}," +
            "{\"day\":\"Friday\",\"slot\":\"8:00-9:00\",\"course\":\"CS301\",\"faculty\":\"F01\",\"room\":\"R101\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#1a4a8a\"}," +
            "{\"day\":\"Friday\",\"slot\":\"9:00-10:00\",\"course\":\"CS302\",\"faculty\":\"F04\",\"room\":\"R201\",\"section\":\"A\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#c0392b\"}," +
            "{\"day\":\"Friday\",\"slot\":\"11:00-12:00\",\"course\":\"CS301\",\"faculty\":\"F01\",\"room\":\"R101\",\"section\":\"B\",\"semester\":5,\"dept\":\"CSE\",\"color\":\"#1a4a8a\"}" +
        "]";
    }
}
