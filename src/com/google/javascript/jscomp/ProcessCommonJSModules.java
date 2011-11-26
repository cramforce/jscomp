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

import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;

/**
 *
 */
class ProcessCommonJSModules implements CompilerPass {

  private final AbstractCompiler compiler;
  private final String filenamePrefix;
  
  ProcessCommonJSModules(AbstractCompiler compiler, String filenamePrefix) {
    this.compiler = compiler;
    this.filenamePrefix = filenamePrefix;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, new ProcessCommonJsModulesCallback());
  }
  
  private String guessCJSModuleName(String filename) {
    if (filename.indexOf(filenamePrefix) == 0) {
      filename = filename.substring(filenamePrefix.length());
    }
    return toModuleName(filename);
  }
  
  private String toModuleName(String filename) {
    return "module$" + filename.replaceAll("\\/", "\\$").replace("\\.js$", "");
  }

  /**
   */
  private class ProcessCommonJsModulesCallback
      extends AbstractPostOrderCallback {
    
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall() && n.getFirstChild() != null && 
          n.getFirstChild().isName() && "require".equals(n.getFirstChild().getString())) {
        String moduleName = toModuleName(n.getChildAtIndex(1).getString());
        Node moduleRef = IR.name(moduleName).srcref(n);
        parent.replaceChild(n, moduleRef);
        Node script = getCurrentScriptNode(parent);
        script.addChildToFront(IR.exprResult(
            IR.call(IR.getprop(IR.name("goog"), IR.string("require")), IR.string(moduleName))
        ).copyInformationFromForTree(n));
        
        if (t.getScope().isGlobal()) {
          String alias = parent.getString();
          String suffix = guessCJSModuleName(script.getSourceFileName());
          NodeTraversal.traverse(compiler, script, new RenameVarsCallback(alias, alias + "$$" + suffix));
        }
        compiler.reportCodeChange();
      }
      
      if (n.isScript()) {
        String moduleName = guessCJSModuleName(n.getSourceFileName());
        n.addChildToFront(IR.var(IR.name(moduleName), IR.objectlit()).copyInformationFromForTree(n));
        NodeTraversal.traverse(compiler, n, new RenameVarsCallback("exports", moduleName));
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
        compiler.reportCodeChange();
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
  
  private class RenameVarsCallback
      extends AbstractPostOrderCallback {
    
    private final String from;
    private final String to;
    
    public RenameVarsCallback(String from, String to) {
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
