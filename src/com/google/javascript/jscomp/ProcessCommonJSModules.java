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

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

/**
 * Rewrites a Common JS module http://wiki.commonjs.org/wiki/Modules/1.1.1
 * into a form that can be safely concatenated.
 * Does not add a function around the module body but instead adds suffixes
 * to global variables to avoid conflicts.
 * Calls to require are changed to reference the required module directly.
 * goog.provide and goog.require are emitted for closure compiler automatic
 * ordering.
 */
class ProcessCommonJSModules implements CompilerPass {

  private static final String MODULE_NAME_PREFIX = "module$";

  private final AbstractCompiler compiler;
  private final String filenamePrefix;
  private final boolean reportDependencies;
  private JSModule module;

  ProcessCommonJSModules(AbstractCompiler compiler, String filenamePrefix) {
    this.compiler = compiler;
    this.filenamePrefix = filenamePrefix;
    this.reportDependencies = true;
  }

  ProcessCommonJSModules(AbstractCompiler compiler, String filenamePrefix,
      boolean reportDependencies) {
    this.compiler = compiler;
    this.filenamePrefix = filenamePrefix;
    this.reportDependencies = reportDependencies;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal
        .traverse(compiler, root, new ProcessCommonJsModulesCallback());
  }

  private String guessCJSModuleName(String filename) {
    return toModuleName(normalizeSourceName(filename));
  }

  public JSModule getModule() {
    return module;
  }

  /**
   * Turns a filename into a JS identifier that is used for moduleNames in
   * rewritten code. Removes leading ./, replaces / with $, removes trailing .js
   * and replaces - with _. All moduleNames get a "module$" prefix.
   */
  public static String toModuleName(String filename) {
    return MODULE_NAME_PREFIX +
        filename.replaceAll("^\\.\\/", "").replaceAll("\\/", "\\$")
            .replaceAll("\\.js$", "").replaceAll("-", "_");
  }

  /**
   * Turn a filename into a moduleName with support for relative addressing
   * with ./ and ../ based on currentFilename;
   */
  public static String toModuleName(String filename, String currentFilename) {
    filename = filename.replaceAll("\\.js$", "");
    currentFilename = currentFilename.replaceAll("\\.js$", "");

    // More elegant algorithms welcome but it is late.
    boolean relative = false;
    List<String> parts = Lists.newArrayList(currentFilename.split("/"));
    if (filename.startsWith("./")) {
      filename = filename.substring(2);
      parts.remove(parts.size() - 1);
      relative = true;
    }
    else if (filename.startsWith("../")) {
      parts.remove(parts.size() - 1);
      while (filename.startsWith("../")) {
        filename = filename.substring(3);
        parts.remove(parts.size() - 1);
      }
      relative = true;
    }
    if (relative) {
      parts.add(filename);
      filename = Joiner.on("/").join(parts);
    }
    return toModuleName(filename);
  }

  private String normalizeSourceName(String filename) {
    if (filename.indexOf(filenamePrefix) == 0) {
      filename = filename.substring(filenamePrefix.length());
    }
    return filename;
  }

  /**
   * Visits require, every "script" and special module.exports assignments.
   */
  private class ProcessCommonJsModulesCallback extends
      AbstractPostOrderCallback {

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall() && n.getFirstChild() != null && n.getChildCount() == 2 &&
          n.getFirstChild().isName() &&
          "require".equals(n.getFirstChild().getString()) &&
          n.getChildAtIndex(1).isString()) {
        visitRequireCall(t, n, parent);
      }

      if (n.isScript()) {
        visitScript(n);
      }

      if (n.isGetProp() && n.getChildAtIndex(0).isName() &&
          "module".equals(n.getChildAtIndex(0).getString()) &&
          n.getChildAtIndex(1).isString() &&
          "exports".equals(n.getChildAtIndex(1).getString())) {
        visitModuleExports(n);
      }
    }

    /**
     * Visit require calls. Emit corresponding goog.require and rewrite require
     * to be a direct reference to name of require module.
     */
    private void visitRequireCall(NodeTraversal t, Node require, Node parent) {
      String moduleName = toModuleName(require.getChildAtIndex(1).getString(),
          normalizeSourceName(t.getSourceName()));
      Node moduleRef = IR.name(moduleName).srcref(require);
      parent.replaceChild(require, moduleRef);
      Node script = getCurrentScriptNode(parent);
      if (reportDependencies) {
        compiler.getInput(script.getInputId()).addRequire(moduleName);
      }
      // Rewrite require("name").
      script.addChildToFront(IR.exprResult(
          IR.call(IR.getprop(IR.name("goog"), IR.string("require")),
              IR.string(moduleName))).copyInformationFromForTree(require));
      compiler.reportCodeChange();
    }

    /**
     * Emit goog.provide and add suffix to all global vars to avoid conflicts
     * with other modules.
     *
     * @param script
     */
    private void visitScript(Node script) {
      String moduleName = guessCJSModuleName(normalizeSourceName(script
          .getSourceFileName()));
      script.addChildToFront(IR.var(IR.name(moduleName), IR.objectlit())
          .copyInformationFromForTree(script));
      // System.out.println("Provide " + moduleName);
      if (reportDependencies) {
        CompilerInput ci = compiler.getInput(script.getInputId());
        ci.addProvide(moduleName);
        JSModule m = new JSModule(moduleName);
        m.add(ci);
        module = m;
      }
      script.addChildToFront(IR.exprResult(
          IR.call(IR.getprop(IR.name("goog"), IR.string("provide")),
              IR.string(moduleName))).copyInformationFromForTree(script));

      emitOptionalModuleExportsOverride(script, moduleName);

      // Rename vars to not conflict in global scope.
      NodeTraversal.traverse(compiler, script, new SuffixVarsCallback(
          moduleName));

      compiler.reportCodeChange();
    }

    /**
     * Emit <code>if (moduleName.module$exports) {
     *    moduleName = moduleName.module$export;
     * }</code> at end of file.
     */
    private void emitOptionalModuleExportsOverride(Node script,
        String moduleName) {
      Node moduleExportsProp = IR.getprop(IR.name(moduleName),
          IR.string("module$exports"));
      script.addChildToBack(IR.ifNode(
          moduleExportsProp,
          IR.block(IR.exprResult(IR.assign(IR.name(moduleName),
              moduleExportsProp.cloneTree())))).copyInformationFromForTree(
          script));
    }

    /**
     * Rewrite module.exports to moduleName.module$exports.
     */
    private void visitModuleExports(Node prop) {
      String moduleName = guessCJSModuleName(prop.getSourceFileName());
      prop.getChildAtIndex(0).setString(moduleName);
      prop.getChildAtIndex(1).setString("module$exports");
    }

    /**
     * Returns next script node in parents.
     */
    private Node getCurrentScriptNode(Node n) {
      while (true) {
        if (n.isScript()) {
          return n;
        }
        n = n.getParent();
      }
    }
  }

  /**
   * Traverses a node tree and appends a suffix to all global variable names.
   */
  private class SuffixVarsCallback extends AbstractPostOrderCallback {

    private final String suffix;

    public SuffixVarsCallback(String suffix) {
      this.suffix = suffix;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName()) {
        String name = n.getString();
        if (suffix.equals(name)) {
          return;
        }
        if ("exports".equals(name)) {
          n.setString(suffix);
        } else {
          Scope.Var var = t.getScope().getVar(name);
          if (var != null && var.isGlobal()) {
            n.setString(name + "$$" + suffix);
          }
        }
      }
    }
  }
}
