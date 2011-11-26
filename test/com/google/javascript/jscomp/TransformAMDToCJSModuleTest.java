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

/**
 * Unit tests for {@link TransformAMDToCJSModules}
 *
 */
public class TransformAMDToCJSModuleTest extends CompilerTestCase {

  public TransformAMDToCJSModuleTest() {
    super("", false);
  }

  @Override protected CompilerPass getProcessor(Compiler compiler) {
    return new TransformAMDToCJSModule(compiler);
  }

  @Override
  protected int getNumRepetitions() {
    return 1;
  }

  public void testRewrite() {
    test("define(['foo', 'bar'], function(foo, bar) { foo(bar); bar+1; })", "var bar=require(\"bar\");var foo=require(\"foo\");foo(bar);bar+1");
    test("define(['foo', 'bar'], function(foo, bar, baz) { foo(bar); bar+1; })", "var baz;var bar=require(\"bar\");var foo=require(\"foo\");foo(bar);bar+1");
    test("define(['foo', 'bar'], function(foo, bar) { return { test: 1 } })", "var bar=require(\"bar\");var foo=require(\"foo\");module.exports={test:1}");
  }
  
}
