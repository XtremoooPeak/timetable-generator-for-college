import java.util.*;

/**
 * Validates faculty workload limits, course hour completion, and section daily load.
 * All lookups are null-safe.
 */
public class WorkloadValidator implements ConstraintValidator {

    private static final List<String> SLOT_ORDER = List.of(
        "8:00-9:00", "9:00-10:00", "10:00-11:00", "11:00-12:00",
        "12:00-1:00", "2:00-3:00", "3:00-4:00", "4:00-5:00"
    );

    @Override
    public void validate(
        List<Map<String, Object>> timetable,
        List<Map<String, Object>> courses,
        List<Map<String, Object>> faculty,
        List<Map<String, Object>> rooms,
        ValidationEngine.ValidationResult result
    ) {
        if (timetable == null || timetable.isEmpty()) return;

        // Build maps (null-safe)
        Map<String, Map<String, Object>> courseMap = new HashMap<>();
        for (Map<String, Object> c : courses) {
            if (c != null && c.get("id") != null) courseMap.put(c.get("id").toString(), c);
        }

        Map<String, Map<String, Object>> facultyMap = new HashMap<>();
        for (Map<String, Object> f : faculty) {
            if (f != null && f.get("id") != null) facultyMap.put(f.get("id").toString(), f);
        }

        // Track per-section course hours: courseId@section → count
        Map<String, Integer> courseSectionHours = new HashMap<>();
        // Track faculty daily hours: facultyId@day → count
        Map<String, Integer> facultyDailyHours = new HashMap<>();
        // Track section daily slot indices: sem-section@day → sorted list
        Map<String, List<Integer>> sectionDailySlots = new HashMap<>();

        // Track which dept+semester combos appear in the timetable
        Set<String> scheduledCombos = new HashSet<>(); // courseId@section that have been scheduled

        for (Map<String, Object> entry : timetable) {
            if (entry == null) continue;
            String day      = ValidationEngine.safeStr(entry, "day");
            String slot     = ValidationEngine.safeStr(entry, "slot");
            String courseId = ValidationEngine.safeStr(entry, "course");
            String facId    = ValidationEngine.safeStr(entry, "faculty");
            String section  = entry.get("section") != null ? entry.get("section").toString() : "A";
            String semester = entry.get("semester") != null ? entry.get("semester").toString() : "?";

            if (day == null || slot == null) continue;

            // Course-section hours
            if (courseId != null) {
                String csKey = courseId + "@" + section;
                courseSectionHours.merge(csKey, 1, Integer::sum);
                scheduledCombos.add(csKey);
            }

            // Faculty daily hours
            if (facId != null) {
                facultyDailyHours.merge(facId + "@" + day, 1, Integer::sum);
            }

            // Section daily slot tracking (for back-to-back / gap detection)
            int slotIdx = SLOT_ORDER.indexOf(slot);
            if (slotIdx >= 0 && !"12:00-1:00".equals(slot)) {
                String secDayKey = semester + "-" + section + "@" + day;
                sectionDailySlots.computeIfAbsent(secDayKey, k -> new ArrayList<>()).add(slotIdx);
            }
        }

        // ── 1. Weekly course hours (only check courses that appear in the timetable) ──
        for (String csKey : scheduledCombos) {
            String[] parts = csKey.split("@", 2);
            if (parts.length < 2) continue;
            String courseId = parts[0];
            String section  = parts[1];

            Map<String, Object> course = courseMap.get(courseId);
            if (course == null) continue; // orphan entry, caught by reference validator

            int credits   = course.containsKey("credits") ? ((Number) course.get("credits")).intValue() : 3;
            int scheduled = courseSectionHours.getOrDefault(csKey, 0);

            if (scheduled < credits) {
                // Only flag as medium (not critical) — AI may not have completed all hours
                ValidationEngine.addConflict(result, "Incomplete Schedule", "medium",
                    "Course " + courseId + " Section " + section + " has " + scheduled + "/" + credits +
                    " hours scheduled (missing " + (credits - scheduled) + " hours)",
                    List.of(courseId), "Weekly", "N/A");
            } else if (scheduled > credits) {
                ValidationEngine.addConflict(result, "Over-Scheduled Course", "medium",
                    "Course " + courseId + " Section " + section + " is over-scheduled: " +
                    scheduled + "/" + credits + " hours",
                    List.of(courseId), "Weekly", "N/A");
            }
        }

        // ── 2. Faculty daily workload limit ──────────────────────────────────────
        for (Map.Entry<String, Integer> entry : facultyDailyHours.entrySet()) {
            int hrs = entry.getValue();
            if (hrs <= 5) continue; // within acceptable limit

            String[] parts  = entry.getKey().split("@", 2);
            String facId    = parts[0];
            String day      = parts.length > 1 ? parts[1] : "?";
            Map<String, Object> fac = facultyMap.get(facId);
            String facName  = (fac != null && fac.get("name") != null) ? fac.get("name").toString() : facId;

            // Check if faculty has a custom max
            int maxAllowed = 5;
            if (fac != null && fac.get("maxHoursPerDay") != null) {
                try { maxAllowed = ((Number) fac.get("maxHoursPerDay")).intValue(); } catch (Exception ignored) {}
            }

            if (hrs > maxAllowed) {
                ValidationEngine.addSoftViolation(result, "Faculty Overload",
                    facName + " has " + hrs + " teaching hours on " + day + " (max allowed: " + maxAllowed + ")");
            }
        }

        // ── 3. Section daily load analysis ───────────────────────────────────────
        for (Map.Entry<String, List<Integer>> entry : sectionDailySlots.entrySet()) {
            String key  = entry.getKey();
            List<Integer> slots = new ArrayList<>(entry.getValue());
            Collections.sort(slots);
            String[] parts = key.split("@", 2);
            String secKey = parts[0];
            String day    = parts.length > 1 ? parts[1] : "?";

            // Too many lectures per day
            if (slots.size() > 5) {
                ValidationEngine.addSoftViolation(result, "Heavy Section Load",
                    "Section " + secKey + " has " + slots.size() + " lectures on " + day + " (>5 hrs)");
            }

            // Check for long idle gaps (gap > 2 non-lunch slots)
            for (int i = 0; i < slots.size() - 1; i++) {
                int gap = slots.get(i + 1) - slots.get(i);
                // Skip the lunch break gap (slot 4 = 12:00-1:00)
                boolean spansLunch = slots.get(i) < 4 && slots.get(i + 1) > 4;
                if (!spansLunch && gap > 2) {
                    ValidationEngine.addSoftViolation(result, "Long Idle Gap",
                        "Section " + secKey + " has a gap of " + (gap - 1) + " free hour(s) on " + day);
                }
            }

            // Detect 3+ back-to-back lectures for section
            int consecutive = 1;
            for (int i = 0; i < slots.size() - 1; i++) {
                if (slots.get(i + 1) - slots.get(i) == 1) {
                    consecutive++;
                    if (consecutive > 3) {
                        ValidationEngine.addSoftViolation(result, "Back-To-Back Lectures",
                            "Section " + secKey + " has " + consecutive + "+ consecutive classes on " + day);
                        break;
                    }
                } else {
                    consecutive = 1;
                }
            }
        }
    }
}
