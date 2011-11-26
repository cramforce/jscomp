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
  
  TransformAMDToCJSModule(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, new TransformAMDModulesCallback());
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
        if (n.getChildCount() == 2) {
          callback = n.getChildAtIndex(1);
        }
        if (n.getChildCount() == 3) {
          requiresNode = n.getChildAtIndex(1);
          callback = n.getChildAtIndex(2);
        }
        int i = 0;
        Node paramList = callback.getChildAtIndex(1);
        Node context = parent.getParent(); // Removes EXPR_RESULT around define call;
        for (Node aliasNode : paramList.children()) {
          
          String moduleName = null;
          Node modNode = requiresNode.getChildAtIndex(i++);
          if (modNode != null) {
            moduleName = modNode.getString();
          }
          if (aliasNode != null) {
            String aliasName = aliasNode.getString();
            if (moduleName != null) {
              context.addChildToFront(
                  IR.var(IR.name(aliasName), 
                      IR.call(IR.name("require"), IR.string(moduleName))).copyInformationFromForTree(aliasNode));
            } else {
              context.addChildToFront(
                  IR.var(IR.name(aliasName)).copyInformationFromForTree(aliasNode));
            }
          }
        }
        
        Node callbackBlock = callback.getChildAtIndex(2);
        
        NodeTraversal.traverse(compiler, callbackBlock, new NodeTraversal.AbstractShallowCallback() {
          @Override
          public void visit(NodeTraversal t, Node n, Node parent) {
            if (n.isReturn()) {
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
