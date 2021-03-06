/*
 * Copyright 2018 The Closure Compiler Authors.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.javascript.jscomp.ProcessClosurePrimitives.INVALID_FORWARD_DECLARE;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.javascript.jscomp.ModuleMetadataMap.ModuleMetadata;
import com.google.javascript.jscomp.ModuleMetadataMap.ModuleType;
import com.google.javascript.jscomp.NodeTraversal.AbstractModuleCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * Rewrites all goog.require, goog.module.get, goog.forwardDeclare, and goog.requireType calls for
 * goog.provide, goog.module, and ES6 modules (that call goog.declareModuleId).
 */
final class RewriteClosureImports implements HotSwapCompilerPass {

  private static final Node GOOG_REQUIRE = IR.getprop(IR.name("goog"), IR.string("require"));
  private static final Node GOOG_MODULE_GET =
      IR.getprop(IR.getprop(IR.name("goog"), IR.string("module")), IR.string("get"));
  private static final Node GOOG_FORWARD_DECLARE =
      IR.getprop(IR.name("goog"), IR.string("forwardDeclare"));
  private static final Node GOOG_REQUIRE_TYPE =
      IR.getprop(IR.name("goog"), IR.string("requireType"));

  private final AbstractCompiler compiler;
  @Nullable private final PreprocessorSymbolTable preprocessorSymbolTable;
  private final ImmutableSet<ModuleType> typesToRewriteIn;
  private final Rewriter rewriter;

  /**
   * @param typesToRewriteIn A temporary set of module types to rewrite in. As this pass replaces
   *     the logic inside ES6RewriteModules, ClosureRewriteModules, and ProcessClosurePrimitives,
   *     this set should be expanded. Once it covers all module types it should be removed. This is
   *     how we will slowly and safely consolidate all logic to this pass. e.g. if the set is empty
   *     then this pass does nothing.
   */
  RewriteClosureImports(
      AbstractCompiler compiler,
      ModuleMetadataMap moduleMetadataMap,
      @Nullable PreprocessorSymbolTable preprocessorSymbolTable,
      ImmutableSet<ModuleType> typesToRewriteIn) {
    this.compiler = compiler;
    this.preprocessorSymbolTable = preprocessorSymbolTable;
    this.typesToRewriteIn = typesToRewriteIn;
    this.rewriter = new Rewriter(compiler, moduleMetadataMap);
  }

  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    if (typesToRewriteIn.isEmpty()) {
      return;
    }

    NodeTraversal.traverse(compiler, scriptRoot, rewriter);
  }

  @Override
  public void process(Node externs, Node root) {
    if (typesToRewriteIn.isEmpty()) {
      return;
    }

    NodeTraversal.traverse(compiler, externs, rewriter);
    NodeTraversal.traverse(compiler, root, rewriter);
  }

  private class Rewriter extends AbstractModuleCallback {
    // Requires for Closure files need to be inlined if aliased. This behavior isn't really correct,
    // but is required as some Closure symbols need to be recognized by the compiler, like
    // goog.asserts. e.g.:
    //
    // const {assert} = goog.require('goog.asserts');
    // assert(false);
    //
    // Needs to be written to:
    //
    // goog.asserts.assert(false);
    //
    // As other stages of the compiler look for "goog.asserts.assert".
    // However we don't do this for ES modules due to mutable exports.
    // Note that doing this for types also allows type references to non-imported non-legacy
    // goog.module files - which we really shouldn't have allowed, but people are now relying on it.
    private final Map<String, String> variablesToInline = new HashMap<>();
    private final Map<String, String> typesToInline = new HashMap<>();
    private final ModuleMetadataMap moduleMetadataMap;

    Rewriter(AbstractCompiler compiler, ModuleMetadataMap moduleMetadataMap) {
      super(compiler, moduleMetadataMap);
      this.moduleMetadataMap = moduleMetadataMap;
    }

    @Override
    protected void exitModule(ModuleMetadata oldModule, Node moduleScopeRoot) {
      variablesToInline.clear();
      typesToInline.clear();
    }

    @Override
    public void visit(
        NodeTraversal t,
        Node n,
        @Nullable ModuleMetadata currentModule,
        @Nullable Node moduleScopeRoot) {
      // currentModule is null on ROOT nodes.
      if (currentModule == null || !typesToRewriteIn.contains(currentModule.moduleType())) {
        return;
      }

      Node parent = n.getParent();

      switch (n.getToken()) {
        case CALL:
          if (n.getFirstChild().matchesQualifiedName(GOOG_REQUIRE)
              || n.getFirstChild().matchesQualifiedName(GOOG_REQUIRE_TYPE)) {
            visitRequire(n, parent, currentModule);
          } else if (n.getFirstChild().matchesQualifiedName(GOOG_FORWARD_DECLARE)) {
            visitForwardDeclare(t, n, parent, currentModule);
          } else if (n.getFirstChild().matchesQualifiedName(GOOG_MODULE_GET)) {
            visitGoogModuleGet(t, n);
          }
          break;
        case NAME:
          maybeInlineName(t, n);
          break;
        default:
          break;
      }

      if (n.getJSDocInfo() != null) {
        rewriteJsdoc(n.getJSDocInfo(), currentModule);
      }
    }

    private void rewriteImport(
        ModuleMetadata currentModule,
        ModuleMetadata requiredModule,
        String requiredNamespace,
        Node callNode,
        Node parentNode) {
      Node callee = callNode.getFirstChild();
      maybeAddToSymbolTable(callee);

      Node statementNode = NodeUtil.getEnclosingStatement(callNode);
      Node changeScope = NodeUtil.getEnclosingChangeScopeRoot(statementNode);
      String globalName = ModuleRenaming.getGlobalName(requiredModule, requiredNamespace);

      if (parentNode.isExprResult()) {
        // goog.require('something');
        if (requiredModule.isNonLegacyGoogModule() || requiredModule.isEs6Module()) {
          // Fully qualified module namespaces can be referenced in type annotations.
          // Legacy modules and provides don't need rewriting since their references will be the
          // same after rewriting.
          typesToInline.put(requiredNamespace, globalName);
        }

        statementNode.detach();
        maybeAddToSymbolTable(callNode.getLastChild(), globalName);
      } else if (requiredModule.isEs6Module()) {
        // const alias = goog.require('es6');
        // const {alias} = goog.require('es6');

        // ES6 stored in a const or destructured - keep the variables and just replace the call.

        // const alias = module$es6;
        // const {alias} = module$es6;
        checkState(NodeUtil.isNameDeclaration(parentNode.getParent()));
        callNode.replaceWith(NodeUtil.newQName(compiler, globalName).srcrefTree(callNode));
        maybeAddToSymbolTable(callNode.getLastChild(), globalName);
      } else if (parentNode.isName()) {
        // const alias = goog.require('closure');
        // use(alias);

        // Closure file stored in a var or const without destructuring. Inline the variable.

        // use(closure-global-name);
        variablesToInline.put(parentNode.getString(), globalName);
        typesToInline.put(parentNode.getString(), globalName);
        statementNode.detach();

        maybeAddAliasToSymbolTable(statementNode.getFirstChild(), currentModule);
      } else {
        // const {alias} = goog.require('closure');
        // use(alias);

        // Closure file stored in a var or const with destructuring. Inline the variables.

        // use(closure-global-name.alias);
        checkState(parentNode.isDestructuringLhs());

        for (Node importSpec : parentNode.getFirstChild().children()) {
          checkState(importSpec.hasChildren(), importSpec);
          String importedProperty = importSpec.getString();
          Node aliasNode = importSpec.getFirstChild();
          String aliasName = aliasNode.getString();
          String fullName = globalName + "." + importedProperty;
          variablesToInline.put(aliasName, fullName);
          typesToInline.put(aliasName, fullName);

          maybeAddAliasToSymbolTable(aliasNode, currentModule);
        }

        statementNode.detach();
      }

      compiler.reportChangeToChangeScope(changeScope);
    }

    private void visitRequire(Node callNode, Node parentNode, ModuleMetadata currentModule) {
      String requiredNamespace = callNode.getLastChild().getString();
      ModuleMetadata requiredModule =
          moduleMetadataMap.getModulesByGoogNamespace().get(requiredNamespace);
      rewriteImport(currentModule, requiredModule, requiredNamespace, callNode, parentNode);
    }

    private void rewriteGoogModuleGet(
        ModuleMetadata requiredModule, String requiredNamespace, NodeTraversal t, Node callNode) {
      Node maybeAssign = callNode.getParent();
      boolean isFillingAnAlias = maybeAssign.isAssign() && maybeAssign.getParent().isExprResult();

      if (isFillingAnAlias) {
        // Only valid to assign if the original declaration was a forward declare. Verified in the
        // CheckClosureImports pass. We can just detach this node as we will replace the
        // goog.forwardDeclare.
        // let x = goog.forwardDeclare('x');
        // x = goog.module.get('x');
        maybeAssign.getParent().detach();
      } else {
        callNode.replaceWith(
            NodeUtil.newQName(
                    compiler, ModuleRenaming.getGlobalName(requiredModule, requiredNamespace))
                .srcrefTree(callNode));
      }

      t.reportCodeChange();
    }

    private void visitGoogModuleGet(NodeTraversal t, Node callNode) {
      String requiredNamespace = callNode.getLastChild().getString();
      ModuleMetadata requiredModule =
          moduleMetadataMap.getModulesByGoogNamespace().get(requiredNamespace);
      rewriteGoogModuleGet(requiredModule, requiredNamespace, t, callNode);
    }

    /** Process a goog.forwardDeclare() call and record the specified forward declaration. */
    private void visitForwardDeclare(
        NodeTraversal t, Node n, Node parent, ModuleMetadata currentModule) {
      CodingConvention convention = compiler.getCodingConvention();

      String typeDeclaration;
      try {
        typeDeclaration = Iterables.getOnlyElement(convention.identifyTypeDeclarationCall(n));
      } catch (NullPointerException | NoSuchElementException | IllegalArgumentException e) {
        compiler.report(
            t.makeError(
                n,
                INVALID_FORWARD_DECLARE,
                "A single type could not identified for the goog.forwardDeclare statement"));
        return;
      }

      if (typeDeclaration != null) {
        compiler.forwardDeclareType(typeDeclaration);
      }

      String requiredNamespace = n.getLastChild().getString();
      ModuleMetadata requiredModule =
          moduleMetadataMap.getModulesByGoogNamespace().get(requiredNamespace);

      // Assume anything not provided is a global, and that this isn't a module (we've checked this
      // in the checks pass).
      if (requiredModule == null) {
        checkState(parent.isExprResult());
        parent.detach();
        t.reportCodeChange();
      } else {
        rewriteImport(currentModule, requiredModule, requiredNamespace, n, parent);
      }
    }

    private void maybeInlineName(NodeTraversal t, Node n) {
      if (variablesToInline.isEmpty()) {
        return;
      }

      Var var = t.getScope().getVar(n.getString());

      if (var != null) {
        return;
      }

      String newName = variablesToInline.get(n.getString());

      if (newName == null) {
        return;
      }

      if (!newName.contains(".")) {
        safeSetString(n, newName);
      } else {
        n.replaceWith(NodeUtil.newQName(compiler, newName).srcrefTree(n));
      }
    }

    private void safeSetString(Node n, String newString) {
      if (n.getString().equals(newString)) {
        return;
      }

      String originalName = n.getString();
      n.setString(newString);
      if (n.getOriginalName() == null) {
        n.setOriginalName(originalName);
      }
      // TODO(blickly): It would be better not to be renaming detached nodes
      Node changeScope = NodeUtil.getEnclosingChangeScopeRoot(n);
      if (changeScope != null) {
        compiler.reportChangeToChangeScope(changeScope);
      }
    }

    private void rewriteJsdoc(JSDocInfo info, ModuleMetadata currentModule) {
      if (typesToInline.isEmpty()) {
        return;
      }

      JsDocRefReplacer replacer = new JsDocRefReplacer(currentModule);

      for (Node typeNode : info.getTypeNodes()) {
        NodeUtil.visitPreOrder(typeNode, replacer);
      }
    }

    private final class JsDocRefReplacer implements NodeUtil.Visitor {
      private final ModuleMetadata currentModule;

      JsDocRefReplacer(ModuleMetadata currentModule) {
        this.currentModule = currentModule;
      }

      @Override
      public void visit(Node typeRefNode) {
        if (!typeRefNode.isString()) {
          return;
        }

        // A type name that might be simple like "Foo" or qualified like "foo.Bar".
        String typeName = typeRefNode.getString();

        // Tries to rename progressively shorter type prefixes like "foo.Bar.Baz", "foo.Bar",
        // "foo".
        String prefixTypeName = typeName;
        String suffix = "";
        do {
          // If the name is an alias for an imported namespace rewrite from
          // "{Foo}" to
          // "{module$exports$bar$Foo}" or
          // "{bar.Foo}"
          String aliasedNamespace = typesToInline.get(prefixTypeName);
          if (aliasedNamespace != null) {
            if (preprocessorSymbolTable != null) {
              // Jsdoc type node is a single STRING node that spans the whole type. For example
              // STRING node "bar.Foo". When rewriting modules potentially replace only "module"
              // part of the type: "bar.Foo" => "module$exports$bar$Foo". So we need to remember
              // that "bar" as alias. To do that we clone type node and make "bar" node from  it.
              Node moduleOnlyNode = typeRefNode.cloneNode();
              safeSetString(moduleOnlyNode, prefixTypeName);
              moduleOnlyNode.setLength(prefixTypeName.length());
              maybeAddAliasToSymbolTable(moduleOnlyNode, currentModule);
            }

            safeSetString(typeRefNode, aliasedNamespace + suffix);
            return;
          }

          if (prefixTypeName.contains(".")) {
            prefixTypeName = prefixTypeName.substring(0, prefixTypeName.lastIndexOf('.'));
            suffix = typeName.substring(prefixTypeName.length());
          } else {
            return;
          }
        } while (true);
      }
    }

    /** Add the given qualified name node to the symbol table. */
    private void maybeAddToSymbolTable(Node n) {
      if (preprocessorSymbolTable != null) {
        preprocessorSymbolTable.addReference(n);
      }
    }

    private void maybeAddToSymbolTable(Node n, String name) {
      if (preprocessorSymbolTable != null) {
        preprocessorSymbolTable.addReference(n, name);
      }
    }

    /**
     * Add alias nodes to the symbol table as they going to be removed by rewriter. Example aliases:
     *
     * <pre>
     * const Foo = goog.require('my.project.Foo');
     * const bar = goog.require('my.project.baz');
     * const {baz} = goog.require('my.project.utils');
     * </pre>
     */
    private void maybeAddAliasToSymbolTable(Node n, ModuleMetadata currentModule) {
      if (preprocessorSymbolTable != null) {
        n.putBooleanProp(Node.MODULE_ALIAS, true);
        // Alias can be used in js types. Types have node type STRING and not NAME so we have to
        // use their name as string.
        String nodeName =
            n.isString() ? n.getString() : preprocessorSymbolTable.getQualifiedName(n);

        // We need to include module as part of the name because aliases are local to current
        // module. Aliases with the same name from different module should be completely different
        // entities.
        String module =
            ModuleRenaming.getGlobalName(
                currentModule, Iterables.getFirst(currentModule.googNamespaces(), null));

        String name = "alias_" + module + "_" + nodeName;
        maybeAddToSymbolTable(n, name);
      }
    }
  }
}
