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

import lombok.Data;

import java.util.List;

/**
 * AST nodes for Go template parsing.
 */
public abstract class HelmGoTemplateNode {

    @Data
    public static class TextNode extends HelmGoTemplateNode {
        private final String text;
        private final boolean trimLeft;
        private final boolean trimRight;
    }

    @Data
    public static class VariableNode extends HelmGoTemplateNode {
        private final List<String> path;
    }

    @Data
    public static class FunctionCallNode extends HelmGoTemplateNode {
        private final String name;
        private final List<HelmGoTemplateNode> args;
    }

    @Data
    public static class PipelineNode extends HelmGoTemplateNode {
        private final List<HelmGoTemplateNode> functions;
    }

    @Data
    public static class IfNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode condition;
        private final List<HelmGoTemplateNode> body;
        private final List<HelmGoTemplateNode> elseBody;
    }

    @Data
    public static class RangeNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode over;
        private final List<HelmGoTemplateNode> body;
    }

    @Data
    public static class WithNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode target;
        private final List<HelmGoTemplateNode> body;
    }

    @Data
    public static class AssignNode extends HelmGoTemplateNode {
        private final String variable;
        private final HelmGoTemplateNode value;
    }

    @Data
    public static class DefineNode extends HelmGoTemplateNode {
        private final String name;
        private final List<HelmGoTemplateNode> body;
    }

    @Data
    public static class BlockNode extends HelmGoTemplateNode {
        private final String name;
        private final List<HelmGoTemplateNode> body;
    }

    @Data
    public static class TemplateNode extends HelmGoTemplateNode {
        private final String name;
        private final HelmGoTemplateNode pipeline;
    }

    @Data
    public static class IndexNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode target;
        private final HelmGoTemplateNode index;
    }

    @Data
    public static class SliceNode extends HelmGoTemplateNode {
        private final HelmGoTemplateNode target;
        private final HelmGoTemplateNode low;
        private final HelmGoTemplateNode high;
    }

    @Data
    public static class ListNode extends HelmGoTemplateNode {
        private final List<HelmGoTemplateNode> elements;
    }

    @Data
    public static class StringNode extends HelmGoTemplateNode {
        private final String value;
    }

    @Data
    public static class NumberNode extends HelmGoTemplateNode {
        private final String value;
    }

    @Data
    public static class BoolNode extends HelmGoTemplateNode {
        private final boolean value;
    }
}
