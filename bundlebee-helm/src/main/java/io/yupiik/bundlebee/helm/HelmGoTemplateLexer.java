/*
 * Copyright (c) 2021 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.bundlebee.helm;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for Go template syntax, aligned with Go's text/template/parse/lex.go.
 *
 * Token types correspond to Go's item types:
 * - TEXT        = itemText (plain text between actions)
 * - LEFT_DELIM  = itemLeftDelim ({{ or {{-)
 * - RIGHT_DELIM = itemRightDelim (}} or -}})
 * - SPACE       = itemSpace (run of spaces separating arguments)
 * - VARIABLE    = itemVariable ($var)
 * - FIELD       = itemField (.field)
 * - IDENT       = itemIdentifier
 * - STRING      = itemString ("quoted")
 * - NUMBER      = itemNumber
 * - BOOL        = itemBool (true/false)
 * - PIPE        = itemPipe (|)
 * - ASSIGN       = itemAssign (=)
 * - DECLARE      = itemDeclare (:=)
 * - LPAREN      = itemLeftParen
 * - RPAREN      = itemRightParen
 * - LBRACK      = [ for index/slice
 * - RBRACK      = ] for index/slice
 * - COMMA       = ,
 * - DOT         = itemDot (bare .)
 * - IF/ELSE/END/RANGE/WITH/DEFINE/BLOCK/TEMPLATE/Nil = keywords
 */
public class HelmGoTemplateLexer {

    @Data
    @AllArgsConstructor
    public static class Token {
        private final TokenType type;
        private final String value;
        private final boolean trimLeft;
        private final boolean trimRight;
    }

    public enum TokenType {
        TEXT,
        LEFT_DELIM,
        RIGHT_DELIM,
        SPACE,
        VARIABLE,
        FIELD,
        IDENT,
        STRING,
        NUMBER,
        BOOL,
        PIPE,
        ASSIGN,      // = (itemAssign)
        DECLARE,     // := (itemDeclare)
        LPAREN,
        RPAREN,
        LBRACK,
        RBRACK,
        COMMA,
        DOT,         // bare . (itemDot)
        IF,
        ELSE,
        END,
        RANGE,
        WITH,
        DEFINE,
        BLOCK,
        TEMPLATE,
        NIL
    }

    public List<Token> tokenize(final String template) {
        final var tokens = new ArrayList<Token>();
        int pos = 0;
        boolean nextTextTrimLeft = false;
        while (pos < template.length()) {
            final int openIdx = template.indexOf("{{", pos);
            if (openIdx < 0) {
                // No more actions - emit remaining text
                if (pos < template.length()) {
                    tokens.add(new Token(TokenType.TEXT, template.substring(pos), nextTextTrimLeft, false));
                }
                break;
            }
            // Emit text before the action
            if (openIdx > pos) {
                tokens.add(new Token(TokenType.TEXT, template.substring(pos, openIdx), nextTextTrimLeft, false));
                nextTextTrimLeft = false;
            }
            boolean trimLeft = false;
            boolean trimRight = false;
            int actionStart = openIdx + 2;
            if (actionStart < template.length() && template.charAt(actionStart) == '-') {
                trimLeft = true;
                actionStart++;
            }
            final int closeIdx = findClosing(template, actionStart);
            if (closeIdx < 0) {
                tokens.add(new Token(TokenType.TEXT, template.substring(openIdx), false, false));
                break;
            }
            boolean closesWithDash = false;
            int actionEnd = closeIdx;
            if (actionEnd > actionStart && template.charAt(actionEnd - 1) == '-') {
                closesWithDash = true;
                actionEnd--;
            }
            // Propagate trimLeft to the preceding text token
            if (trimLeft && !tokens.isEmpty()) {
                final var last = tokens.get(tokens.size() - 1);
                if (last.getType() == TokenType.TEXT) {
                    tokens.set(tokens.size() - 1, new Token(TokenType.TEXT, last.getValue(), last.isTrimLeft(), true));
                }
            }
            // Emit LEFT_DELIM (like Go's itemLeftDelim)
            tokens.add(new Token(TokenType.LEFT_DELIM, trimLeft ? "{{-" : "{{", trimLeft, false));
            // Tokenize action body - emit SPACE tokens between arguments
            final var actionBody = template.substring(actionStart, actionEnd);
            tokenizeAction(actionBody, tokens);
            // Emit RIGHT_DELIM (like Go's itemRightDelim)
            tokens.add(new Token(TokenType.RIGHT_DELIM, closesWithDash ? "-}}" : "}}", false, closesWithDash));
            nextTextTrimLeft = closesWithDash;
            pos = closeIdx + 2;
        }
        return tokens;
    }

    private int findClosing(final String template, final int start) {
        int depth = 0;
        for (int i = start; i < template.length() - 1; i++) {
            if (template.charAt(i) == '{' && template.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (template.charAt(i) == '}' && template.charAt(i + 1) == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
                i++;
            }
        }
        return -1;
    }

    /**
     * Tokenize an action body (content between left and right delimiters).
     * Emits SPACE tokens between arguments (like Go's itemSpace).
     * Go template comments are silently skipped.
     */
    private void tokenizeAction(final String action, final List<Token> tokens) {
        int pos = 0;
        while (pos < action.length()) {
            // Skip leading whitespace but emit SPACE tokens
            if (Character.isWhitespace(action.charAt(pos))) {
                final var spaceStart = pos;
                while (pos < action.length() && Character.isWhitespace(action.charAt(pos))) {
                    pos++;
                }
                // Emit a single SPACE token for the run (like Go)
                if (!tokens.isEmpty()) {
                    tokens.add(new Token(TokenType.SPACE, action.substring(spaceStart, pos), false, false));
                }
                continue;
            }
            final char ch = action.charAt(pos);
            switch (ch) {
                case '/': {
                    // Check for comment: /*
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '*') {
                        // Skip comment until */
                        pos += 2;
                        final var commentEnd = action.indexOf("*/", pos);
                        if (commentEnd >= 0) {
                            pos = commentEnd + 2;
                        } else {
                            pos = action.length();
                        }
                        continue;
                    }
                    tokens.add(new Token(TokenType.IDENT, String.valueOf(ch), false, false));
                    pos++;
                    break;
                }
                case '|': {
                    tokens.add(new Token(TokenType.PIPE, "|", false, false));
                    pos++;
                    break;
                }
                case '(': {
                    tokens.add(new Token(TokenType.LPAREN, "(", false, false));
                    pos++;
                    break;
                }
                case ')': {
                    tokens.add(new Token(TokenType.RPAREN, ")", false, false));
                    pos++;
                    break;
                }
                case '[': {
                    tokens.add(new Token(TokenType.LBRACK, "[", false, false));
                    pos++;
                    break;
                }
                case ']': {
                    tokens.add(new Token(TokenType.RBRACK, "]", false, false));
                    pos++;
                    break;
                }
                case ',': {
                    tokens.add(new Token(TokenType.COMMA, ",", false, false));
                    pos++;
                    break;
                }
                case '.': {
                    // Go's lexField: .Field starts with letter
                    if (pos + 1 < action.length() && isAlphaNum(action.charAt(pos + 1))) {
                        // Read .Field chain like .Values.name
                        pos++; // skip the dot
                        final var ident = readIdent(action, pos);
                        tokens.add(new Token(TokenType.FIELD, "." + ident, false, false));
                        pos += ident.length();
                        // Continue chaining .Field
                        while (pos < action.length() && action.charAt(pos) == '.'
                                && pos + 1 < action.length() && isAlphaNum(action.charAt(pos + 1))) {
                            pos++; // skip dot
                            final var field = readIdent(action, pos);
                            tokens.add(new Token(TokenType.FIELD, "." + field, false, false));
                            pos += field.length();
                        }
                    } else {
                        // Bare dot
                        tokens.add(new Token(TokenType.DOT, ".", false, false));
                        pos++;
                    }
                    break;
                }
                case '$': {
                    pos++;
                    if (pos < action.length() && (isAlphaNum(action.charAt(pos)) || action.charAt(pos) == '_')) {
                        final var ident = readIdent(action, pos);
                        tokens.add(new Token(TokenType.VARIABLE, "$" + ident, false, false));
                        pos += ident.length();
                        // Continue chaining .field after variable
                        while (pos < action.length() && action.charAt(pos) == '.'
                                && pos + 1 < action.length() && isAlphaNum(action.charAt(pos + 1))) {
                            pos++; // skip dot
                            final var field = readIdent(action, pos);
                            tokens.add(new Token(TokenType.FIELD, "." + field, false, false));
                            pos += field.length();
                        }
                    } else {
                        tokens.add(new Token(TokenType.VARIABLE, "$", false, false));
                    }
                    break;
                }
                case ':': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.DECLARE, ":=", false, false));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.IDENT, ":", false, false));
                        pos++;
                    }
                    break;
                }
                case '=': {
                    tokens.add(new Token(TokenType.ASSIGN, "=", false, false));
                    pos++;
                    break;
                }
                case '!': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '=') {
                        // != is not a token in Go - it's parsed as ! =
                        // But we keep it for convenience
                        tokens.add(new Token(TokenType.IDENT, "!=", false, false));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.IDENT, "!", false, false));
                        pos++;
                    }
                    break;
                }
                case '>': {
                    tokens.add(new Token(TokenType.IDENT, ">", false, false));
                    pos++;
                    break;
                }
                case '<': {
                    tokens.add(new Token(TokenType.IDENT, "<", false, false));
                    pos++;
                    break;
                }
                case '&': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '&') {
                        tokens.add(new Token(TokenType.IDENT, "&&", false, false));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.IDENT, "&", false, false));
                        pos++;
                    }
                    break;
                }
                case '"': {
                    final var str = readString(action, pos);
                    tokens.add(new Token(TokenType.STRING, str, false, false));
                    pos += str.length();
                    break;
                }
                case '\'': {
                    final var str = readSingleQuotedString(action, pos);
                    tokens.add(new Token(TokenType.STRING, str, false, false));
                    pos += str.length();
                    break;
                }
                default: {
                    if (Character.isDigit(ch) || (ch == '-' && pos + 1 < action.length() && Character.isDigit(action.charAt(pos + 1)))) {
                        final var num = readNumber(action, pos);
                        tokens.add(new Token(TokenType.NUMBER, num, false, false));
                        pos += num.length();
                    } else if (isAlphaNum(ch)) {
                        final var ident = readIdent(action, pos);
                        final var type = keywordType(ident);
                        tokens.add(new Token(type, ident, false, false));
                        pos += ident.length();
                    } else {
                        // Unknown character - emit as IDENT (like Go's itemChar)
                        tokens.add(new Token(TokenType.IDENT, String.valueOf(ch), false, false));
                        pos++;
                    }
                    break;
                }
            }
        }
    }

    private TokenType keywordType(final String ident) {
        switch (ident) {
            case "if": return TokenType.IF;
            case "else": return TokenType.ELSE;
            case "end": return TokenType.END;
            case "range": return TokenType.RANGE;
            case "with": return TokenType.WITH;
            case "define": return TokenType.DEFINE;
            case "block": return TokenType.BLOCK;
            case "template": return TokenType.TEMPLATE;
            case "nil": return TokenType.NIL;
            case "true":
            case "false": return TokenType.BOOL;
            default: return TokenType.IDENT;
        }
    }

    private boolean isAlphaNum(final char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private String readIdent(final String s, int pos) {
        int end = pos;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) {
            end++;
        }
        return s.substring(pos, end);
    }

    private String readNumber(final String s, int pos) {
        int end = pos;
        if (end < s.length() && s.charAt(end) == '-') {
            end++;
        }
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
            end++;
        }
        return s.substring(pos, end);
    }

    private String readString(final String s, int pos) {
        int end = pos + 1;
        while (end < s.length()) {
            final char ch = s.charAt(end);
            if (ch == '\\') {
                end += 2;
            } else if (ch == '"') {
                end++;
                break;
            } else {
                end++;
            }
        }
        return s.substring(pos, Math.min(end, s.length()));
    }

    private String readSingleQuotedString(final String s, int pos) {
        int end = pos + 1;
        while (end < s.length()) {
            final char ch = s.charAt(end);
            if (ch == '\\') {
                end += 2;
            } else if (ch == '\'') {
                end++;
                break;
            } else {
                end++;
            }
        }
        return s.substring(pos, Math.min(end, s.length()));
    }
}
