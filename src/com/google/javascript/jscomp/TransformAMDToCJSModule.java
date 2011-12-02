/*
 * Copyright 2011 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import java.util.List;

import com.google.common.collect.Lists;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

/**
 *
 */
class TransformAMDToCJSModule implements CompilerPass {

  private final AbstractCompiler compiler;

  private final DiagnosticType UNSUPPORTED_DEFINE_SIGNATURE = DiagnosticType.error("UNSUPPORTED_DEFINE_SIGNATURE",
      "Only define(function() ...) and define(['dep', 'dep1'], function(d0, d2, [exports, module]) ...) forms are currently supported.");

  TransformAMDToCJSModule(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, new TransformAMDModulesCallback());
  }

  private void unsupportedDefineError(String sourceName, Node n) {
    compiler.report(JSError.make(sourceName, n, UNSUPPORTED_DEFINE_SIGNATURE));
  }

  private boolean isSpecialModuleName(String moduleName) {
    return "exports".equals(moduleName) ||
        "require".equals(moduleName)  ||
        "module".equals(moduleName);
  }

  /**
   */
  private class TransformAMDModulesCallback
      extends AbstractPostOrderCallback {

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall() && n.getFirstChild() != null &&
          n.getFirstChild().isName() && "define".equals(n.getFirstChild().getString())) {
        Node requiresNode = null;
        Node callback = null;
        Node onlyExport = null;
        int defineArity = n.getChildCount() - 1;
        if (defineArity == 0) {
          unsupportedDefineError(t.getSourceName(), n);
          return; // Can't handle this.
        }
        else if (defineArity == 1) {
          callback = n.getChildAtIndex(1);
          if (callback.isObjectLit()) {
            onlyExport = callback;
            callback = null;
          }
        }
        else if (defineArity == 2) {
          requiresNode = n.getChildAtIndex(1);
          callback = n.getChildAtIndex(2);
        }
        else if(defineArity >= 3) {
          unsupportedDefineError(t.getSourceName(), n);
          return; // Can't handle this.
        }

        Node context = parent.getParent(); // Removes EXPR_RESULT around define call;

        if (onlyExport != null) {
          onlyExport.getParent().removeChild(onlyExport);
          context.replaceChild(parent, IR.exprResult(
              IR.assign(IR.name("exports"), onlyExport)).copyInformationFromForTree(onlyExport));
          compiler.reportCodeChange();
          return;
        }

        if (!callback.isFunction()) {
          unsupportedDefineError(t.getSourceName(), n);
          return; // Can't handle this.
        }
        if (requiresNode != null && !requiresNode.isArrayLit()) {
          unsupportedDefineError(t.getSourceName(), n);
          return; // Can't handle this.
        }

        int i = 0;
        Node paramList = callback.getChildAtIndex(1);
        for (Node aliasNode : paramList.children()) {
          String moduleName = null;
          Node modNode = requiresNode != null && requiresNode.getChildCount() > i ?
              requiresNode.getChildAtIndex(i) :
              null;

          if (modNode != null) {
            moduleName = modNode.getString();
          }
          if (aliasNode != null) {
            String aliasName = aliasNode.getString();

            if (isSpecialModuleName(moduleName)) {
              continue;
            }

            if (moduleName != null && moduleName.indexOf('!') == -1) { // TODO(malteubl) Handle ?,! in define better.
              Node call = IR.call(IR.name("require"), IR.string(moduleName));
              call.putBooleanProp(Node.FREE_CALL, true);
              context.addChildToFront(
                  IR.var(IR.name(aliasName), call).copyInformationFromForTree(aliasNode));
            } else {
              // ignore exports, require and module (because they are implicit in CJS);
              if (isSpecialModuleName(aliasName)) {
                continue;
              }
              context.addChildToFront(
                  IR.var(IR.name(aliasName)).copyInformationFromForTree(aliasNode));
            }
          }
          i++;
        }

        Node callbackBlock = callback.getChildAtIndex(2);

        NodeTraversal.traverse(compiler, callbackBlock, new NodeTraversal.AbstractShallowCallback() {
          @Override
          public void visit(NodeTraversal t, Node n, Node parent) {
            if (n.isReturn() && n.hasChildren()) {
              Node retVal = n.getFirstChild();
              n.removeChild(retVal);
              parent.replaceChild(n, IR.exprResult(
                  IR.assign(
                      IR.getprop(IR.name("module"), IR.string("exports")).copyInformationFromForTree(n),
                      retVal).copyInformationFrom(n)).copyInformationFrom(n));
            }
          }
        });

        int curIndex = context.getIndexOfChild(parent);
        context.removeChild(parent);
        Node before = context.getChildAtIndex(curIndex);
        for (Node body : callbackBlock.children()) {
          body.getParent().removeChild(body);
          if (before != null) {
            context.addChildBefore(body, before);
          }
          context.addChildToBack(body);
        }
        compiler.reportCodeChange();
      }
    }
  }
}
