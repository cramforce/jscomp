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
  final static DiagnosticType REQUIREJS_PLUGINS_NOT_SUPPORTED_WARNING =
    DiagnosticType.warning(
          "REQUIREJS_PLUGINS_NOT_SUPPORTED",
          "Plugins in define requirements are not supported: {0}");

  final static String VAR_RENAME_SUFFIX = "__alias";


  private final AbstractCompiler compiler;
  private int renameIndex = 0;

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

        handleRequiresAndParamList(t, n, script, requiresNode, callback);

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
    private void handleRequiresAndParamList(NodeTraversal t, Node defineNode,
        Node script, Node requiresNode, Node callback) {
      int i = 0;
      Node paramList = callback.getChildAtIndex(1);
      // Iterate over the longer list.
      if (requiresNode == null ||
          paramList.getChildCount() >= requiresNode.getChildCount()) {
        for (Node aliasNode : paramList.children()) {
          Node modNode = requiresNode != null &&
              requiresNode.getChildCount() > i ?
                  requiresNode.getChildAtIndex(i) : null;

          handleRequire(t, defineNode, script, callback, aliasNode, modNode);
          i++;
        }
      } else {
        for (Node modNode : requiresNode.children()) {
          Node aliasNode = paramList.getChildCount() > i ?
              paramList.getChildAtIndex(i) : null;
          handleRequire(t, defineNode, script, callback, aliasNode, modNode);
          i++;
        }
      }
    }

    /**
     * Rewrite a single require call.
     */
    private void handleRequire(NodeTraversal t, Node defineNode, Node script,
        Node callback, Node aliasNode, Node modNode) {
      String moduleName = null;
      if (modNode != null) {
        moduleName = modNode.getString();
      }
      String aliasName = aliasNode != null ? aliasNode.getString() : null;

      if (isVirtualModuleName(moduleName)) {
        return;
      }

      Scope scope = t.getScope().getGlobalScope();
      while (aliasName != null &&
          scope.isDeclared(aliasName, true)) {
        String renamed = aliasName + VAR_RENAME_SUFFIX + renameIndex;
        if (!scope.isDeclared(renamed, true)) {
          NodeTraversal.traverse(compiler, callback, new RenameCallback(aliasName,
              renamed));
          aliasName = renamed;
          break;
        }
        renameIndex++;
      }

      moduleName = handlePlugins(script, moduleName, modNode);

      Node requireNode;

      if (moduleName != null) {
        Node call = IR.call(IR.name("require"), IR.string(moduleName));
        call.putBooleanProp(Node.FREE_CALL, true);
        if (aliasName != null) {
          requireNode = IR.var(IR.name(aliasName), call)
              .copyInformationFromForTree(aliasNode);
        } else {
          requireNode = IR.exprResult(call).
              copyInformationFromForTree(modNode);
        }
      } else {
        // ignore exports, require and module (because they are implicit
        // in CJS);
        if (isVirtualModuleName(aliasName)) {
          return;
        }
        requireNode = IR.var(IR.name(aliasName), IR.nullNode())
            .copyInformationFromForTree(aliasNode);
      }

      script.addChildBefore(requireNode,
          defineNode.getParent());
    }

    /**
     * Require.js supports a range of plugins that are hard to support
     * statically. Generally none are supported right now with the
     * exception of a simple hack to support condition loading. This
     * was added to make compilation of Dojo work better but will
     * probably break, so just don't use them :)
     */
    private String handlePlugins(Node script, String moduleName, Node modNode) {
      if (moduleName != null && moduleName.indexOf('!') != -1) {
        compiler.report(JSError.make(script.getSourceFileName(), modNode,
            REQUIREJS_PLUGINS_NOT_SUPPORTED_WARNING, moduleName));
        int condition = moduleName.indexOf('?');
        if (condition > 0) {
          if (moduleName.indexOf(':') != -1) {
            return null;
          }
          return handlePlugins(script, moduleName.substring(condition + 1), modNode);
        }
        moduleName = null;
      }
      return moduleName;
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

  /**
   * Renames names;
   */
  private class RenameCallback extends AbstractPostOrderCallback {

    private final String from;
    private final String to;

    public RenameCallback(String from, String to) {
      this.from = from;
      this.to = to;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName() && from.equals(n.getString())) {
        n.setString(to);
      }
    }
  }
}
