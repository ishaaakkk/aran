package edu.upc.dtak.aran.operators.queryengine.oql;

import edu.upc.dtak.aran.core.util.Names;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hand-written lexer + recursive-descent parser for OQL (Aran Query Language).
 *
 * <pre>
 *   query    := SELECT [DISTINCT] projList [FROM pathExpr] [WHERE conj]
 *               [GROUP BY groupList] [HAVING havingConj] [ORDER BY orderList] [LIMIT int]
 *
 *   projList := projItem (',' projItem)*
 *   projItem := '*'
 *             | ClassName '.' ('*' | ident) [AS ident]
 *             | aggFn '(' '*' ')' [AS ident]
 *             | aggFn '(' ClassName '.' ident ')' [AS ident]
 *   aggFn    := COUNT | SUM | AVG | MIN | MAX
 *
 *   pathExpr := ClassName ('-[' ident ']-&gt;' ClassName)*
 *
 *   conj     := filter ('AND' filter)*
 *   filter   := ClassName '.' ident OP literal
 *
 *   groupList    := ClassName '.' ident (',' ClassName '.' ident)*
 *   orderList    := orderItem (',' orderItem)*
 *   orderItem    := (ClassName '.' ident | ident) [ASC | DESC]
 *   havingConj   := havingPred ('AND' havingPred)*
 *   havingPred   := aggFn '(' '*' ')' OP literal
 *                 | aggFn '(' ClassName '.' ident ')' OP literal
 *                 | ClassName '.' ident OP literal
 *
 *   ident / ClassName := word | '"' chars '"'      ("" inside the quotes is one ")
 * </pre>
 *
 * <p><b>Keywords are contextual.</b> A word is a keyword only where the grammar expects one, so a
 * class called {@code Order} or a property called {@code count} is written bare like any other
 * name. Every class and property position is fixed by the grammar, so two lookaheads settle the
 * only places a word could be read two ways: an aggregate is its function name <em>followed by
 * {@code (}</em> ({@code count.x} is a class), and {@code DISTINCT} is the keyword unless a
 * {@code .} follows it. Classifying keywords in the lexer, as this parser once did, made every
 * such name unreachable (S6-1). A double-quoted identifier is never a keyword, and is how a name
 * outside the bare alphabet is written — the {@code first-name}, {@code price.usd} and
 * {@code 2024_total} that bootstrap mints from real headers (S6-2). Double quotes are free for
 * this because string literals are single-quoted.
 */
@NullMarked
public final class OqlParser {

    public static final class OqlParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public OqlParseException(String message) { super(message); }
    }

    private enum Kind {
        WORD, QUOTED, NUMBER, STRING, OP, DOT, COMMA, STAR,
        LBRACKET, RBRACKET, LPAREN, RPAREN, DASH, ARROW, EOF
    }

    private record Token(Kind kind, String text, int pos) {}

    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

    private final List<Token> tokens;
    private int i = 0;

    private OqlParser(String src) { this.tokens = lex(src); }

    public static OqlQuery parse(String oql) {
        if (oql == null || oql.isBlank()) throw new OqlParseException("OQL query is empty.");
        return new OqlParser(oql).parseQuery();
    }

    // ── Lexer ─────────────────────────────────────────────────────────────────

    private static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int p = 0, n = s.length();
        while (p < n) {
            char c = s.charAt(p);
            if (Character.isWhitespace(c)) { p++; continue; }
            if (c == ';')  { p++; continue; }
            if (c == ',')  { out.add(new Token(Kind.COMMA,    ",", p++)); continue; }
            if (c == '.')  { out.add(new Token(Kind.DOT,      ".", p++)); continue; }
            if (c == '*')  { out.add(new Token(Kind.STAR,     "*", p++)); continue; }
            if (c == '(')  { out.add(new Token(Kind.LPAREN,   "(", p++)); continue; }
            if (c == ')')  { out.add(new Token(Kind.RPAREN,   ")", p++)); continue; }
            if (c == '[')  { out.add(new Token(Kind.LBRACKET, "[", p++)); continue; }
            if (c == ']')  { out.add(new Token(Kind.RBRACKET, "]", p++)); continue; }
            if (c == '\'') {
                int start = p++;
                StringBuilder sb = new StringBuilder("'");
                while (p < n) {
                    char d = s.charAt(p);
                    if (d == '\'') {
                        if (p + 1 < n && s.charAt(p + 1) == '\'') { sb.append("''"); p += 2; continue; }
                        sb.append('\''); p++;
                        out.add(new Token(Kind.STRING, sb.toString(), start));
                        break;
                    }
                    sb.append(d); p++;
                    if (p >= n) throw new OqlParseException("Unterminated string at position " + start);
                }
                continue;
            }
            if (c == '"') {
                int start = p++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (p < n) {
                    char d = s.charAt(p);
                    if (d == '"') {
                        if (p + 1 < n && s.charAt(p + 1) == '"') { sb.append('"'); p += 2; continue; }
                        p++;
                        closed = true;
                        break;
                    }
                    sb.append(d); p++;
                }
                if (!closed) throw new OqlParseException("Unterminated quoted identifier at position " + start);
                if (sb.isEmpty()) throw new OqlParseException("Empty quoted identifier at position " + start);
                out.add(new Token(Kind.QUOTED, sb.toString(), start));
                continue;
            }
            if (c == '-') {
                if (p + 1 < n && s.charAt(p + 1) == '>') {
                    out.add(new Token(Kind.ARROW, "->", p)); p += 2;
                } else if (p + 1 < n && Character.isDigit(s.charAt(p + 1))
                        && !out.isEmpty() && out.get(out.size() - 1).kind() == Kind.OP) {
                    int start = p++;
                    StringBuilder sb = new StringBuilder("-");
                    while (p < n && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) sb.append(s.charAt(p++));
                    out.add(new Token(Kind.NUMBER, sb.toString(), start));
                } else {
                    out.add(new Token(Kind.DASH, "-", p++));
                }
                continue;
            }
            if (c == '<' || c == '>' || c == '!' || c == '=') {
                int start = p;
                StringBuilder sb = new StringBuilder();
                sb.append(c); p++;
                if (p < n && (s.charAt(p) == '=' || s.charAt(p) == '>')) { sb.append(s.charAt(p++)); }
                out.add(new Token(Kind.OP, sb.toString(), start));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = p;
                StringBuilder sb = new StringBuilder();
                while (p < n && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) sb.append(s.charAt(p++));
                out.add(new Token(Kind.NUMBER, sb.toString(), start));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = p;
                StringBuilder sb = new StringBuilder();
                while (p < n && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_')) sb.append(s.charAt(p++));
                out.add(new Token(Kind.WORD, sb.toString(), start));
                continue;
            }
            throw new OqlParseException("Unexpected character '" + c + "' at position " + p);
        }
        out.add(new Token(Kind.EOF, "", s.length()));
        return out;
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private OqlQuery parseQuery() {
        expectKeyword("select");
        boolean distinct = false;
        // `SELECT Distinct.x` projects from a class called Distinct.
        if (peekKeyword("distinct") && peekAt(1).kind() != Kind.DOT) { consume(); distinct = true; }
        List<OqlQuery.Projection> select = parseProjList();

        List<OqlQuery.PathStep> path = new ArrayList<>();
        @Nullable String explicitFrom = null;
        if (peekKeyword("from")) {
            consume();
            String src = expectName("a class name");
            explicitFrom = src;
            String current = src;
            while (peek().kind() == Kind.DASH) {
                consume();
                expect(Kind.LBRACKET);
                String joinProp = expectName("a join-property name");
                expect(Kind.RBRACKET);
                expect(Kind.ARROW);
                String tgt = expectName("a class name");
                path.add(new OqlQuery.PathStep(current, joinProp, tgt));
                current = tgt;
            }
        }

        List<OqlQuery.Filter> where = new ArrayList<>();
        if (peekKeyword("where")) { consume(); where = parseConj(); }

        List<OqlQuery.GroupKey> groupBy = new ArrayList<>();
        if (peekKeyword("group")) {
            consume();
            if (!peekKeyword("by")) throw new OqlParseException("Expected BY after GROUP");
            consume();
            groupBy = parseGroupList();
        }

        List<OqlQuery.HavingPredicate> having = new ArrayList<>();
        if (peekKeyword("having")) { consume(); having = parseHavingConj(); }

        List<OqlQuery.OrderKey> orderBy = new ArrayList<>();
        if (peekKeyword("order")) {
            consume();
            if (!peekKeyword("by")) throw new OqlParseException("Expected BY after ORDER");
            consume();
            orderBy = parseOrderList();
        }

        @Nullable Integer limit = null;
        if (peekKeyword("limit")) {
            consume();
            Token t = expect(Kind.NUMBER);
            try { limit = Integer.parseInt(t.text()); }
            catch (NumberFormatException e) {
                throw new OqlParseException("LIMIT must be an integer, got '" + t.text() + "'");
            }
        }

        expect(Kind.EOF);
        return new OqlQuery(distinct, select, path, explicitFrom, where, groupBy, having, orderBy, limit);
    }

    // ── SELECT ────────────────────────────────────────────────────────────────

    private List<OqlQuery.Projection> parseProjList() {
        List<OqlQuery.Projection> list = new ArrayList<>();
        list.add(parseProjItem());
        while (peek().kind() == Kind.COMMA) { consume(); list.add(parseProjItem()); }
        return list;
    }

    private OqlQuery.Projection parseProjItem() {
        if (atAggregate()) {
            return parseAggProjection();
        }
        // Bare star: SELECT * — expands to all columns of all FROM classes
        if (peek().kind() == Kind.STAR) {
            consume();
            return OqlQuery.Projection.globalStar();
        }
        // A class may be called `from`, but only `from.x` projects it. Without this, an empty or
        // comma-trailed SELECT list reads FROM as a class and the error points past the real gap.
        if (peekKeyword("from") && peekAt(1).kind() != Kind.DOT) {
            throw new OqlParseException("Expected a projection at position " + peek().pos()
                    + ", got " + describe(peek()) + ": the SELECT list is empty or ends in a comma");
        }
        String className = expectName("a class name");
        expect(Kind.DOT);
        if (peek().kind() == Kind.STAR) { consume(); return OqlQuery.Projection.star(className); }
        String property = expectName("a property name");
        @Nullable String as = null;
        if (peekKeyword("as")) { consume(); as = expectName("an alias name"); }
        return OqlQuery.Projection.of(className, property, as);
    }

    private OqlQuery.Projection parseAggProjection() {
        OqlQuery.AggFn fn = consumeAggFn();
        expect(Kind.LPAREN);
        OqlQuery.Projection proj;
        if (fn == OqlQuery.AggFn.COUNT && peek().kind() == Kind.STAR) {
            consume();
            expect(Kind.RPAREN);
            @Nullable String as = null;
            if (peekKeyword("as")) { consume(); as = expectName("an alias name"); }
            proj = OqlQuery.Projection.countStar(as);
        } else if (peek().kind() == Kind.STAR) {
            throw new OqlParseException("Only COUNT(*) is allowed; " + fn.display() + "(*) is not valid");
        } else {
            String cls = expectName("a class name");
            expect(Kind.DOT);
            String prop = expectName("a property name");
            expect(Kind.RPAREN);
            @Nullable String as = null;
            if (peekKeyword("as")) { consume(); as = expectName("an alias name"); }
            proj = OqlQuery.Projection.agg(fn, cls, prop, as);
        }
        return proj;
    }

    // ── ORDER BY ──────────────────────────────────────────────────────────────

    private List<OqlQuery.OrderKey> parseOrderList() {
        List<OqlQuery.OrderKey> list = new ArrayList<>();
        list.add(parseOrderKey());
        while (peek().kind() == Kind.COMMA) { consume(); list.add(parseOrderKey()); }
        return list;
    }

    /**
     * Parses {@code ClassName '.' ident} or a bare {@code ident}, each with an optional
     * ASC/DESC. The bare form is how an aggregate's output is ordered: {@code COUNT(*) AS n}
     * projects a column called {@code n} that belongs to no class, so there is nothing to
     * qualify it with.
     */
    private OqlQuery.OrderKey parseOrderKey() {
        String first = expectName("a class name or an output alias");
        @Nullable String className = null;
        String property = first;
        if (peek().kind() == Kind.DOT) {
            consume();
            className = first;
            property = expectName("a property name");
        }
        boolean desc = false;
        if (peekKeyword("desc")) { consume(); desc = true; }
        else if (peekKeyword("asc")) { consume(); }
        return new OqlQuery.OrderKey(className, property, desc);
    }

    // ── WHERE ─────────────────────────────────────────────────────────────────

    /**
     * Parses: andGroup ('OR' andGroup)*
     * Precedence: AND binds tighter than OR, matching standard SQL.
     * Filters that open a new OR group have {@code or=true}.
     */
    private List<OqlQuery.Filter> parseConj() {
        List<OqlQuery.Filter> list = new ArrayList<>();
        parseAndGroup(list, false);
        while (peekKeyword("or")) { consume(); parseAndGroup(list, true); }
        return list;
    }

    /** Parses: filter ('AND' filter)* and appends into list. */
    private void parseAndGroup(List<OqlQuery.Filter> list, boolean firstOr) {
        list.add(parseFilter(firstOr));
        while (peekKeyword("and")) { consume(); list.add(parseFilter(false)); }
    }

    private OqlQuery.Filter parseFilter(boolean or) {
        String cls  = expectName("a class name");
        expect(Kind.DOT);
        String prop = expectName("a property name");
        if (peekKeyword("is")) {
            consume();
            boolean notNull = false;
            if (peekKeyword("not")) { consume(); notNull = true; }
            if (!peekKeyword("null"))
                throw new OqlParseException("Expected NULL after IS" + (notNull ? " NOT" : "")
                        + " at position " + peek().pos());
            consume();
            return new OqlQuery.Filter(cls, prop,
                    notNull ? OqlQuery.Op.IS_NOT_NULL : OqlQuery.Op.IS_NULL, "", or);
        }
        Token op  = expect(Kind.OP);
        Token lit = parseComparand(op, true);
        return new OqlQuery.Filter(cls, prop, toOp(op), lit.text(), or);
    }

    // ── GROUP BY ──────────────────────────────────────────────────────────────

    private List<OqlQuery.GroupKey> parseGroupList() {
        List<OqlQuery.GroupKey> list = new ArrayList<>();
        list.add(parseGroupKey());
        while (peek().kind() == Kind.COMMA) { consume(); list.add(parseGroupKey()); }
        return list;
    }

    private OqlQuery.GroupKey parseGroupKey() {
        String cls  = expectName("a class name");
        expect(Kind.DOT);
        String prop = expectName("a property name");
        return new OqlQuery.GroupKey(cls, prop);
    }

    // ── HAVING ────────────────────────────────────────────────────────────────

    private List<OqlQuery.HavingPredicate> parseHavingConj() {
        List<OqlQuery.HavingPredicate> list = new ArrayList<>();
        list.add(parseHavingPredicate());
        while (peekKeyword("and")) { consume(); list.add(parseHavingPredicate()); }
        return list;
    }

    private OqlQuery.HavingPredicate parseHavingPredicate() {
        if (atAggregate()) {
            OqlQuery.AggFn fn = consumeAggFn();
            expect(Kind.LPAREN);
            if (peek().kind() == Kind.STAR) {
                consume();
                expect(Kind.RPAREN);
                Token op  = expect(Kind.OP);
                Token lit = parseComparand(op, false);
                return OqlQuery.HavingPredicate.onAggregate(OqlQuery.AggFn.COUNT_STAR, null, null, toOp(op), lit.text());
            }
            String cls  = expectName("a class name");
            expect(Kind.DOT);
            String prop = expectName("a property name");
            expect(Kind.RPAREN);
            Token op  = expect(Kind.OP);
            Token lit = parseComparand(op, false);
            return OqlQuery.HavingPredicate.onAggregate(fn, cls, prop, toOp(op), lit.text());
        }
        String cls  = expectName("a class name");
        expect(Kind.DOT);
        String prop = expectName("a property name");
        Token op   = expect(Kind.OP);
        Token lit  = parseComparand(op, false);
        return OqlQuery.HavingPredicate.onProperty(cls, prop, toOp(op), lit.text());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * The literal on the right of a comparison — refusing {@code NULL}.
     *
     * <p>{@code x = NULL} is unknown for every row, so it filters everything out; it used to
     * parse and lower to {@code = CAST(NULL AS VARCHAR)}, which returned an empty result with no
     * hint why. The two tests that do mean something are spelled {@code IS [NOT] NULL}, which
     * {@code WHERE} has and {@code HAVING} does not.
     */
    private Token parseComparand(Token op, boolean where) {
        Token t = peek();
        if (isKeyword(t, "null")) {
            String hint = !where
                    ? "HAVING has no null test"
                    : switch (op.text()) {
                        case "=" -> "write IS NULL instead";
                        case "<>", "!=" -> "write IS NOT NULL instead";
                        default -> "test for a missing value with IS NULL or IS NOT NULL";
                    };
            throw new OqlParseException("'" + op.text() + " NULL' at position " + op.pos()
                    + " is never true — a comparison with NULL is unknown for every row; "
                    + hint + ".");
        }
        return parseLiteral();
    }

    private Token parseLiteral() {
        Token t = peek();
        if (t.kind() == Kind.STRING || t.kind() == Kind.NUMBER) { consume(); return t; }
        if (isKeyword(t, "true") || isKeyword(t, "false")) {
            consume();
            return new Token(t.kind(), t.text().toUpperCase(Locale.ROOT), t.pos());
        }
        if (t.kind() == Kind.QUOTED) {
            throw new OqlParseException("Expected literal at position " + t.pos() + ", got the quoted"
                    + " identifier \"" + t.text() + "\" — string literals take single quotes: '"
                    + Names.sqlLiteral(t.text()) + "'");
        }
        throw new OqlParseException("Expected literal at position " + t.pos() + ", got '" + t.text() + "'");
    }

    private static OqlQuery.Op toOp(Token t) {
        return switch (t.text()) {
            case "="       -> OqlQuery.Op.EQ;
            case "<>", "!="-> OqlQuery.Op.NE;
            case "<"       -> OqlQuery.Op.LT;
            case "<="      -> OqlQuery.Op.LE;
            case ">"       -> OqlQuery.Op.GT;
            case ">="      -> OqlQuery.Op.GE;
            default -> throw new OqlParseException("Unknown operator '" + t.text() + "' at position " + t.pos());
        };
    }

    /** An aggregate is its function name followed by {@code (}; {@code count.x} is a class. */
    private boolean atAggregate() {
        Token t = peek();
        return t.kind() == Kind.WORD
                && AGGREGATES.contains(t.text().toLowerCase(Locale.ROOT))
                && peekAt(1).kind() == Kind.LPAREN;
    }

    private OqlQuery.AggFn consumeAggFn() {
        Token t = consume();
        return switch (t.text().toUpperCase(Locale.ROOT)) {
            case "COUNT" -> OqlQuery.AggFn.COUNT;
            case "SUM"   -> OqlQuery.AggFn.SUM;
            case "AVG"   -> OqlQuery.AggFn.AVG;
            case "MIN"   -> OqlQuery.AggFn.MIN;
            case "MAX"   -> OqlQuery.AggFn.MAX;
            default -> throw new OqlParseException("Unknown aggregate function '" + t.text() + "'");
        };
    }

    private Token peek()    { return tokens.get(i); }
    private Token peekAt(int ahead) { return tokens.get(Math.min(i + ahead, tokens.size() - 1)); }
    private Token consume() { return tokens.get(i++); }

    /** A quoted word is a name, never a keyword — that is what quoting is for. */
    private static boolean isKeyword(Token t, String kw) {
        return t.kind() == Kind.WORD && t.text().equalsIgnoreCase(kw);
    }

    private boolean peekKeyword(String kw) {
        return isKeyword(peek(), kw);
    }

    /** A class, property, join-property or alias name: any word, keyword-spelled or not, or a quoted identifier. */
    private String expectName(String what) {
        Token t = consume();
        if (t.kind() != Kind.WORD && t.kind() != Kind.QUOTED)
            throw new OqlParseException("Expected " + what + " at position " + t.pos()
                    + ", got " + describe(t));
        return t.text();
    }

    private void expectKeyword(String kw) {
        Token t = consume();
        if (!isKeyword(t, kw))
            throw new OqlParseException("Expected '" + kw.toUpperCase(Locale.ROOT)
                    + "' at position " + t.pos() + ", got " + describe(t));
    }

    private Token expect(Kind kind) {
        Token t = consume();
        if (t.kind() != kind)
            throw new OqlParseException("Expected " + describe(kind) + " at position " + t.pos()
                    + ", got " + describe(t));
        return t;
    }

    private static String describe(Token t) {
        return switch (t.kind()) {
            case EOF -> "the end of the query";
            case QUOTED -> "\"" + t.text().replace("\"", "\"\"") + "\"";
            default -> "'" + t.text() + "'";
        };
    }

    private static String describe(Kind kind) {
        return switch (kind) {
            case DOT -> "'.'";
            case COMMA -> "','";
            case STAR -> "'*'";
            case LBRACKET -> "'['";
            case RBRACKET -> "']'";
            case LPAREN -> "'('";
            case RPAREN -> "')'";
            case ARROW -> "'->'";
            case OP -> "a comparison operator";
            case NUMBER -> "a number";
            case EOF -> "the end of the query";
            default -> kind.name();
        };
    }
}
