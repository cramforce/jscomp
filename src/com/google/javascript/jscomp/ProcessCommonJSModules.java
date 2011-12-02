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

import java.io.File;
import java.util.List;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.deps.SortedDependencies;
import com.google.javascript.jscomp.deps.SortedDependencies.CircularDependencyException;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.InputId;
import com.google.javascript.rhino.Node;

/**
 *
 */
class ProcessCommonJSModules implements CompilerPass {

  private final AbstractCompiler compiler;
  private final String filenamePrefix;
  private final boolean reportDependencies;
  private List<CompilerInput> inputs;
  private JSModule module;

  ProcessCommonJSModules(AbstractCompiler compiler, String filenamePrefix, List<CompilerInput> inputs) {
    this.compiler = compiler;
    this.filenamePrefix = filenamePrefix;
    this.reportDependencies = true;
    this.inputs = inputs;
  }

  ProcessCommonJSModules(AbstractCompiler compiler, String filenamePrefix, boolean reportDependencies) {
    this.compiler = compiler;
    this.filenamePrefix = filenamePrefix;
    this.reportDependencies = reportDependencies;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, new ProcessCommonJsModulesCallback());
  }

  private String guessCJSModuleName(String filename) {
    return toModuleName(normalizeSourceName(filename));
  }

  public boolean hasCyclicDependency() {
    try {
      new SortedDependencies(inputs);
    } catch (CircularDependencyException e) {
      return true;
    }
    return false;
  }

  public JSModule getModule() {
    return module;
  }

  public static String toModuleName(String filename) {
    return "module$" + filename.
        replaceAll("^\\.\\/", "").
        replaceAll("\\/", "\\$").
        replaceAll("\\.js$", "").
        replaceAll("-", "_");
  }

  public static String toModuleName(String filename, String currentFilename) {
    filename = filename.replaceAll("\\.js$", "");
    currentFilename = currentFilename.replaceAll("\\.js$", "");

    int dirsBack = 0;
    while (filename.startsWith("./")) {
      filename = filename.substring(2);
      dirsBack++;
    }
    if (filename.startsWith("../")) {
      dirsBack++;
    }
    while (filename.startsWith("../")) {
      filename = filename.substring(3);
      dirsBack++;
    }
    List<String> parts = Lists.newArrayList(currentFilename.split("/"));
    if (dirsBack > 0) {
      for (int i = 0; i < dirsBack; i++) {
        parts.remove(parts.size() - 1);
      }
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
   */
  private class ProcessCommonJsModulesCallback
      extends AbstractPostOrderCallback {

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall() && n.getFirstChild() != null &&
          n.getChildCount() == 2 &&
          n.getFirstChild().isName() && "require".equals(n.getFirstChild().getString()) &&
          n.getChildAtIndex(1).isString()) {
        String moduleName = toModuleName(n.getChildAtIndex(1).getString(), normalizeSourceName(t.getSourceName()));
        Node moduleRef = IR.name(moduleName).srcref(n);
        parent.replaceChild(n, moduleRef);
        Node script = getCurrentScriptNode(parent);
        //System.out.println("Require " + moduleName + " in " + guessCJSModuleName(t.getSourceName()));
        if (reportDependencies) {
          compiler.getInput(script.getInputId()).addRequire(moduleName);
          /*if (hasCyclicDependency()) {
            compiler.getInput(script.getInputId()).removeRequire(moduleName);
          }*/
        }
        script.addChildToFront(IR.exprResult(
            IR.call(IR.getprop(IR.name("goog"), IR.string("require")), IR.string(moduleName))
        ).copyInformationFromForTree(n));
        compiler.reportCodeChange();
      }

      if (n.isScript()) {
        String moduleName = guessCJSModuleName(normalizeSourceName(n.getSourceFileName()));
        n.addChildToFront(IR.var(IR.name(moduleName), IR.objectlit()).copyInformationFromForTree(n));
        // System.out.println("Provide " + moduleName);
        if (reportDependencies) {
          CompilerInput ci = compiler.getInput(n.getInputId());
          ci.addProvide(moduleName);
          JSModule m = new JSModule(moduleName);
          m.add(ci);
          module = m;
        }
        n.addChildToFront(IR.exprResult(
            IR.call(IR.getprop(IR.name("goog"), IR.string("provide")), IR.string(moduleName))
        ).copyInformationFromForTree(n));

        Node moduleExportsProp = IR.getprop(IR.name(moduleName), IR.string("module$exports"));
        n.addChildToBack(
            IR.ifNode(moduleExportsProp,
                IR.block(
                    IR.exprResult(
                        IR.assign(IR.name(moduleName), moduleExportsProp.cloneTree())))).
            copyInformationFromForTree(n)
        );

        // Rename vars to not conflict in global scope.
        NodeTraversal.traverse(compiler, n, new SuffixVarsCallback(moduleName));

        compiler.reportCodeChange();
      }

      if (n.isGetProp() &&
          n.getChildAtIndex(0).isName() &&
          "module".equals( n.getChildAtIndex(0).getString()) &&
          n.getChildAtIndex(1).isString() &&
          "exports".equals(n.getChildAtIndex(1).getString())) {
        String moduleName = guessCJSModuleName(n.getSourceFileName());
        n.getChildAtIndex(0).setString(moduleName);
        n.getChildAtIndex(1).setString("module$exports");
      }
    }

    Node getCurrentScriptNode(Node n) {
      while (true) {
        if (n.isScript()) {
          return n;
        }
        n = n.getParent();
      }
    }
  }

  private class SuffixVarsCallback
      extends AbstractPostOrderCallback {

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
