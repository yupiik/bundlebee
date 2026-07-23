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
import io.yupiik.bundlebee.helm.HelmGoTemplateNode.IdentifierNode;
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

/**
 * Recursive-descent parser for Go template syntax, aligned with Go's text/template/parse/parse.go.
 *
 * Grammar (from Go source):
 * <pre>
 * Body = { action } .
 * Action = "{{" -? Pipeline -? "}}" .
 * Pipeline = VarDecl? Command { "|" Command } .
 * VarDecl = "$" identifier (":=" | "=") .
 * Command = Operand { Space Operand } .
 * Operand = Field | Variable | dot | term { Field } .
 * Term = Number | String | nil | Boolean | '.' | '(' Pipeline ')' | Function .
 * Field = "." identifier .
 * Function = identifier .
 * </pre>
 *
 * Key alignment with Go:
 * - RIGHT_DELIM terminates pipelines (Go: itemRightDelim is the end token)
 * - SPACE tokens separate command arguments (Go: command() checks for itemSpace)
 * - FIELD tokens for .field access (Go: itemField)
 * - DECLARE (:=) vs ASSIGN (=) (Go: itemDeclare vs itemAssign)
 */
public class HelmGoTemplateParser {

    private final List<Token> tokens;
    private int pos;

    public HelmGoTemplateParser(final List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public List<HelmGoTemplateNode> parse() {
        return parseBody();
    }

    // Body = { action } .
    // Actions are delimited by LEFT_DELIM/RIGHT_DELIM.
    // Between actions, we have TEXT tokens.
    private List<HelmGoTemplateNode> parseBody() {
        return parseBodyUntil(null);
    }

    private List<HelmGoTemplateNode> parseBodyUntil(final TokenType stopToken) {
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
            if (token.getType() == TokenType.LEFT_DELIM) {
                pos++;
                if (parseActionBody(nodes, stopToken)) {
                    return nodes;
                }
                continue;
            }
            // Unknown token at body level - skip
            pos++;
        }
        return nodes;
    }

    /**
     * Parse action body between LEFT_DELIM and RIGHT_DELIM.
     * Returns true if END token was encountered (signaling body termination).
     * Go: action() calls pipeline("command", itemRightDelim).
     */
    private boolean parseActionBody(final List<HelmGoTemplateNode> nodes, final TokenType stopToken) {
        if (pos >= tokens.size()) {
            return false;
        }
        skipSpaces();

        if (pos >= tokens.size()) {
            return false;
        }
        final var first = tokens.get(pos);

        if (first.getType() == TokenType.RIGHT_DELIM) {
            pos++; // consume RIGHT_DELIM
            return false;
        }

        // Check for END keyword when stopToken is END
        if (first.getType() == TokenType.END && stopToken == TokenType.END) {
            pos++; // consume END
            skipSpaces();
            if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
                pos++; // consume RIGHT_DELIM
            }
            return true;
        }

        // Check for ELSE keyword - signals end of if-body
        if (first.getType() == TokenType.ELSE) {
            // Don't consume - let parseIf handle it
            return true;
        }

        // Check for control flow keywords
        if (first.getType() == TokenType.IF) {
            nodes.add(parseIf());
        } else if (first.getType() == TokenType.RANGE) {
            nodes.add(parseRange());
        } else if (first.getType() == TokenType.WITH) {
            nodes.add(parseWith());
        } else if (first.getType() == TokenType.DEFINE) {
            nodes.add(parseDefine());
        } else if (first.getType() == TokenType.BLOCK) {
            nodes.add(parseBlock());
        } else if (first.getType() == TokenType.TEMPLATE) {
            nodes.add(parseTemplate());
        } else if (first.getType() == TokenType.VARIABLE
                && findNextNonSpace(pos + 1) < tokens.size()
                && isAssign(tokens.get(findNextNonSpace(pos + 1)))) {
            nodes.add(parseAssign());
        } else {
            nodes.add(parsePipeline());
        }

        // Consume RIGHT_DELIM
        skipSpaces();
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
            pos++;
        }
        return false;
    }

    /**
     * Parse pipeline: VarDecl? Command { "|" Command } .
     * Go: pipeline(context, end) until end token (RIGHT_DELIM for top-level actions).
     */
    private PipelineNode parsePipeline() {
        final var commands = new ArrayList<HelmGoTemplateNode>();

        // Check for variable declaration prefix: $var := or $var =
        // Go handles this in pipeline() before parsing commands
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.VARIABLE
                && pos + 1 < tokens.size() && isAssign(tokens.get(pos + 1))) {
            // Don't consume - let the caller handle it via parseAssign
        }

        commands.add(parseCommand());

        // Pipeline: command { "|" command }
        while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.PIPE) {
            pos++; // consume PIPE
            skipSpaces();
            commands.add(parseCommand());
        }

        return new PipelineNode(commands);
    }

    /**
     * Parse command: Operand { Space Operand } .
     * Go: command() loops: operand, then checks for SPACE (continue), RIGHT_DELIM/RIGHT_PAREN (stop), PIPE (stop).
     */
    private HelmGoTemplateNode parseCommand() {
        skipSpaces();
        final var operands = new ArrayList<HelmGoTemplateNode>();

        // First operand
        final var first = parseOperand();
        if (first == null) {
            return new StringNode("");
        }
        operands.add(first);

        // Subsequent operands separated by SPACE
        while (pos < tokens.size()) {
            final var peek = tokens.get(pos);
            if (peek.getType() == TokenType.SPACE) {
                pos++; // consume SPACE
                skipSpaces();
                final var next = parseOperand();
                if (next == null) {
                    break;
                }
                operands.add(next);
            } else {
                break;
            }
        }

        if (operands.size() == 1) {
            return operands.get(0);
        }

        // Multiple operands: first is function name, rest are args
        final var funcName = coerceString(operands.get(0));
        final var args = new ArrayList<HelmGoTemplateNode>(operands.subList(1, operands.size()));
        return new FunctionCallNode(funcName, args);
    }

    /**
     * Parse operand: term { Field } .
     * Go: operand() calls term(), then chains .Field accesses.
     */
    private HelmGoTemplateNode parseOperand() {
        skipSpaces();
        if (pos >= tokens.size()) {
            return null;
        }

        final var token = tokens.get(pos);
        final var type = token.getType();

        // These tokens are NOT operands - return null to stop command parsing
        if (type == TokenType.PIPE || type == TokenType.RIGHT_DELIM || type == TokenType.DECLARE
                || type == TokenType.ASSIGN || type == TokenType.COMMA || type == TokenType.RPAREN
                || type == TokenType.RBRACK) {
            return null;
        }

        // Variable: $var
        if (type == TokenType.VARIABLE) {
            pos++;
            final var node = parseVariableRest(token.getValue());
            return node;
        }

        // Field: .field - chain consecutive FIELD tokens into a single path
        if (token.getType() == TokenType.FIELD) {
            pos++;
            final var path = new ArrayList<String>();
            path.add(""); // root context
            path.add(token.getValue().substring(1)); // strip leading dot
            // Chain consecutive FIELD tokens (Go: field() chains itemField tokens)
            while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.FIELD) {
                path.add(tokens.get(pos).getValue().substring(1));
                pos++;
            }
            return new VariableNode(path);
        }

        // Dot
        if (token.getType() == TokenType.DOT) {
            pos++;
            final var path = new ArrayList<String>();
            path.add(""); // root
            // Chain consecutive FIELD tokens
            while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.FIELD) {
                path.add(tokens.get(pos).getValue().substring(1));
                pos++;
            }
            return new VariableNode(path);
        }

        // Term: number, string, bool, nil, (pipeline), function call
        return parseTerm();
    }

    /**
     * Parse term: Number | String | nil | Boolean | '(' Pipeline ')' | identifier .
     * Go: term() handles the atomic values.
     */
    private HelmGoTemplateNode parseTerm() {
        skipSpaces();
        if (pos >= tokens.size()) {
            return new StringNode("");
        }

        final var token = tokens.get(pos);

        if (token.getType() == TokenType.STRING) {
            pos++;
            return new StringNode(unquote(token.getValue()));
        }
        if (token.getType() == TokenType.NUMBER) {
            pos++;
            return new NumberNode(token.getValue());
        }
        if (token.getType() == TokenType.BOOL) {
            pos++;
            return new BoolNode("true".equals(token.getValue()));
        }
        if (token.getType() == TokenType.NIL) {
            pos++;
            return new HelmGoTemplateNode.NilNode();
        }
        if (token.getType() == TokenType.LPAREN) {
            pos++; // consume (
            skipSpaces();
            final var expr = parsePipeline();
            skipSpaces();
            if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RPAREN) {
                pos++; // consume )
            }
            return expr;
        }
        if (token.getType() == TokenType.LBRACK) {
            // List literal: [elem1, elem2, ...]
            pos++; // consume [
            final var elements = new ArrayList<HelmGoTemplateNode>();
            skipSpaces();
            if (pos < tokens.size() && tokens.get(pos).getType() != TokenType.RBRACK) {
                elements.add(parsePipeline());
                while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.COMMA) {
                    pos++; // consume ,
                    skipSpaces();
                    elements.add(parsePipeline());
                }
            }
            skipSpaces();
            if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RBRACK) {
                pos++; // consume ]
            }
            return new ListNode(elements);
        }
        if (token.getType() == TokenType.IDENT) {
            pos++;
            final var name = token.getValue();
            return new IdentifierNode(name);
        }
        // Unknown - skip
        pos++;
        return new StringNode(token.getValue());
    }

    private AssignNode parseAssign() {
        final var varName = tokens.get(pos).getValue();
        pos++; // consume VARIABLE
        skipSpaces();
        final var isDeclare = tokens.get(pos).getType() == TokenType.DECLARE;
        pos++; // consume ASSIGN or DECLARE
        skipSpaces();
        final var value = parsePipeline();
        return new AssignNode(varName, value, isDeclare);
    }

    private IfNode parseIf() {
        pos++; // consume IF
        skipSpaces();
        final var condition = parsePipeline();
        skipSpaces();

        // Body until {{else}} or {{end}}
        var body = parseBodyUntil(TokenType.END);

        // Check for else/else-if chain - iterative like Go's parser
        final var elseBody = new ArrayList<HelmGoTemplateNode>();
        skipSpaces();
        while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.ELSE) {
            pos++; // consume ELSE
            skipSpaces();
            if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
                pos++; // consume RIGHT_DELIM
            }
            skipSpaces();
            if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.IF) {
                // else if: parse as nested if (Go handles this iteratively)
                pos++; // consume IF
                skipSpaces();
                final var elseIfCondition = parsePipeline();
                skipSpaces();
                // Consume RIGHT_DELIM after the else-if action
                if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
                    pos++;
                }
                final var elseIfBody = parseBodyUntil(TokenType.END);
                final var elseIfNode = new IfNode(elseIfCondition, elseIfBody, List.of());
                elseBody.add(elseIfNode);
                // Continue loop to check for more else/else-if
            } else {
                // plain else
                final var elseNodes = parseBodyUntil(TokenType.END);
                elseBody.addAll(elseNodes);
                break; // plain else ends the chain
            }
        }

        // Consume RIGHT_DELIM after END (if parseBodyUntil stopped at END)
        skipSpaces();
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
            pos++;
        }

        return new IfNode(condition, body, elseBody);
    }

    private RangeNode parseRange() {
        pos++; // consume RANGE
        skipSpaces();

        // Check for $k, $v := pattern or $v := pattern
        String keyVar = null;
        String valueVar = null;
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.VARIABLE) {
            if (pos + 2 < tokens.size()
                    && tokens.get(pos + 1).getType() == TokenType.COMMA) {
                // $k, $v :=
                keyVar = tokens.get(pos).getValue();
                pos += 2; // consume VAR and COMMA
                skipSpaces();
                valueVar = tokens.get(pos).getValue();
                pos++; // consume VAR
                skipSpaces();
                if (pos < tokens.size() && (tokens.get(pos).getType() == TokenType.DECLARE
                        || tokens.get(pos).getType() == TokenType.ASSIGN)) {
                    pos++; // consume := or =
                }
            } else if (pos + 1 < tokens.size() && isAssign(tokens.get(pos + 1))) {
                // $v :=
                valueVar = tokens.get(pos).getValue();
                pos += 2; // consume VAR and := / =
            }
        }

        skipSpaces();
        final var over = parsePipeline();
        skipSpaces();

        final var body = parseBodyUntil(TokenType.END);

        // Consume RIGHT_DELIM after END
        skipSpaces();
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
            pos++;
        }

        return new RangeNode(over, body, keyVar, valueVar);
    }

    private WithNode parseWith() {
        pos++; // consume WITH
        skipSpaces();
        final var target = parsePipeline();
        skipSpaces();

        final var body = parseBodyUntil(TokenType.END);

        // Consume RIGHT_DELIM after END
        skipSpaces();
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
            pos++;
        }

        return new WithNode(target, body);
    }

    private DefineNode parseDefine() {
        pos++; // consume DEFINE
        skipSpaces();
        final var name = expectString();
        skipSpaces();

        final var body = parseBodyUntil(TokenType.END);

        // Consume RIGHT_DELIM after END
        skipSpaces();
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
            pos++;
        }

        return new DefineNode(name, body);
    }

    private BlockNode parseBlock() {
        pos++; // consume BLOCK
        skipSpaces();
        final var name = expectString();
        skipSpaces();

        // Optional pipeline after name
        if (pos < tokens.size() && tokens.get(pos).getType() != TokenType.RIGHT_DELIM) {
            parsePipeline();
        }

        final var body = parseBodyUntil(TokenType.END);

        // Consume RIGHT_DELIM after END
        skipSpaces();
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
            pos++;
        }

        return new BlockNode(name, body);
    }

    private TemplateNode parseTemplate() {
        pos++; // consume TEMPLATE
        skipSpaces();
        final var name = expectString();
        skipSpaces();

        PipelineNode pipeline = null;
        if (pos < tokens.size() && tokens.get(pos).getType() != TokenType.RIGHT_DELIM
                && tokens.get(pos).getType() != TokenType.TEXT) {
            pipeline = parsePipeline();
        }

        // Consume RIGHT_DELIM
        skipSpaces();
        if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
            pos++;
        }

        return new TemplateNode(name, pipeline);
    }

    private void parseBodyUntilEnd() {
        // Parse body until END keyword
        while (pos < tokens.size()) {
            final var token = tokens.get(pos);
            if (token.getType() == TokenType.TEXT) {
                pos++;
                continue;
            }
            if (token.getType() == TokenType.LEFT_DELIM) {
                pos++; // consume LEFT_DELIM
                skipSpaces();
                if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.END) {
                    pos++; // consume END
                    skipSpaces();
                    if (pos < tokens.size() && tokens.get(pos).getType() == TokenType.RIGHT_DELIM) {
                        pos++; // consume RIGHT_DELIM
                    }
                    return;
                }
                // Not END - parse as action
                pos--; // back up to LEFT_DELIM
                parseActionBody(new ArrayList<>(), null);
                continue;
            }
            pos++;
        }
    }

    private void skipSpaces() {
        while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.SPACE) {
            pos++;
        }
    }

    private int findNextNonSpace(int from) {
        while (from < tokens.size() && tokens.get(from).getType() == TokenType.SPACE) {
            from++;
        }
        return from;
    }

    private void skipTextTokens() {
        while (pos < tokens.size() && (tokens.get(pos).getType() == TokenType.TEXT
                || tokens.get(pos).getType() == TokenType.SPACE)) {
            pos++;
        }
    }

    private boolean isAssign(final Token token) {
        return token.getType() == TokenType.ASSIGN || token.getType() == TokenType.DECLARE;
    }

    private String expectString() {
        skipSpaces();
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

    private HelmGoTemplateNode parseVariableRest(final String name) {
        final var path = new ArrayList<String>();
        if (name.startsWith(".")) {
            path.add("");
            final var parts = name.substring(1).split("\\.", -1);
            for (final var part : parts) {
                if (!part.isEmpty()) {
                    path.add(part);
                }
            }
        } else if (name.startsWith("$")) {
            path.add(name);
        } else {
            path.add(name);
        }
        // Chain .field accesses via FIELD tokens
        while (pos < tokens.size() && tokens.get(pos).getType() == TokenType.FIELD) {
            path.add(tokens.get(pos).getValue().substring(1));
            pos++;
        }
        return new VariableNode(path);
    }

    private String coerceString(final HelmGoTemplateNode node) {
        if (node instanceof IdentifierNode) {
            return ((IdentifierNode) node).getName();
        }
        if (node instanceof VariableNode) {
            final var path = ((VariableNode) node).getPath();
            if (path.size() == 1) {
                return path.get(0);
            }
            final var sb = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                if (i == 0) {
                    sb.append(path.get(i));
                } else {
                    sb.append(".").append(path.get(i));
                }
            }
            return sb.toString();
        }
        if (node instanceof StringNode) {
            return ((StringNode) node).getValue();
        }
        return node.toString();
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
