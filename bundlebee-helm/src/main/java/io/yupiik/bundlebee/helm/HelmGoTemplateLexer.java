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
 * Tokenizer for Go template syntax.
 * Produces a flat list of tokens from a template string.
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
        VARIABLE,
        STRING,
        NUMBER,
        BOOL,
        IDENT,
        PIPE,
        LPAREN,
        RPAREN,
        LBRACK,
        RBRACK,
        COMMA,
        DOT,
        ASSIGN,
        EQ,
        NEQ,
        GT,
        GTE,
        LT,
        LTE,
        AND,
        OR,
        NOT,
        IF,
        ELSE,
        END,
        RANGE,
        WITH,
        DEFINE,
        BLOCK,
        TEMPLATE,
        CALL,
        NIL
    }

    public List<Token> tokenize(final String template) {
        final var tokens = new ArrayList<Token>();
        int pos = 0;
        boolean nextTextTrimLeft = false;
        while (pos < template.length()) {
            final int openIdx = template.indexOf("{{", pos);
            if (openIdx < 0) {
                tokens.add(new Token(TokenType.TEXT, template.substring(pos), nextTextTrimLeft, false));
                break;
            }
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
            final var actionBody = template.substring(actionStart, actionEnd).strip();
            tokenizeAction(actionBody, trimLeft, closesWithDash, tokens);
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

    private void tokenizeAction(final String action, final boolean trimLeft, final boolean trimRight,
                                final List<Token> tokens) {
        if (action.isEmpty()) {
            return;
        }
        // Go template comments: {{/* ... */}}
        if (action.startsWith("/*") && action.endsWith("*/")) {
            return;
        }
        int pos = 0;
        while (pos < action.length()) {
            pos = skipWhitespace(action, pos);
            if (pos >= action.length()) {
                break;
            }
            final char ch = action.charAt(pos);
            switch (ch) {
                case '|': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '|') {
                        tokens.add(new Token(TokenType.OR, "||", trimLeft, trimRight));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.PIPE, "|", trimLeft, trimRight));
                        pos++;
                    }
                    break;
                }
                case '(': {
                    tokens.add(new Token(TokenType.LPAREN, "(", trimLeft, trimRight));
                    pos++;
                    break;
                }
                case ')': {
                    tokens.add(new Token(TokenType.RPAREN, ")", trimLeft, trimRight));
                    pos++;
                    break;
                }
                case '[': {
                    tokens.add(new Token(TokenType.LBRACK, "[", trimLeft, trimRight));
                    pos++;
                    break;
                }
                case ']': {
                    tokens.add(new Token(TokenType.RBRACK, "]", trimLeft, trimRight));
                    pos++;
                    break;
                }
                case ',': {
                    tokens.add(new Token(TokenType.COMMA, ",", trimLeft, trimRight));
                    pos++;
                    break;
                }
                case '.': {
                    if (pos + 1 < action.length() && Character.isLetter(action.charAt(pos + 1))) {
                        final var ident = readIdent(action, pos);
                        tokens.add(new Token(TokenType.VARIABLE, ident, trimLeft, trimRight));
                        pos += ident.length();
                    } else {
                        tokens.add(new Token(TokenType.DOT, ".", trimLeft, trimRight));
                        pos++;
                    }
                    break;
                }
                case '$': {
                    final var ident = readIdent(action, pos);
                    tokens.add(new Token(TokenType.VARIABLE, ident, trimLeft, trimRight));
                    pos += ident.length();
                    break;
                }
                case ':': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.ASSIGN, ":=", trimLeft, trimRight));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.IDENT, ":", trimLeft, trimRight));
                        pos++;
                    }
                    break;
                }
                case '=': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.EQ, "==", trimLeft, trimRight));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.EQ, "=", trimLeft, trimRight));
                        pos++;
                    }
                    break;
                }
                case '!': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.NEQ, "!=", trimLeft, trimRight));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.NOT, "!", trimLeft, trimRight));
                        pos++;
                    }
                    break;
                }
                case '>': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.GTE, ">=", trimLeft, trimRight));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.GT, ">", trimLeft, trimRight));
                        pos++;
                    }
                    break;
                }
                case '<': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.LTE, "<=", trimLeft, trimRight));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.LT, "<", trimLeft, trimRight));
                        pos++;
                    }
                    break;
                }
                case '&': {
                    if (pos + 1 < action.length() && action.charAt(pos + 1) == '&') {
                        tokens.add(new Token(TokenType.AND, "&&", trimLeft, trimRight));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.IDENT, "&", trimLeft, trimRight));
                        pos++;
                    }
                    break;
                }
                case '"': {
                    final var str = readString(action, pos);
                    tokens.add(new Token(TokenType.STRING, str, trimLeft, trimRight));
                    pos += str.length();
                    break;
                }
                case '\'': {
                    final var str = readSingleQuotedString(action, pos);
                    tokens.add(new Token(TokenType.STRING, str, trimLeft, trimRight));
                    pos += str.length();
                    break;
                }
                default: {
                    if (Character.isDigit(ch) || (ch == '-' && pos + 1 < action.length() && Character.isDigit(action.charAt(pos + 1)))) {
                        final var num = readNumber(action, pos);
                        tokens.add(new Token(TokenType.NUMBER, num, trimLeft, trimRight));
                        pos += num.length();
                    } else if (Character.isLetter(ch) || ch == '_') {
                        final var ident = readIdent(action, pos);
                        final var type = keywordType(ident);
                        tokens.add(new Token(type, ident, trimLeft, trimRight));
                        pos += ident.length();
                    } else {
                        tokens.add(new Token(TokenType.IDENT, String.valueOf(ch), trimLeft, trimRight));
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
            case "call": return TokenType.CALL;
            case "nil": return TokenType.NIL;
            case "true":
            case "false": return TokenType.BOOL;
            default: return TokenType.IDENT;
        }
    }

    private int skipWhitespace(final String s, int pos) {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private String readIdent(final String s, int pos) {
        int end = pos;
        while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_' || s.charAt(end) == '.')) {
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
