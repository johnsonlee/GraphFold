package io.johnsonlee.graphfold.compiler.javac;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic;

/** Test adapter which preserves real javac attribution except at a deliberately corrupted boundary. */
abstract class ForwardingTrees extends Trees {
    protected final Trees delegate;
    ForwardingTrees(Trees delegate) { this.delegate = delegate; }
    public SourcePositions getSourcePositions() { return delegate.getSourcePositions(); }
    public Tree getTree(Element element) { return delegate.getTree(element); }
    public ClassTree getTree(TypeElement element) { return delegate.getTree(element); }
    public MethodTree getTree(ExecutableElement element) { return delegate.getTree(element); }
    public Tree getTree(Element element, AnnotationMirror annotation) { return delegate.getTree(element, annotation); }
    public Tree getTree(Element element, AnnotationMirror annotation, AnnotationValue value) {
        return delegate.getTree(element, annotation, value);
    }
    public TreePath getPath(CompilationUnitTree unit, Tree tree) { return delegate.getPath(unit, tree); }
    public TreePath getPath(Element element) { return delegate.getPath(element); }
    public TreePath getPath(Element element, AnnotationMirror annotation) { return delegate.getPath(element, annotation); }
    public TreePath getPath(Element element, AnnotationMirror annotation, AnnotationValue value) {
        return delegate.getPath(element, annotation, value);
    }
    public Element getElement(TreePath path) { return delegate.getElement(path); }
    public TypeMirror getTypeMirror(TreePath path) { return delegate.getTypeMirror(path); }
    public Scope getScope(TreePath path) { return delegate.getScope(path); }
    public String getDocComment(TreePath path) { return delegate.getDocComment(path); }
    public boolean isAccessible(Scope scope, TypeElement type) { return delegate.isAccessible(scope, type); }
    public boolean isAccessible(Scope scope, Element member, DeclaredType type) { return delegate.isAccessible(scope, member, type); }
    public TypeMirror getOriginalType(ErrorType type) { return delegate.getOriginalType(type); }
    public void printMessage(Diagnostic.Kind kind, CharSequence message, Tree tree, CompilationUnitTree root) {
        delegate.printMessage(kind, message, tree, root);
    }
    public TypeMirror getLub(CatchTree tree) { return delegate.getLub(tree); }
}
