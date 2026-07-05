import java.util.*;

/**
 * Central validation engine for timetable constraint checking.
 * Delegates to specialized validators and applies global checks.
 * All validators are null-safe and handle missing references gracefully.
 */
public class ValidationEngine {

    public static class ValidationResult {
        public List<Map<String, Object>> conflicts     = new ArrayList<>();
        public List<Map<String, Object>> softViolations = new ArrayList<>();
        public int hardCount    = 0;
        public int softCount    = 0;
        public int overallScore = 100;
    }

    private static final List<ConstraintValidator> validators = List.of(
        new FacultyValidator(),
        new RoomValidator(),
        new SectionValidator(),
        new LabValidator(),
        new WorkloadValidator()
    );

    public static ValidationResult validate(
            List<Map<String, Object>> timetable,
            List<Map<String, Object>> coursesList,
            List<Map<String, Object>> facultyList,
            List<Map<String, Object>> roomsList
    ) {
        ValidationResult result = new ValidationResult();

        if (timetable == null || timetable.isEmpty()) {
            return result;
        }

        // Build lookup maps (null-safe)
        Map<String, Map<String, Object>> courseMap = new HashMap<>();
        for (Map<String, Object> c : coursesList) {
            if (c != null && c.get("id") != null) courseMap.put(c.get("id").toString(), c);
        }

        Map<String, Map<String, Object>> facultyMap = new HashMap<>();
        for (Map<String, Object> f : facultyList) {
            if (f != null && f.get("id") != null) facultyMap.put(f.get("id").toString(), f);
        }

        Map<String, Map<String, Object>> roomMap = new HashMap<>();
        for (Map<String, Object> r : roomsList) {
            if (r != null && r.get("id") != null) roomMap.put(r.get("id").toString(), r);
        }

        // ─── 1. Data Reference Validation ────────────────────────────────────
        for (Map<String, Object> entry : timetable) {
            if (entry == null) continue;

            String courseId  = safeStr(entry, "course");
            String facultyId = safeStr(entry, "faculty");
            String roomId    = safeStr(entry, "room");
            String day       = safeStr(entry, "day");
            String slot      = safeStr(entry, "slot");

            boolean valid = true;
            List<String> affected = new ArrayList<>();

            if (courseId == null || !courseMap.containsKey(courseId)) {
                affected.add(courseId != null ? courseId : "null");
                valid = false;
            }
            if (facultyId == null || !facultyMap.containsKey(facultyId)) {
                affected.add(facultyId != null ? facultyId : "null");
                valid = false;
            }
            if (roomId == null || !roomMap.containsKey(roomId)) {
                affected.add(roomId != null ? roomId : "null");
                valid = false;
            }

            if (!valid) {
                addConflict(result, "Data Reference Error", "high",
                    "Invalid reference — Course: " + courseId + ", Faculty: " + facultyId + ", Room: " + roomId,
                    affected, day, slot);
            }
        }

        // ─── 2. Lunch Break Hard Constraint ──────────────────────────────────
        for (Map<String, Object> entry : timetable) {
            if (entry == null) continue;
            String slot = safeStr(entry, "slot");
            if ("12:00-1:00".equals(slot)) {
                String courseId = safeStr(entry, "course");
                String section  = safeStr(entry, "section");
                addConflict(result, "Lunch Break Violation", "high",
                    "Class " + courseId + " (Section " + section + ") is scheduled during the reserved lunch break (12:00-1:00)",
                    List.of(courseId != null ? courseId : "unknown"),
                    safeStr(entry, "day"), slot);
            }
        }

        // ─── 3. Specialized Validators ────────────────────────────────────────
        for (ConstraintValidator validator : validators) {
            try {
                validator.validate(timetable, coursesList, facultyList, roomsList, result);
            } catch (Exception e) {
                System.err.println("[ValidationEngine] Validator " + validator.getClass().getSimpleName() +
                        " threw exception: " + e.getMessage());
            }
        }

        // ─── 4. Soft: Late Evening Classes (4:00-5:00) ───────────────────────
        Set<String> lateReported = new HashSet<>();
        for (Map<String, Object> entry : timetable) {
            if (entry == null) continue;
            String slot     = safeStr(entry, "slot");
            String courseId = safeStr(entry, "course");
            String section  = safeStr(entry, "section");
            String day      = safeStr(entry, "day");
            if ("4:00-5:00".equals(slot)) {
                String key = courseId + "@" + section + "@" + day;
                if (lateReported.add(key)) {
                    addSoftViolation(result, "Late Evening Class",
                        "Class " + courseId + " (Section " + section + ") is scheduled in the late evening slot 4:00-5:00 on " + day);
                }
            }
        }

        // ─── 5. Soft: Saturday Classes ────────────────────────────────────────
        Map<String, Integer> saturdayCount = new HashMap<>();
        for (Map<String, Object> entry : timetable) {
            if (entry == null) continue;
            if ("Saturday".equals(safeStr(entry, "day"))) {
                String courseId = safeStr(entry, "course");
                if (courseId != null) {
                    saturdayCount.merge(courseId, 1, Integer::sum);
                }
            }
        }
        if (!saturdayCount.isEmpty()) {
            addSoftViolation(result, "Saturday Classes",
                saturdayCount.size() + " course(s) have classes on Saturday: " + saturdayCount.keySet());
        }

        // ─── 6. Score Calculation ─────────────────────────────────────────────
        result.hardCount = result.conflicts.size();
        result.softCount = result.softViolations.size();

        int penalty = (result.hardCount * 12) + (result.softCount * 2);
        result.overallScore = Math.max(0, 100 - penalty);

        return result;
    }

    // ─── Helper Methods ────────────────────────────────────────────────────────

    public static void addConflict(ValidationResult result, String type, String severity,
            String desc, List<String> affected, String day, String slot) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("id", UUID.randomUUID().toString());
        conflict.put("type", type);
        conflict.put("severity", severity);
        conflict.put("desc", desc);
        conflict.put("affectedCourses", affected != null ? affected : List.of());
        conflict.put("day", day != null ? day : "");
        conflict.put("slot", slot != null ? slot : "");
        conflict.put("status", "open");
        result.conflicts.add(conflict);
    }

    public static void addSoftViolation(ValidationResult result, String type, String desc) {
        Map<String, Object> violation = new LinkedHashMap<>();
        violation.put("type", type);
        violation.put("desc", desc);
        result.softViolations.add(violation);
    }

    /** Null-safe string extraction from a map */
    static String safeStr(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
