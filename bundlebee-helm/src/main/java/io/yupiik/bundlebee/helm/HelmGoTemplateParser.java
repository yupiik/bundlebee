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

import io.yupiik.bundlebee.helm.HelmGoTemplateLexer.Token;
import io.yupiik.bundlebee.helm.HelmGoTemplateLexer.TokenType;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.AssignNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.BlockNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.BoolNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.DefineNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.FunctionCallNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.IfNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.IndexNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.ListNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.NumberNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.PipelineNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.RangeNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.SliceNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.StringNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.TemplateNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.TextNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.VariableNode;
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.WithNode;

import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * Recursive-descent parser for Go template syntax.
 * Produces an AST from a list of tokens.
 */
public class HelmGoTemplateParser {

    private final List<Token> tokens;
    private int pos;

    public HelmGoTemplateParser(final List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public List<HelmGoTemplateNode> parse() {
        return parseBody(List.of());
    }

    private List<HelmGoTemplateNode> parseBody(final List<TokenType> stopTokens) {
        final var nodes = new ArrayList<HelmGoTemplateNode>();
        while (pos < tokens.size()) {
            final var token = tokens.get(pos);
            if (token.getType() == TokenType.TEXT) {
                if (!token.getValue().isEmpty()) {
                    nodes.add(new TextNode(token.getValue(), token.isTrimLeft(), token.isTrimRight()));
                }
                pos++;
                continue;
            }
            if (stopTokens.contains(token.getType())) {
                break;
            }
            if (token.getType() == TokenType.IF) {
                nodes.add(parseIf());
            } else if (token.getType() == TokenType.RANGE) {
                nodes.add(parseRange());
            } else if (token.getType() == TokenType.WITH) {
                nodes.add(parseWith());
            } else if (token.getType() == TokenType.DEFINE) {
                nodes.add(parseDefine());
            } else if (token.getType() == TokenType.BLOCK) {
                nodes.add(parseBlock());
            } else if (token.getType() == TokenType.TEMPLATE) {
                nodes.add(parseTemplate());
            } else if (token.getType() == TokenType.VARIABLE && pos + 1 < tokens.size()
                    && tokens.get(pos + 1).getType() == TokenType.ASSIGN) {
                nodes.add(parseAssign());
            } else {
                nodes.add(parsePipeline());
            }
        }
        return nodes;
    }

    private IfNode parseIf() {
        consume(TokenType.IF);
        final var condition = parsePipeline();
        final var body = parseBody(List.of(TokenType.ELSE, TokenType.END));
        List<HelmGoTemplateNode> elseBody = List.of();
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.ELSE) {
            consume(TokenType.ELSE);
            if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.IF) {
                elseBody = List.of(parseIf());
            } else {
                elseBody = parseBody(List.of(TokenType.END));
            }
        }
        consume(TokenType.END);
        return new IfNode(condition, body, elseBody);
    }

    private RangeNode parseRange() {
        consume(TokenType.RANGE);
        final var over = parsePipeline();
        final var body = parseBody(List.of(TokenType.END));
        consume(TokenType.END);
        return new RangeNode(over, body);
    }

    private WithNode parseWith() {
        consume(TokenType.WITH);
        final var target = parsePipeline();
        final var body = parseBody(List.of(TokenType.END));
        consume(TokenType.END);
        return new WithNode(target, body);
    }

    private DefineNode parseDefine() {
        consume(TokenType.DEFINE);
        final var name = expectString();
        final var body = parseBody(List.of(TokenType.END));
        consume(TokenType.END);
        return new DefineNode(name, body);
    }

    private BlockNode parseBlock() {
        consume(TokenType.BLOCK);
        final var name = expectString();
        final var body = parseBody(List.of(TokenType.END));
        consume(TokenType.END);
        return new BlockNode(name, body);
    }

    private TemplateNode parseTemplate() {
        consume(TokenType.TEMPLATE);
        final var name = expectString();
        PipelineNode pipeline = null;
        if (pos < tokens.size() && tokens.get(pos).getType() != TokenType.END
                && tokens.get(pos).getType() != TokenType.TEXT) {
            pipeline = parsePipeline();
        }
        return new TemplateNode(name, pipeline);
    }

    private AssignNode parseAssign() {
        final var varName = tokens.get(pos).getValue();
        consume(TokenType.VARIABLE);
        consume(TokenType.ASSIGN);
        final var value = parsePipeline();
        return new AssignNode(varName, value);
    }

    private PipelineNode parsePipeline() {
        final var functions = new ArrayList<HelmGoTemplateNode>();
        functions.add(parseExpression());
        while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.PIPE) {
            consume(TokenType.PIPE);
            functions.add(parseExpression());
        }
        return new PipelineNode(functions);
    }

    private HelmGoTemplateNode parseExpression() {
        var left = parsePrimary();
        while (pos < tokens.size() && isOperator(tokens.get(pos).getType())) {
            final var op = tokens.get(pos);
            consume(op.getType());
            final var right = parsePrimary();
            left = new FunctionCallNode(op.getValue(), List.of(left, right));
        }
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.LBRACK) {
            consume(TokenType.LBRACK);
            HelmGoTemplateNode index = null;
            HelmGoTemplateNode high = null;
            if (tokens.get(pos).getType() != TokenType.RBRACK) {
                index = parsePipeline();
                if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.COMMA) {
                    consume(TokenType.COMMA);
                    high = parsePipeline();
                }
            }
            consume(TokenType.RBRACK);
            if (high != null) {
                return new SliceNode(left, index, high);
            }
            return new IndexNode(left, index);
        }
        return left;
    }

    private HelmGoTemplateNode parsePrimary() {
        if (pos >= tokens.size()) {
            return new StringNode("");
        }
        final var token = tokens.get(pos);
        if (token.getType() == TokenType.DOT) {
            consume(TokenType.DOT);
            return new VariableNode(List.of(""));
        }
        if (token.getType() == TokenType.VARIABLE) {
            consume(TokenType.VARIABLE);
            return parseVariableRest(token.getValue());
        }
        if (token.getType() == TokenType.STRING) {
            consume(TokenType.STRING);
            return new StringNode(unquote(token.getValue()));
        }
        if (token.getType() == TokenType.NUMBER) {
            consume(TokenType.NUMBER);
            return new NumberNode(token.getValue());
        }
        if (token.getType() == TokenType.BOOL) {
            consume(TokenType.BOOL);
            return new BoolNode("true".equals(token.getValue()));
        }
        if (token.getType() == TokenType.NIL) {
            consume(TokenType.NIL);
            return new StringNode("");
        }
        if (token.getType() == TokenType.LPAREN) {
            consume(TokenType.LPAREN);
            final var expr = parsePipeline();
            consume(TokenType.RPAREN);
            return expr;
        }
        if (token.getType() == TokenType.NOT) {
            consume(TokenType.NOT);
            final var expr = parsePrimary();
            return new FunctionCallNode("not", List.of(expr));
        }
        if (token.getType() == TokenType.IDENT) {
            consume(TokenType.IDENT);
            final var name = token.getValue();
            if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.LPAREN) {
                consume(TokenType.LPAREN);
                final var args = new ArrayList<HelmGoTemplateNode>();
                if (pos < tokens.size() && tokens.get(pos).getType() != TokenType.RPAREN) {
                    args.add(parsePipeline());
                    while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.COMMA) {
                        consume(TokenType.COMMA);
                        args.add(parsePipeline());
                    }
                }
                consume(TokenType.RPAREN);
                return new FunctionCallNode(name, args);
            }
            if (!isStopToken(pos < tokens.size() ? tokens.get(pos).getType() : null)) {
                final var args = new ArrayList<HelmGoTemplateNode>();
                while (pos < tokens.size() && !isStopToken(tokens.get(pos).getType())) {
                    args.add(parsePrimary());
                }
                return new FunctionCallNode(name, args);
            }
            return new FunctionCallNode(name, List.of());
        }
        if (token.getType() == TokenType.LBRACK) {
            consume(TokenType.LBRACK);
            final var elements = new ArrayList<HelmGoTemplateNode>();
            if (pos < tokens.size() && tokens.get(pos).getType() != TokenType.RBRACK) {
                elements.add(parsePipeline());
                while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.COMMA) {
                    consume(TokenType.COMMA);
                    elements.add(parsePipeline());
                }
            }
            consume(TokenType.RBRACK);
            return new ListNode(elements);
        }
        consume(token.getType());
        return new StringNode(token.getValue());
    }

    private HelmGoTemplateNode parseVariableRest(final String prefix) {
        final var path = new ArrayList<String>();
        if (prefix.startsWith(".")) {
            path.add("");
            final var parts = prefix.substring(1).split("\\.", -1);
            for (final var part : parts) {
                if (!part.isEmpty()) {
                    path.add(part);
                }
            }
        } else if (prefix.startsWith("$")) {
            path.add("$");
            if (prefix.length() > 1) {
                path.add(prefix.substring(1));
            }
        } else {
            path.add(prefix);
        }
        while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.DOT) {
            consume(TokenType.DOT);
            if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.IDENT) {
                path.add(tokens.get(pos).getValue());
                consume(TokenType.IDENT);
            }
        }
        return new VariableNode(path);
    }

    private boolean isOperator(final TokenType type) {
        return type == TokenType.EQ || type == TokenType.NEQ
                || type == TokenType.GT || type == TokenType.GTE
                || type == TokenType.LT || type == TokenType.LTE
                || type == TokenType.AND || type == TokenType.OR;
    }

    private boolean isStopToken(final TokenType type) {
        if (type == null) {
            return true;
        }
        return type == TokenType.TEXT || type == TokenType.PIPE || type == TokenType.IF || type == TokenType.ELSE
                || type == TokenType.END || type == TokenType.RANGE || type == TokenType.WITH
                || type == TokenType.DEFINE || type == TokenType.BLOCK || type == TokenType.TEMPLATE
                || type == TokenType.CALL || type == TokenType.ASSIGN || type == TokenType.RPAREN
                || type == TokenType.RBRACK || type == TokenType.COMMA;
    }

    private void consume(final TokenType expected) {
        if (pos >= tokens.size() || tokens.get(pos).getType() != expected) {
            final var found = pos < tokens.size() ? tokens.get(pos) : null;
            throw new IllegalStateException("Expected " + expected + " but got "
                    + (found == null ? "EOF" : found.getType() + " '" + found.getValue() + "'")
                    + " at position " + pos);
        }
        pos++;
    }

    private String expectString() {
        if (pos >= tokens.size() || tokens.get(pos).getType() != TokenType.STRING) {
            final var found = pos < tokens.size() ? tokens.get(pos) : null;
            throw new IllegalStateException("Expected STRING but got "
                    + (found == null ? "EOF" : found.getType() + " '" + found.getValue() + "'")
                    + " at position " + pos);
        }
        final var value = unquote(tokens.get(pos).getValue());
        pos++;
        return value;
    }

    private String unquote(final String s) {
        if (s.length() >= 2 && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
            return s.substring(1, s.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\");
        }
        return s;
    }
}
