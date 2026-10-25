package vpmac;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code #if}/{@code #ifdef}/{@code #elif}/{@code #else}/{@code #endif} before the Mac
 * sees the shader. Viewpoint's shaders test macros that may be undefined ({@code #if X} with no
 * X); C, and the drivers Viewpoint was written for, read those as 0, the Mac's GLSL 1.20 compiler
 * refuses them. Lines in skipped branches become blank, so line numbers hold; {@code #define}s
 * that stay are left for the compiler to expand in the code.
 */
final class Preproc {
    private Preproc() {}

    private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(\\w+)\\s*(.*)$");
    private static final Pattern DEFINE = Pattern.compile("^(\\w+)(\\([^)]*\\))?\\s*(.*)$");

    static String resolve(String src) {
        Map<String, String> macros = new HashMap<>();
        macros.put("__VERSION__", "330");
        macros.put("GL_core_profile", "1");
        // Each frame: was any branch taken yet, is this branch live, was the parent live.
        Deque<boolean[]> stack = new ArrayDeque<>();
        boolean live = true;
        StringBuilder out = new StringBuilder(src.length());
        for (String line : src.split("\n", -1)) {
            Matcher d = DIRECTIVE.matcher(stripComment(line));
            if (!d.matches()) {
                out.append(live ? line : "").append('\n');
                continue;
            }
            String kind = d.group(1), rest = d.group(2).trim();
            switch (kind) {
                case "if": case "ifdef": case "ifndef": {
                    boolean cond = live && (kind.equals("if") ? eval(rest, macros) != 0
                        : macros.containsKey(firstWord(rest)) == kind.equals("ifdef"));
                    stack.push(new boolean[] {cond, live});
                    live = cond;
                    out.append('\n');
                    continue;
                }
                case "elif": {
                    boolean[] f = stack.peek();
                    if (f == null) break;
                    boolean cond = f[1] && !f[0] && eval(rest, macros) != 0;
                    if (cond) f[0] = true;
                    live = cond;
                    out.append('\n');
                    continue;
                }
                case "else": {
                    boolean[] f = stack.peek();
                    if (f == null) break;
                    live = f[1] && !f[0];
                    f[0] = true;
                    out.append('\n');
                    continue;
                }
                case "endif": {
                    boolean[] f = stack.poll();
                    live = f == null || f[1];
                    out.append('\n');
                    continue;
                }
                case "define":
                    if (live) {
                        Matcher m = DEFINE.matcher(rest);
                        if (m.matches()) macros.put(m.group(1), m.group(2) == null ? m.group(3).trim() : "");
                    }
                    break;
                case "undef":
                    if (live) macros.remove(firstWord(rest));
                    break;
                default:
            }
            out.append(live ? line : "").append('\n');
        }
        if (out.length() > 0) out.setLength(out.length() - 1);
        return out.toString();
    }

    private static String firstWord(String s) {
        Matcher m = Pattern.compile("\\w+").matcher(s);
        return m.find() ? m.group() : "";
    }

    private static String stripComment(String line) {
        int c = line.indexOf("//");
        return c >= 0 ? line.substring(0, c) : line;
    }

    // ---- #if expressions: integers, defined(), every C operator, undefined names as 0 ----

    static long eval(String expr, Map<String, String> macros) {
        try {
            Parser p = new Parser(expand(expr.replaceAll("/\\*.*?\\*/", " "), macros, 0));
            long v = p.ternary();
            return v;
        } catch (RuntimeException e) {
            Log.once("pp:" + expr, "could not evaluate #if " + expr + " (" + e.getMessage() + "); taken as false");
            return 0;
        }
    }

    /** Replaces macros by their values, keeping {@code defined X} as 1 or 0. */
    private static String expand(String expr, Map<String, String> macros, int depth) {
        if (depth > 32) return expr;
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("defined\\s*\\(\\s*(\\w+)\\s*\\)|defined\\s+(\\w+)|[A-Za-z_]\\w*").matcher(expr);
        int last = 0;
        while (m.find()) {
            out.append(expr, last, m.start());
            if (m.group(1) != null || m.group(2) != null) {
                String name = m.group(1) != null ? m.group(1) : m.group(2);
                out.append(macros.containsKey(name) ? " 1 " : " 0 ");
            } else {
                String v = macros.get(m.group());
                out.append(' ').append(v == null || v.isEmpty() ? "0" : "(" + expand(v, macros, depth + 1) + ")").append(' ');
            }
            last = m.end();
        }
        out.append(expr.substring(last));
        return out.toString();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private boolean eat(String op) {
            ws();
            if (s.startsWith(op, i)) {
                // don't read "<" out of "<<" or "<=", "&" out of "&&", and so on
                if (op.length() == 1 && i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    char c = op.charAt(0);
                    if ((c == '<' || c == '>') && (n == c || n == '=')) return false;
                    if ((c == '&' || c == '|') && n == c) return false;
                    if ((c == '=' || c == '!') && n == '=') return false;
                }
                i += op.length();
                return true;
            }
            return false;
        }

        long ternary() {
            long c = or();
            if (eat("?")) {
                long a = ternary();
                if (!eat(":")) throw new IllegalArgumentException("missing :");
                long b = ternary();
                return c != 0 ? a : b;
            }
            return c;
        }

        long or() {
            long v = and();
            while (eat("||")) { long r = and(); v = (v != 0 || r != 0) ? 1 : 0; }
            return v;
        }

        long and() {
            long v = bitOr();
            while (eat("&&")) { long r = bitOr(); v = (v != 0 && r != 0) ? 1 : 0; }
            return v;
        }

        long bitOr() {
            long v = bitXor();
            while (eat("|")) v |= bitXor();
            return v;
        }

        long bitXor() {
            long v = bitAnd();
            while (eat("^")) v ^= bitAnd();
            return v;
        }

        long bitAnd() {
            long v = equality();
            while (eat("&")) v &= equality();
            return v;
        }

        long equality() {
            long v = relational();
            while (true) {
                if (eat("==")) v = v == relational() ? 1 : 0;
                else if (eat("!=")) v = v != relational() ? 1 : 0;
                else return v;
            }
        }

        long relational() {
            long v = shift();
            while (true) {
                if (eat("<=")) v = v <= shift() ? 1 : 0;
                else if (eat(">=")) v = v >= shift() ? 1 : 0;
                else if (eat("<")) v = v < shift() ? 1 : 0;
                else if (eat(">")) v = v > shift() ? 1 : 0;
                else return v;
            }
        }

        long shift() {
            long v = additive();
            while (true) {
                if (eat("<<")) v <<= additive();
                else if (eat(">>")) v >>= additive();
                else return v;
            }
        }

        long additive() {
            long v = term();
            while (true) {
                if (eat("+")) v += term();
                else if (eat("-")) v -= term();
                else return v;
            }
        }

        long term() {
            long v = unary();
            while (true) {
                if (eat("*")) v *= unary();
                else if (eat("/")) { long r = unary(); v = r == 0 ? 0 : v / r; }
                else if (eat("%")) { long r = unary(); v = r == 0 ? 0 : v % r; }
                else return v;
            }
        }

        long unary() {
            if (eat("!")) return unary() == 0 ? 1 : 0;
            if (eat("~")) return ~unary();
            if (eat("-")) return -unary();
            if (eat("+")) return unary();
            return primary();
        }

        long primary() {
            ws();
            if (eat("(")) {
                long v = ternary();
                if (!eat(")")) throw new IllegalArgumentException("missing )");
                return v;
            }
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) i++;
            if (start == i) throw new IllegalArgumentException("unexpected '" + (i < s.length() ? s.charAt(i) : ' ') + "'");
            String tok = s.substring(start, i).replaceAll("[uUlL]+$", "");
            if (tok.startsWith("0x") || tok.startsWith("0X")) return Long.parseLong(tok.substring(2), 16);
            if (tok.contains(".")) return (long) Double.parseDouble(tok);
            if (Character.isDigit(tok.charAt(0))) return Long.parseLong(tok);
            return 0; // a name with no value
        }
    }
}
