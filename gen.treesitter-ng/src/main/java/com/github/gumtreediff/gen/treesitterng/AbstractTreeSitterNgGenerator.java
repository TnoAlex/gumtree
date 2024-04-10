/*
 * This file is part of GumTree.
 *
 * GumTree is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GumTree is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with GumTree.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2021 Jean-Rémy Falleri <jr.falleri@gmail.com>
 */
package com.github.gumtreediff.gen.treesitterng;

import com.github.gumtreediff.gen.TreeGenerator;
import com.github.gumtreediff.tree.Tree;
import com.github.gumtreediff.tree.TreeContext;
import com.github.gumtreediff.tree.TypeSet;
import com.github.gumtreediff.utils.Pair;
import org.treesitter.*;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.Reader;
import java.util.*;

public abstract class AbstractTreeSitterNgGenerator extends TreeGenerator {

    private static final String RULES_FILE = "rules.yml";

    private static final String YAML_IGNORED = "ignored";
    private static final String YAML_LABEL_IGNORED = "label_ignored";
    private static final String YAML_FLATTENED = "flattened";
    private static final String YAML_ALIASED = "aliased";

    private static final Map<String, Map<String, Object>> RULES;

    static {
        Yaml yaml = new Yaml();
        RULES = yaml.load(Thread.currentThread().getContextClassLoader().getResourceAsStream(RULES_FILE));
    }

    @Override
    protected TreeContext generate(Reader r) {
        TSParser parser = new TSParser();
        TSLanguage language = getTreeSitterLanguage();
        parser.setLanguage(language);
        BufferedReader bufferedReader = new BufferedReader(r);
        List<String> contentLines = bufferedReader.lines().toList();
        String content = String.join(System.lineSeparator(), contentLines);
        TSTree tree = parser.parseString(null, content);
        Map<String, Object> currentRule = RULES.getOrDefault(getLanguageName(), new HashMap<>());
        return generateFromTressSitterTree(contentLines, currentRule, tree);
    }

    private static String getLabel(List<String> contentLines, TSNode node) {
        int startRow = node.getStartPoint().getRow();
        int startColumn = node.getStartPoint().getColumn();
        int endRow = node.getEndPoint().getRow();
        int endColumn = node.getEndPoint().getColumn();
        List<String> substringLines;
        if (startRow == endRow) {
            substringLines = Collections.singletonList(contentLines.get(startRow).substring(
                    startColumn, endColumn));
        } else {
            substringLines = new ArrayList<>();
            String startLineSubstring = contentLines.get(startRow).substring(startColumn);
            List<String> middleLines = contentLines.subList(startRow + 1, endRow);
            String endLineSubstring = contentLines.get(endRow).substring(0, endColumn);
            substringLines.add(startLineSubstring);
            substringLines.addAll(middleLines);
            substringLines.add(endLineSubstring);
        }

        return String.join("\n", substringLines);
    }

    private static int calculateOffset(List<String> contentLines, TSPoint point) {
        int startRow = point.getRow();
        int startColumn = point.getColumn();
        int offset = 0;
        for (int i = 0; i < startRow; i++) {
            offset += contentLines.get(i).length() + System.lineSeparator().length();
        }
        offset += startColumn;
        return offset;
    }

    @SuppressWarnings("unchecked")
    protected static Pair<Tree, Boolean> tsNode2GumTree(
            List<String> contentLines, Map<String, Object> currentRule, TreeContext context, TSNode node) {
        String type = node.getType();
        TSNode parent = node.getParent();
        String parentAndChildType = parent.isNull() ? type : (parent.getType() + " " + type);
        String label = getLabel(contentLines, node);
        if (currentRule.containsKey(YAML_IGNORED)) {
            List<String> ignores = (List<String>) currentRule.get(YAML_IGNORED);
            if (ignores.contains(type)) {
                return null;
            }
            if (ignores.contains(parentAndChildType)) {
                return null;
            }
        }
        if (currentRule.containsKey(YAML_LABEL_IGNORED)) {
            List<String> ignores = (List<String>) currentRule.get(YAML_LABEL_IGNORED);
            if (ignores.contains(label)) {
                return null;
            }
        }
        if (currentRule.containsKey(YAML_ALIASED)) {
            Map<String, String> alias = (Map<String, String>) currentRule.get(YAML_ALIASED);
            if (alias.containsKey(type)) {
                type = alias.get(type);
            } else if (alias.containsKey(parentAndChildType)) {
                type = alias.get(parentAndChildType);
            }
        }
        boolean flatten = false;
        if (currentRule.containsKey(YAML_FLATTENED)) {
            List<String> flattenMap = (List<String>) currentRule.get(YAML_FLATTENED);
            if (flattenMap.contains(type)) {
                flatten = true;
            } else if (flattenMap.contains(parentAndChildType)) {
                flatten = true;
            }
        }
        Tree tree;
        if (node.getChildCount() == 0) {
            tree = context.createTree(TypeSet.type(type), label);
        } else {
            tree = context.createTree(TypeSet.type(type));
        }
        tree.setPos(calculateOffset(contentLines, node.getStartPoint()));
        tree.setLength(label.length());
        return new Pair<>(tree, flatten);
    }

    private static TreeContext generateFromTressSitterTree(
            List<String> contentLines, Map<String, Object> currentRule, TSTree tree) {
        TSNode rootNode = tree.getRootNode();
        TreeContext context = new TreeContext();
        Pair<Tree, Boolean> rootPair = tsNode2GumTree(contentLines, currentRule, context, rootNode);
        if (rootPair == null) {
            return context;
        }
        context.setRoot(rootPair.first);
        Stack<TSNode> tsNodeStack = new Stack<>();
        Stack<Pair<Tree, Boolean>> treeStack = new Stack<>();
        tsNodeStack.push(rootNode);
        treeStack.push(rootPair);
        while (!tsNodeStack.isEmpty()) {
            TSNode tsNodeNow = tsNodeStack.pop();
            Pair<Tree, Boolean> treeNow = treeStack.pop();
            // flatten here
            if (treeNow.second) {
                continue;
            }
            int childCount = tsNodeNow.getChildCount();
            for (int i = 0; i < childCount; i++) {
                TSNode child = tsNodeNow.getChild(i);
                Pair<Tree, Boolean> childTree = tsNode2GumTree(contentLines, currentRule, context, child);
                if (childTree != null) {
                    treeNow.first.addChild(childTree.first);
                    tsNodeStack.push(child);
                    treeStack.push(childTree);
                }
            }
        }
        return context;
    }

    protected abstract TSLanguage getTreeSitterLanguage();

    protected abstract String getLanguageName();
}