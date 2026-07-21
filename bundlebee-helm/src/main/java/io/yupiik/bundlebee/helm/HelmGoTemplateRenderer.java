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

import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a parsed Go template AST against a context.
 * Supports variable resolution, pipelines, conditionals, ranges, and function calls
 * via the CDI-discovered {@link HelmFunction} registry.
 */
@Log
public class HelmGoTemplateRenderer {

    private final Map<String, HelmFunction> functionsMap;
    private final Map<String, List<HelmGoTemplateNode>> defines = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> scopeStack = new ArrayDeque<>();

    public HelmGoTemplateRenderer(final Map<String, HelmFunction> functionsMap) {
        this.functionsMap = functionsMap;
        scopeStack.push(new HashMap<>());
    }

    public String render(final List<HelmGoTemplateNode> nodes, final Map<String, Object> context) {
        scopeStack.clear();
        scopeStack.push(new HashMap<>());
        final var scope = new HashMap<>(context);
        scope.put(".", context);
        scopeStack.push(scope);
        final var sb = new StringBuilder();
        renderNodes(nodes, sb);
        return sb.toString();
    }

    public void registerDefines(final List<HelmGoTemplateNode> nodes) {
        for (final var node : nodes) {
            if (node instanceof HelmGoTemplateNode.DefineNode) {
                final var defineNode = (HelmGoTemplateNode.DefineNode) node;
                defines.put(defineNode.getName(), defineNode.getBody());
            }
        }
    }

    private void renderNodes(final List<HelmGoTemplateNode> nodes, final StringBuilder sb) {
        for (int i = 0; i < nodes.size(); i++) {
            final var node = nodes.get(i);
            if (node instanceof HelmGoTemplateNode.TextNode) {
                final var textNode = (HelmGoTemplateNode.TextNode) node;
                var text = textNode.getText();
                if (textNode.isTrimLeft()) {
                    text = text.stripLeading();
                }
                if (textNode.isTrimRight() && i + 1 < nodes.size()) {
                    text = text.stripTrailing();
                }
                sb.append(text);
            } else if (node instanceof HelmGoTemplateNode.IfNode) {
                renderIf((HelmGoTemplateNode.IfNode) node, sb);
            } else if (node instanceof HelmGoTemplateNode.RangeNode) {
                renderRange((HelmGoTemplateNode.RangeNode) node, sb);
            } else if (node instanceof HelmGoTemplateNode.WithNode) {
                renderWith((HelmGoTemplateNode.WithNode) node, sb);
            } else if (node instanceof HelmGoTemplateNode.AssignNode) {
                final var assignNode = (HelmGoTemplateNode.AssignNode) node;
                final var value = evalPipeline(assignNode.getValue());
                currentScope().put(assignNode.getVariable(), value);
            } else if (node instanceof HelmGoTemplateNode.TemplateNode) {
                renderTemplate((HelmGoTemplateNode.TemplateNode) node, sb);
            } else if (node instanceof HelmGoTemplateNode.BlockNode) {
                final var blockNode = (HelmGoTemplateNode.BlockNode) node;
                final var templateBody = defines.get(blockNode.getName());
                if (templateBody != null) {
                    renderNodes(templateBody, sb);
                } else {
                    renderNodes(blockNode.getBody(), sb);
                }
            } else {
                sb.append(coerceString(evalNode(node)));
            }
        }
    }

    private void renderIf(final HelmGoTemplateNode.IfNode ifNode, final StringBuilder sb) {
        final var condition = evalPipeline(ifNode.getCondition());
        if (isTruthy(condition)) {
            scopeStack.push(new HashMap<>(currentScope()));
            try {
                renderNodes(ifNode.getBody(), sb);
            } finally {
                scopeStack.pop();
            }
        } else if (!ifNode.getElseBody().isEmpty()) {
            scopeStack.push(new HashMap<>(currentScope()));
            try {
                renderNodes(ifNode.getElseBody(), sb);
            } finally {
                scopeStack.pop();
            }
        }
    }

    private void renderRange(final HelmGoTemplateNode.RangeNode rangeNode, final StringBuilder sb) {
        final var iterable = evalPipeline(rangeNode.getOver());
        final var items = toIterable(iterable);
        if (items == null) {
            return;
        }
        int idx = 0;
        for (final var item : items) {
            final var rangeScope = new HashMap<>(currentScope());
            rangeScope.put(".", item);
            rangeScope.put("$", scopeStack.peek().get("$"));
            rangeScope.put("$idx", idx);
            rangeScope.put("$length", items.size());
            scopeStack.push(rangeScope);
            try {
                renderNodes(rangeNode.getBody(), sb);
            } finally {
                scopeStack.pop();
            }
            idx++;
        }
    }

    private void renderWith(final HelmGoTemplateNode.WithNode withNode, final StringBuilder sb) {
        final var target = evalPipeline(withNode.getTarget());
        if (!isTruthy(target)) {
            return;
        }
        final var withScope = new HashMap<>(currentScope());
        withScope.put(".", target);
        scopeStack.push(withScope);
        try {
            renderNodes(withNode.getBody(), sb);
        } finally {
            scopeStack.pop();
        }
    }

    private void renderTemplate(final HelmGoTemplateNode.TemplateNode templateNode, final StringBuilder sb) {
        final var name = templateNode.getName();
        final var body = defines.get(name);
        if (body == null) {
            log.fine(() -> "Template '" + name + "' not found, skipping");
            return;
        }
        if (templateNode.getPipeline() != null) {
            final var data = evalPipeline(templateNode.getPipeline());
            final var templateScope = new HashMap<>(currentScope());
            templateScope.put(".", data);
            scopeStack.push(templateScope);
            try {
                renderNodes(body, sb);
            } finally {
                scopeStack.pop();
            }
        } else {
            renderNodes(body, sb);
        }
    }

    private Object evalPipeline(final HelmGoTemplateNode node) {
        if (node instanceof HelmGoTemplateNode.PipelineNode) {
            final var pipelineNode = (HelmGoTemplateNode.PipelineNode) node;
            final var functions = pipelineNode.getFunctions();
            if (functions.isEmpty()) {
                return null;
            }
            var result = evalNode(functions.get(0));
            for (int i = 1; i < functions.size(); i++) {
                final var funcNode = functions.get(i);
                if (funcNode instanceof HelmGoTemplateNode.FunctionCallNode) {
                    final var fn = (HelmGoTemplateNode.FunctionCallNode) funcNode;
                    final var helmFunc = functionsMap.get(fn.getName());
                    if (helmFunc != null) {
                        final var args = new ArrayList<Object>();
                        for (final var arg : fn.getArgs()) {
                            args.add(evalNode(arg));
                        }
                        args.add(result);
                        result = helmFunc.execute(args.toArray());
                    } else {
                        result = evalNode(funcNode);
                    }
                } else {
                    result = evalNode(funcNode);
                }
            }
            return result;
        }
        return evalNode(node);
    }

    private Object evalNode(final HelmGoTemplateNode node) {
        if (node instanceof HelmGoTemplateNode.StringNode) {
            return ((HelmGoTemplateNode.StringNode) node).getValue();
        }
        if (node instanceof HelmGoTemplateNode.NumberNode) {
            return parseNumber(((HelmGoTemplateNode.NumberNode) node).getValue());
        }
        if (node instanceof HelmGoTemplateNode.BoolNode) {
            return ((HelmGoTemplateNode.BoolNode) node).isValue();
        }
        if (node instanceof HelmGoTemplateNode.VariableNode) {
            return resolveVariable(((HelmGoTemplateNode.VariableNode) node).getPath());
        }
        if (node instanceof HelmGoTemplateNode.FunctionCallNode) {
            return evalFunction((HelmGoTemplateNode.FunctionCallNode) node);
        }
        if (node instanceof HelmGoTemplateNode.IndexNode) {
            final var indexNode = (HelmGoTemplateNode.IndexNode) node;
            final var target = evalNode(indexNode.getTarget());
            final var index = evalNode(indexNode.getIndex());
            return resolveIndex(target, index);
        }
        if (node instanceof HelmGoTemplateNode.SliceNode) {
            final var sliceNode = (HelmGoTemplateNode.SliceNode) node;
            final var target = evalNode(sliceNode.getTarget());
            final var low = evalNode(sliceNode.getLow());
            final var high = sliceNode.getHigh() != null ? evalNode(sliceNode.getHigh()) : null;
            return resolveSlice(target, low, high);
        }
        if (node instanceof HelmGoTemplateNode.ListNode) {
            final var listNode = (HelmGoTemplateNode.ListNode) node;
            final var result = new ArrayList<>();
            for (final var element : listNode.getElements()) {
                result.add(evalNode(element));
            }
            return result;
        }
        if (node instanceof HelmGoTemplateNode.PipelineNode) {
            return evalPipeline((HelmGoTemplateNode.PipelineNode) node);
        }
        return null;
    }

    private Object evalFunction(final HelmGoTemplateNode.FunctionCallNode fn) {
        if ("include".equals(fn.getName())) {
            return evalInclude(fn);
        }
        if ("tpl".equals(fn.getName())) {
            return evalTpl(fn);
        }
        final var helmFunc = functionsMap.get(fn.getName());
        if (helmFunc == null) {
            log.fine(() -> "Unknown helm function: " + fn.getName());
            return null;
        }
        final var args = new Object[fn.getArgs().size()];
        for (int i = 0; i < fn.getArgs().size(); i++) {
            args[i] = evalNode(fn.getArgs().get(i));
        }
        return helmFunc.execute(args);
    }

    private Object evalInclude(final HelmGoTemplateNode.FunctionCallNode fn) {
        if (fn.getArgs().isEmpty()) {
            return "";
        }
        final var name = coerceString(evalNode(fn.getArgs().get(0)));
        final var body = defines.get(name);
        if (body == null) {
            log.fine(() -> "Include template '" + name + "' not found");
            return "";
        }
        final var data = fn.getArgs().size() > 1 ? evalNode(fn.getArgs().get(1)) : null;
        final var includeScope = new HashMap<>(currentScope());
        if (data != null) {
            includeScope.put(".", data);
        }
        scopeStack.push(includeScope);
        try {
            final var sb = new StringBuilder();
            renderNodes(body, sb);
            return sb.toString();
        } finally {
            scopeStack.pop();
        }
    }

    private Object evalTpl(final HelmGoTemplateNode.FunctionCallNode fn) {
        if (fn.getArgs().isEmpty()) {
            return "";
        }
        final var templateStr = coerceString(evalNode(fn.getArgs().get(0)));
        if (templateStr.isEmpty()) {
            return "";
        }
        final var data = fn.getArgs().size() > 1 ? evalNode(fn.getArgs().get(1)) : currentScope().get(".");
        final var tplScope = new HashMap<>(currentScope());
        tplScope.put(".", data);
        scopeStack.push(tplScope);
        try {
            final var lexer = new HelmGoTemplateLexer();
            final var tokens = lexer.tokenize(templateStr);
            final var parser = new HelmGoTemplateParser(tokens);
            final var nodes = parser.parse();
            final var sb = new StringBuilder();
            renderNodes(nodes, sb);
            return sb.toString();
        } finally {
            scopeStack.pop();
        }
    }

    @SuppressWarnings("unchecked")
    private Object resolveVariable(final List<String> path) {
        if (path.isEmpty()) {
            return null;
        }
        Object current;
        if ("$".equals(path.get(0))) {
            current = currentScope().get("$");
            if (current == null) {
                current = currentScope().get(".");
            }
            if (path.size() == 1) {
                return current;
            }
            for (int i = 1; i < path.size(); i++) {
                if (current == null) {
                    return null;
                }
                current = resolveField(current, path.get(i));
            }
            return current;
        }
        if (path.get(0).isEmpty()) {
            current = currentScope().get(".");
            for (int i = 1; i < path.size(); i++) {
                if (current == null) {
                    return null;
                }
                current = resolveField(current, path.get(i));
            }
            return current;
        }
        current = currentScope().get(path.get(0));
        if (current == null) {
            current = currentScope().get(".");
        }
        for (int i = 1; i < path.size(); i++) {
            if (current == null) {
                return null;
            }
            current = resolveField(current, path.get(i));
        }
        return current;
    }

    private Object resolveField(final Object obj, final String field) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Map) {
            final var map = (Map<?, ?>) obj;
            return map.get(field);
        }
        if (obj instanceof List) {
            final var list = (List<?>) obj;
            try {
                return list.get(Integer.parseInt(field));
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Object resolveIndex(final Object target, final Object index) {
        if (target instanceof Map) {
            return ((Map<?, ?>) target).get(coerceString(index));
        }
        if (target instanceof List && index instanceof Number) {
            return ((List<?>) target).get(((Number) index).intValue());
        }
        return null;
    }

    private Object resolveSlice(final Object target, final Object low, final Object high) {
        if (target instanceof String) {
            final var s = (String) target;
            final int l = low instanceof Number ? ((Number) low).intValue() : 0;
            final int h = high instanceof Number ? ((Number) high).intValue() : s.length();
            return s.substring(Math.max(0, l), Math.min(s.length(), h));
        }
        if (target instanceof List) {
            final var l = (List<?>) target;
            final int lo = low instanceof Number ? ((Number) low).intValue() : 0;
            final int hi = high instanceof Number ? ((Number) high).intValue() : l.size();
            return l.subList(Math.max(0, lo), Math.min(l.size(), hi));
        }
        return null;
    }

    private Map<String, Object> currentScope() {
        return scopeStack.peek();
    }

    private boolean isTruthy(final Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof List) {
            return !((List<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Object> toIterable(final Object obj) {
        if (obj instanceof List) {
            return (List<Object>) obj;
        }
        if (obj instanceof Map) {
            return new ArrayList<>(((Map<?, ?>) obj).values());
        }
        if (obj instanceof String) {
            return ((String) obj).chars().mapToObj(c -> String.valueOf((char) c)).collect(java.util.stream.Collectors.toList());
        }
        return null;
    }

    private String coerceString(final Object obj) {
        if (obj == null) {
            return "<nil>";
        }
        return obj.toString();
    }

    private Object parseNumber(final String s) {
        if (s.contains(".")) {
            return Double.parseDouble(s);
        }
        try {
            final long l = Long.parseLong(s);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        } catch (final NumberFormatException e) {
            return Double.parseDouble(s);
        }
    }
}
