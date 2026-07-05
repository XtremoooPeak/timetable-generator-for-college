import java.util.List;
import java.util.Map;

/**
 * Builds structured, detailed prompts for the Groq Llama 3.3 timetable generation engine.
 * Uses production-grade prompt engineering with explicit constraints, schema, and examples.
 */
public class PromptBuilder {

    public static String buildSystemPrompt(String scheduleType) {
        boolean isEasy = "easy".equalsIgnoreCase(scheduleType);

        return """
                You are an expert University Timetable Scheduling Engine.
                Your ONLY output must be a single valid JSON object. Never add explanations or commentary outside the JSON.

                ═══════════════════════════════════════════════════════════
                HARD CONSTRAINTS — violating any of these is UNACCEPTABLE:
                ═══════════════════════════════════════════════════════════
                1. FACULTY CLASH: A faculty member CANNOT teach two classes at the same day+slot.
                2. ROOM CLASH: A room CANNOT host two classes at the same day+slot.
                3. STUDENT CLASH: A section CANNOT have two classes at the same day+slot.
                4. LAB IN LAB ROOM: All lab courses (type=Lab) MUST use rooms with type=Lab only.
                5. THEORY IN LECTURE ROOM: Theory courses MUST use rooms with type=Lecture or Seminar only.
                6. CONTIGUOUS LABS: Lab sessions for a course MUST be in consecutive slots (e.g., 9:00-10:00 + 10:00-11:00) on the same day.
                7. LUNCH BREAK: ABSOLUTELY NO class may be placed in the 12:00-1:00 slot.
                8. COMPLETE HOURS: Each course must be scheduled for exactly (credits) hours per week.
                9. VALID REFERENCES: Only use course IDs, faculty IDs, and room IDs from the provided registries.
                10. FACULTY AVAILABILITY: Do not schedule a faculty member in their unavailableSlots.

                ═══════════════════════════════════════════════════════════
                SOFT CONSTRAINTS — minimize violations wherever possible:
                ═══════════════════════════════════════════════════════════
                """ + (isEasy ? """
                - EASY SCHEDULE: Leave at least 1 empty slot between any two consecutive classes for the same faculty or section.
                - Avoid scheduling 3+ classes back-to-back for any faculty member.
                """ : """
                - TIGHT SCHEDULE: Pack classes efficiently with minimal idle gaps for students.
                - Distribute classes across days (avoid putting all classes of a course on a single day).
                """) + """
                - ROOM DISTRIBUTION: Spread classes across all available rooms. Do NOT place all classes in a single room.
                - AVOID LATE CLASSES: Minimize 4:00-5:00 PM slot usage.
                - WORKLOAD BALANCE: Distribute faculty teaching hours evenly across the week.
                - AVOID SATURDAY: Prefer Monday–Friday. Use Saturday only if necessary.
                - MINIMIZE GAPS: Reduce idle gaps between classes for student sections.

                ═══════════════════════════════════════════════════════════
                REQUIRED OUTPUT JSON SCHEMA (return EXACTLY this structure):
                ═══════════════════════════════════════════════════════════
                {
                  "timetable": [
                    {
                      "day": "Monday",
                      "slot": "8:00-9:00",
                      "course": "CS301",
                      "faculty": "F01",
                      "room": "R101",
                      "section": "A",
                      "semester": 5,
                      "dept": "CSE",
                      "color": "#1a4a8a"
                    }
                  ],
                  "conflicts": [],
                  "suggestions": [],
                  "analytics": {
                    "totalScheduled": 0,
                    "coveragePercent": 100
                  },
                  "score": {
                    "hardViolations": 0,
                    "softViolations": 0,
                    "overallScore": 100
                  }
                }

                ═══════════════════════════════════════════════════════════
                COLOR ASSIGNMENT RULES:
                ═══════════════════════════════════════════════════════════
                Assign a consistent hex color per unique course ID:
                Use colors from: ["#1a4a8a","#c0392b","#1a7a46","#b7620a","#5a2d82","#0d6e7a","#e67e22","#2ecc71","#3498db","#8e44ad","#16a085","#d35400"]

                ═══════════════════════════════════════════════════════════
                IMPORTANT RULES:
                ═══════════════════════════════════════════════════════════
                - The "semester" field in each timetable entry must be an INTEGER (not a string).
                - The "section" field must be a STRING (e.g., "A", "B").
                - Output ONLY valid JSON. No markdown, no code blocks, no extra text.
                - If you generate multiple sections (e.g., section="All"), generate entries for ALL sections.
                - Each entry represents ONE 1-hour slot. For a 4-credit theory course: 4 separate entries.
                - For a 2-credit lab: 2 entries grouped as consecutive slots on the same day.
                """;
    }

    public static String buildUserPrompt(
            List<Map<String, Object>> courses,
            List<Map<String, Object>> faculty,
            List<Map<String, Object>> rooms,
            String dept, String semester, String section,
            Map<String, Object> constraints,
            String customCommand,
            List<String> days,
            List<String> slots
    ) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("targetDept", dept);
        payload.put("targetSemester", Integer.parseInt(semester));
        payload.put("targetSection", section);
        payload.put("availableDays", days);
        payload.put("availableSlots", slots);
        payload.put("constraints", constraints);
        payload.put("courses", courses);
        payload.put("faculty", faculty);
        payload.put("rooms", rooms);

        if (customCommand != null && !customCommand.isBlank()) {
            payload.put("additionalInstruction", customCommand);
        }

        // Build human-readable summary to help the AI
        StringBuilder summary = new StringBuilder();
        summary.append("Generate a complete weekly timetable for:\n");
        summary.append("  Department: ").append(dept).append("\n");
        summary.append("  Semester: ").append(semester).append("\n");
        summary.append("  Section: ").append(section.equalsIgnoreCase("All") ? "ALL sections (generate entries for each section separately)" : section).append("\n");
        summary.append("  Total courses to schedule: ").append(courses.size()).append("\n");
        summary.append("  Available faculty: ").append(faculty.size()).append("\n");
        summary.append("  Available rooms: ").append(rooms.size()).append("\n\n");

        summary.append("Courses to schedule:\n");
        for (Map<String, Object> c : courses) {
            summary.append("  - ").append(c.get("id")).append(" | ").append(c.get("name"))
                   .append(" | Credits: ").append(c.get("credits"))
                   .append(" | Type: ").append(c.get("type"))
                   .append(" | Sections: ").append(c.get("sections")).append("\n");
        }

        summary.append("\nFaculty assignments:\n");
        for (Map<String, Object> f : faculty) {
            summary.append("  - ").append(f.get("id")).append(" | ").append(f.get("name"))
                   .append(" | Courses: ").append(f.get("courses")).append("\n");
        }

        summary.append("\nRemember: Respect ALL hard constraints. Return JSON only.");

        payload.put("taskSummary", summary.toString());

        return JsonUtil.toJson(payload);
    }

    /**
     * Build a repair prompt when validation finds violations.
     */
    public static String buildRepairPrompt(
            String originalUserPrompt,
            String generatedTimetableJson,
            java.util.List<java.util.Map<String, Object>> hardConflicts,
            java.util.List<java.util.Map<String, Object>> softViolations
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous timetable had CONSTRAINT VIOLATIONS that MUST be fixed.\n\n");

        if (!hardConflicts.isEmpty()) {
            sb.append("=== HARD CONSTRAINT VIOLATIONS (MUST FIX ALL) ===\n");
            for (var c : hardConflicts) {
                sb.append("  [").append(c.get("type")).append("] ")
                  .append(c.get("desc")).append(" — Day: ").append(c.get("day"))
                  .append(", Slot: ").append(c.get("slot")).append("\n");
            }
            sb.append("\n");
        }

        if (!softViolations.isEmpty()) {
            sb.append("=== SOFT CONSTRAINT VIOLATIONS (fix if possible) ===\n");
            for (var v : softViolations) {
                sb.append("  [").append(v.get("type")).append("] ").append(v.get("desc")).append("\n");
            }
            sb.append("\n");
        }

        sb.append("=== YOUR PREVIOUS TIMETABLE (fix the violations above) ===\n");
        sb.append(generatedTimetableJson).append("\n\n");

        sb.append("Fix ALL hard constraint violations. Return a corrected JSON timetable with 0 hard violations.\n");
        sb.append("IMPORTANT: Return ONLY the JSON object. No explanations.\n\n");
        sb.append("=== ORIGINAL TASK DATA ===\n");
        sb.append(originalUserPrompt);

        return sb.toString();
    }
}
