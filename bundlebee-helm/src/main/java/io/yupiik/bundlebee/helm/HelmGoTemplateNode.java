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

import java.util.List;
import java.util.Objects;

/**
 * AST nodes for Go template parsing.
 */
public abstract class HelmGoTemplateNode {

    public static class TextNode extends HelmGoTemplateNode {
        private final String text;
        private final boolean trimLeft;
        private final boolean trimRight;

        public TextNode(final String text, final boolean trimLeft, final boolean trimRight) {
            this.text = text;
            this.trimLeft = trimLeft;
            this.trimRight = trimRight;
        }

        public String getText() {
            return this.text;
        }

        public boolean isTrimLeft() {
            return this.trimLeft;
        }

        public boolean isTrimRight() {
            return this.trimRight;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.TextNode(text=" + this.getText() + ", trimLeft=" + this.isTrimLeft()
                    + ", trimRight=" + this.isTrimRight() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof TextNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && this.isTrimLeft() == other.isTrimLeft()
                    && this.isTrimRight() == other.isTrimRight()
                    && Objects.equals(this.getText(), other.getText());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.TextNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.isTrimLeft(), this.isTrimRight(), this.getText());
        }
    }

    public static class VariableNode extends HelmGoTemplateNode {
        private final List<String> path;

        public VariableNode(final List<String> path) {
            this.path = path;
        }

        public List<String> getPath() {
            return this.path;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.VariableNode(path=" + this.getPath() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof VariableNode other)) {
                return false;
            }
            return other.canEqual(this) && Objects.equals(this.getPath(), other.getPath());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.VariableNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getPath());
        }
    }

    public static class FunctionCallNode extends HelmGoTemplateNode {
        private final String name;
        private final List<HelmGoTemplateNode> args;

        public FunctionCallNode(final String name, final List<HelmGoTemplateNode> args) {
            this.name = name;
            this.args = args;
        }

        public String getName() {
            return this.name;
        }

        public List<HelmGoTemplateNode> getArgs() {
            return this.args;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.FunctionCallNode(name=" + this.getName() + ", args=" + this.getArgs() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof FunctionCallNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getName(), other.getName())
                    && Objects.equals(this.getArgs(), other.getArgs());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.FunctionCallNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getName(), this.getArgs());
        }
    }

    public static class PipelineNode extends HelmGoTemplateNode {
        private final List<HelmGoTemplateNode> functions;

        public PipelineNode(final List<HelmGoTemplateNode> functions) {
            this.functions = functions;
        }

        public List<HelmGoTemplateNode> getFunctions() {
            return this.functions;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.PipelineNode(functions=" + this.getFunctions() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof PipelineNode other)) {
                return false;
            }
            return other.canEqual(this) && Objects.equals(this.getFunctions(), other.getFunctions());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.PipelineNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getFunctions());
        }
    }

    public static class IfNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode condition;
        private final List<HelmGoTemplateNode> body;
        private final List<HelmGoTemplateNode> elseBody;

        public IfNode(
                final HelmGoTemplateNode condition,
                final List<HelmGoTemplateNode> body,
                final List<HelmGoTemplateNode> elseBody) {
            this.condition = condition;
            this.body = body;
            this.elseBody = elseBody;
        }

        public HelmGoTemplateNode getCondition() {
            return this.condition;
        }

        public List<HelmGoTemplateNode> getBody() {
            return this.body;
        }

        public List<HelmGoTemplateNode> getElseBody() {
            return this.elseBody;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.IfNode(condition=" + this.getCondition() + ", body=" + this.getBody()
                    + ", elseBody=" + this.getElseBody() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof IfNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getCondition(), other.getCondition())
                    && Objects.equals(this.getBody(), other.getBody())
                    && Objects.equals(this.getElseBody(), other.getElseBody());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.IfNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getCondition(), this.getBody(), this.getElseBody());
        }
    }

    public static class RangeNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode over;
        private final List<HelmGoTemplateNode> body;
        private final String keyVar;
        private final String valueVar;

        public RangeNode(
                final HelmGoTemplateNode over,
                final List<HelmGoTemplateNode> body,
                final String keyVar,
                final String valueVar) {
            this.over = over;
            this.body = body;
            this.keyVar = keyVar;
            this.valueVar = valueVar;
        }

        public HelmGoTemplateNode getOver() {
            return this.over;
        }

        public List<HelmGoTemplateNode> getBody() {
            return this.body;
        }

        public String getKeyVar() {
            return this.keyVar;
        }

        public String getValueVar() {
            return this.valueVar;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.RangeNode(over=" + this.getOver() + ", body=" + this.getBody() + ", keyVar="
                    + this.getKeyVar() + ", valueVar=" + this.getValueVar() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof RangeNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getOver(), other.getOver())
                    && Objects.equals(this.getBody(), other.getBody())
                    && Objects.equals(this.getKeyVar(), other.getKeyVar())
                    && Objects.equals(this.getValueVar(), other.getValueVar());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.RangeNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getOver(), this.getBody(), this.getKeyVar(), this.getValueVar());
        }
    }

    public static class WithNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode target;
        private final List<HelmGoTemplateNode> body;

        public WithNode(final HelmGoTemplateNode target, final List<HelmGoTemplateNode> body) {
            this.target = target;
            this.body = body;
        }

        public HelmGoTemplateNode getTarget() {
            return this.target;
        }

        public List<HelmGoTemplateNode> getBody() {
            return this.body;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.WithNode(target=" + this.getTarget() + ", body=" + this.getBody() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof WithNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getTarget(), other.getTarget())
                    && Objects.equals(this.getBody(), other.getBody());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.WithNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getTarget(), this.getBody());
        }
    }

    public static class AssignNode extends HelmGoTemplateNode {
        private final String variable;
        private final HelmGoTemplateNode value;
        private final boolean declare; // true for :=, false for =

        public AssignNode(final String variable, final HelmGoTemplateNode value, final boolean declare) {
            this.variable = variable;
            this.value = value;
            this.declare = declare;
        }

        public String getVariable() {
            return this.variable;
        }

        public HelmGoTemplateNode getValue() {
            return this.value;
        }

        public boolean isDeclare() {
            return this.declare;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.AssignNode(variable=" + this.getVariable() + ", value=" + this.getValue()
                    + ", declare=" + this.isDeclare() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof AssignNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && this.isDeclare() == other.isDeclare()
                    && Objects.equals(this.getVariable(), other.getVariable())
                    && Objects.equals(this.getValue(), other.getValue());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.AssignNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.isDeclare(), this.getVariable(), this.getValue());
        }
    }

    public static class DefineNode extends HelmGoTemplateNode {
        private final String name;
        private final List<HelmGoTemplateNode> body;

        public DefineNode(final String name, final List<HelmGoTemplateNode> body) {
            this.name = name;
            this.body = body;
        }

        public String getName() {
            return this.name;
        }

        public List<HelmGoTemplateNode> getBody() {
            return this.body;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.DefineNode(name=" + this.getName() + ", body=" + this.getBody() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof DefineNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getName(), other.getName())
                    && Objects.equals(this.getBody(), other.getBody());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.DefineNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getName(), this.getBody());
        }
    }

    public static class BlockNode extends HelmGoTemplateNode {
        private final String name;
        private final List<HelmGoTemplateNode> body;

        public BlockNode(final String name, final List<HelmGoTemplateNode> body) {
            this.name = name;
            this.body = body;
        }

        public String getName() {
            return this.name;
        }

        public List<HelmGoTemplateNode> getBody() {
            return this.body;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.BlockNode(name=" + this.getName() + ", body=" + this.getBody() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof BlockNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getName(), other.getName())
                    && Objects.equals(this.getBody(), other.getBody());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.BlockNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getName(), this.getBody());
        }
    }

    public static class TemplateNode extends HelmGoTemplateNode {
        private final String name;
        private final HelmGoTemplateNode pipeline;

        public TemplateNode(final String name, final HelmGoTemplateNode pipeline) {
            this.name = name;
            this.pipeline = pipeline;
        }

        public String getName() {
            return this.name;
        }

        public HelmGoTemplateNode getPipeline() {
            return this.pipeline;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.TemplateNode(name=" + this.getName() + ", pipeline=" + this.getPipeline() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof TemplateNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getName(), other.getName())
                    && Objects.equals(this.getPipeline(), other.getPipeline());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.TemplateNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getName(), this.getPipeline());
        }
    }

    public static class IndexNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode target;
        private final HelmGoTemplateNode index;

        public IndexNode(final HelmGoTemplateNode target, final HelmGoTemplateNode index) {
            this.target = target;
            this.index = index;
        }

        public HelmGoTemplateNode getTarget() {
            return this.target;
        }

        public HelmGoTemplateNode getIndex() {
            return this.index;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.IndexNode(target=" + this.getTarget() + ", index=" + this.getIndex() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof IndexNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getTarget(), other.getTarget())
                    && Objects.equals(this.getIndex(), other.getIndex());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.IndexNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getTarget(), this.getIndex());
        }
    }

    public static class SliceNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode target;
        private final HelmGoTemplateNode low;
        private final HelmGoTemplateNode high;

        public SliceNode(final HelmGoTemplateNode target, final HelmGoTemplateNode low, final HelmGoTemplateNode high) {
            this.target = target;
            this.low = low;
            this.high = high;
        }

        public HelmGoTemplateNode getTarget() {
            return this.target;
        }

        public HelmGoTemplateNode getLow() {
            return this.low;
        }

        public HelmGoTemplateNode getHigh() {
            return this.high;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.SliceNode(target=" + this.getTarget() + ", low=" + this.getLow() + ", high="
                    + this.getHigh() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof SliceNode other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getTarget(), other.getTarget())
                    && Objects.equals(this.getLow(), other.getLow())
                    && Objects.equals(this.getHigh(), other.getHigh());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.SliceNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getTarget(), this.getLow(), this.getHigh());
        }
    }

    public static class ListNode extends HelmGoTemplateNode {
        private final List<HelmGoTemplateNode> elements;

        public ListNode(final List<HelmGoTemplateNode> elements) {
            this.elements = elements;
        }

        public List<HelmGoTemplateNode> getElements() {
            return this.elements;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.ListNode(elements=" + this.getElements() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof ListNode other)) {
                return false;
            }
            return other.canEqual(this) && Objects.equals(this.getElements(), other.getElements());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.ListNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getElements());
        }
    }

    public static class StringNode extends HelmGoTemplateNode {
        private final String value;

        public StringNode(final String value) {
            this.value = value;
        }

        public String getValue() {
            return this.value;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.StringNode(value=" + this.getValue() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof StringNode other)) {
                return false;
            }
            return other.canEqual(this) && Objects.equals(this.getValue(), other.getValue());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.StringNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getValue());
        }
    }

    public static class NumberNode extends HelmGoTemplateNode {
        private final String value;

        public NumberNode(final String value) {
            this.value = value;
        }

        public String getValue() {
            return this.value;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.NumberNode(value=" + this.getValue() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof NumberNode other)) {
                return false;
            }
            return other.canEqual(this) && Objects.equals(this.getValue(), other.getValue());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.NumberNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getValue());
        }
    }

    public static class BoolNode extends HelmGoTemplateNode {
        private final boolean value;

        public BoolNode(final boolean value) {
            this.value = value;
        }

        public boolean isValue() {
            return this.value;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.BoolNode(value=" + this.isValue() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof BoolNode other)) {
                return false;
            }
            return other.canEqual(this) && this.isValue() == other.isValue();
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.BoolNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.isValue());
        }
    }

    public static class IdentifierNode extends HelmGoTemplateNode {
        private final String name;

        public IdentifierNode(final String name) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }

        @Override
        public String toString() {
            return "HelmGoTemplateNode.IdentifierNode(name=" + this.getName() + ")";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof IdentifierNode other)) {
                return false;
            }
            return other.canEqual(this) && Objects.equals(this.getName(), other.getName());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.IdentifierNode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.getName());
        }
    }

    public static class NilNode extends HelmGoTemplateNode {
        public NilNode() {}

        @Override
        public String toString() {
            return "HelmGoTemplateNode.NilNode()";
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof NilNode other)) {
                return false;
            }
            return other.canEqual(this);
        }

        protected boolean canEqual(final Object other) {
            return other instanceof HelmGoTemplateNode.NilNode;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
