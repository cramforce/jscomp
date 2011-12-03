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

import com.google.common.annotations.VisibleForTesting;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

/**
 * Rewrites an AMD module https://github.com/amdjs/amdjs-api/wiki/AMD to a
 * Common JS module. See {@link ProcessCommonJSModule} for follow up processing
 * step.
 */
class TransformAMDToCJSModule implements CompilerPass {

  @VisibleForTesting
  final static DiagnosticType UNSUPPORTED_DEFINE_SIGNATURE_ERROR =
      DiagnosticType.error(
          "UNSUPPORTED_DEFINE_SIGNATURE",
          "Only define(function() ...), define(OBJECT_LITERAL) and define("
              + "['dep', 'dep1'], function(d0, d2, [exports, module]) ...) forms "
              + "are currently supported.");
  final static DiagnosticType NON_TOP_LEVEL_STATEMENT_DEFINE_ERROR =
      DiagnosticType.error(
            "NON_TOP_LEVEL_STATEMENT_DEFINE",
            "The define function must be called as a top level statement.");


  private final AbstractCompiler compiler;

  TransformAMDToCJSModule(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, new TransformAMDModulesCallback());
  }

  private void unsupportedDefineError(String sourceName, Node n) {
    compiler.report(JSError.make(sourceName, n, UNSUPPORTED_DEFINE_SIGNATURE_ERROR));
  }

  /**
   * The modules "exports", "require" and "module" are virtual in terms of
   * existing implicitly in CJS.
   */
  private boolean isVirtualModuleName(String moduleName) {
    return "exports".equals(moduleName) || "require".equals(moduleName) ||
        "module".equals(moduleName);
  }

  /**
   * Rewrites calls to define which has to be in void context just below the
   * current script node.
   */
  private class TransformAMDModulesCallback extends AbstractPostOrderCallback {

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall() && n.getFirstChild() != null &&
          n.getFirstChild().isName() &&
          "define".equals(n.getFirstChild().getString())) {
        Scope.Var defineVar = t.getScope().getVar(n.getFirstChild().
            getString());
        if (defineVar != null && !defineVar.isGlobal()) {
          // Ignore non-global define.
          return;
        }
        if (!(parent.isExprResult() && parent.getParent().isScript())) {
          compiler.report(JSError.make(t.getSourceName(), n,
              NON_TOP_LEVEL_STATEMENT_DEFINE_ERROR));
          return;
        }
        Node script = parent.getParent();
        Node requiresNode = null;
        Node callback = null;
        int defineArity = n.getChildCount() - 1;
        if (defineArity == 0) {
          unsupportedDefineError(t.getSourceName(), n);
          return;
        } else if (defineArity == 1) {
          callback = n.getChildAtIndex(1);
          if (callback.isObjectLit()) {
            handleDefineObjectLiteral(parent, callback, script);
            return;
          }
        } else if (defineArity == 2) {
          requiresNode = n.getChildAtIndex(1);
          callback = n.getChildAtIndex(2);
        } else if (defineArity >= 3) {
          unsupportedDefineError(t.getSourceName(), n);
          return;
        }

        if (!callback.isFunction() ||
            (requiresNode != null && !requiresNode.isArrayLit())) {
          unsupportedDefineError(t.getSourceName(), n);
          return;
        }

        handleRequiresAndParamList(script, requiresNode, callback);

        Node callbackBlock = callback.getChildAtIndex(2);
        NodeTraversal.traverse(compiler, callbackBlock,
            new DefineCallbackReturnCallback());

        moveCallbackContentToTopLevel(parent, script, callbackBlock);
        compiler.reportCodeChange();
      }
    }

    /**
     * When define is called with an object literal, assign it to exports and
     * we're done.
     */
    private void handleDefineObjectLiteral(Node parent, Node onlyExport,
        Node script) {
      onlyExport.getParent().removeChild(onlyExport);
      script.replaceChild(parent,
          IR.exprResult(IR.assign(IR.name("exports"), onlyExport))
              .copyInformationFromForTree(onlyExport));
      compiler.reportCodeChange();
    }

    /**
     * Rewrites the required modules to
     * <code>var nameInParamList = require("nameFromRequireList");</code>
     */
    private void handleRequiresAndParamList(Node script, Node requiresNode,
        Node callback) {
      int i = 0;
      Node paramList = callback.getChildAtIndex(1);
      for (Node aliasNode : paramList.children()) {
        String moduleName = null;
        Node modNode = requiresNode != null &&
            requiresNode.getChildCount() > i ?
                requiresNode.getChildAtIndex(i) : null;

        if (modNode != null) {
          moduleName = modNode.getString();
        }
        if (aliasNode != null) {
          String aliasName = aliasNode.getString();

          if (isVirtualModuleName(moduleName)) {
            continue;
          }

          // TODO(malteubl) Handle ?,! in define better.
          if (moduleName != null && moduleName.indexOf('!') == -1) {
            Node call = IR.call(IR.name("require"), IR.string(moduleName));
            call.putBooleanProp(Node.FREE_CALL, true);
            script.addChildToFront(IR.var(IR.name(aliasName), call)
                .copyInformationFromForTree(aliasNode));
          } else {
            // ignore exports, require and module (because they are implicit
            // in CJS);
            if (isVirtualModuleName(aliasName)) {
              continue;
            }
            script.addChildToFront(IR.var(IR.name(aliasName))
                .copyInformationFromForTree(aliasNode));
          }
        }
        i++;
      }
    }

    /**
     * Moves the statements in the callback to be direct children of the
     * current script.
     */
    private void moveCallbackContentToTopLevel(Node parent, Node script,
        Node callbackBlock) {
      int curIndex = script.getIndexOfChild(parent);
      script.removeChild(parent);
      Node before = script.getChildAtIndex(curIndex);
      for (Node body : callbackBlock.children()) {
        body.getParent().removeChild(body);
        if (before != null) {
          script.addChildBefore(body, before);
        }
        script.addChildToBack(body);
      }
    }
  }

  /**
   * Rewrites the return statement of the callback to be an assingment to
   * module.exports.
   */
  private class DefineCallbackReturnCallback extends
      NodeTraversal.AbstractShallowStatementCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isReturn() && n.hasChildren()) {
        Node retVal = n.getFirstChild();
        n.removeChild(retVal);
        parent.replaceChild(n, IR.exprResult(
            IR.assign(
                IR.getprop(IR.name("module"), IR.string("exports"))
                    .copyInformationFromForTree(n), retVal)
                    .copyInformationFrom(n)).copyInformationFrom(n));
      }
    }
  }
}
