package local.digital;

import java.util.List;

public record Entry(String id, String type, String title, String content, String level,
                    String boundaries, List<String> evidenceIds, String visibility,
                    boolean publicConfirmed, String source, String updatedAt) {
    public boolean approved() { return "public".equals(visibility) && publicConfirmed; }
    public Entry asPrivate() {
        return new Entry(id, type, title, content, level, boundaries, evidenceIds,
                "private", false, source, updatedAt);
    }
    public void validate() {
        if (blank(id) || blank(type) || blank(title) || blank(content) || blank(source) || blank(updatedAt))
            throw new IllegalArgumentException("条目必须包含 id/type/title/content/source/updatedAt");
        if (!List.of("profile", "skill", "project", "evidence", "learning", "style", "document").contains(type))
            throw new IllegalArgumentException("未知条目类型");
        if (content.length() > 100_000 || title.length() > 200 || source.length() > 500)
            throw new IllegalArgumentException("条目过长，请拆分后导入");
        java.time.LocalDate.parse(updatedAt);
    }
    public String searchableContent() {
        StringBuilder sb = new StringBuilder();
        if (content != null && !content.isEmpty()) {
            sb.append(content);
        }
        if (level != null && !level.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("熟练程度：").append(level);
        }
        if (boundaries != null && !boundaries.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("范围限制：").append(boundaries);
        }
        return sb.toString();
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
