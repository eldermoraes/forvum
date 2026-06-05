package ai.forvum.engine.tools;

import ai.forvum.core.ToolSpec;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Glob filtering of the global tool set into an agent's belt (ULTRAPLAN section 5.3, the Select pillar
 * applied to capability). A persona's {@code allowedTools} globs are intersected against every
 * registered {@link ToolSpec}; the result is the immutable subset the agent may call.
 *
 * <p>Glob semantics are deliberately simple (tool names are dotted tokens, not paths): {@code *} matches
 * any run of characters including dots, {@code ?} matches exactly one character, and every other
 * character — the dot included — is literal. Stateless and pure; no CDI.
 */
public final class ToolFilter {

    private ToolFilter() {
    }

    /** The tools whose names match at least one of {@code allowedGlobs}, in {@code tools} iteration order. */
    public static List<ToolSpec> filter(List<String> allowedGlobs, Collection<ToolSpec> tools) {
        return tools.stream()
                .filter(spec -> allowedGlobs.stream().anyMatch(glob -> matches(glob, spec.name())))
                .toList();
    }

    /** Whether {@code name} matches {@code glob} under the simple {@code *}/{@code ?} semantics above. */
    static boolean matches(String glob, String name) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                flush(regex, literal);
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        flush(regex, literal);
        return name.matches(regex.toString());
    }

    private static void flush(StringBuilder regex, StringBuilder literal) {
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }
}
