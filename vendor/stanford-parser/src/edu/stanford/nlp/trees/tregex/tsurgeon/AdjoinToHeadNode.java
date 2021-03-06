package edu.stanford.nlp.trees.tregex.tsurgeon;

import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.tregex.TregexMatcher;

/** Adjoin in a tree (like in TAG), but retain the target of adjunction as the root of the auxiliary tree.
 * @author Roger Levy (rog@nlp.stanford.edu)
 */
class AdjoinToHeadNode extends TsurgeonPattern {


  AuxiliaryTree adjunctionTree;

  public AdjoinToHeadNode(AuxiliaryTree t, TsurgeonPattern p) {
    super("adjoin", new TsurgeonPattern[] {p});
    adjunctionTree = t;
  }

  public Tree evaluate(Tree t, TregexMatcher m) {
    Tree targetNode = children[0].evaluate(t,m);
    Tree parent = targetNode.parent(t);
    AuxiliaryTree ft = adjunctionTree.copy(this);
    ft.foot.setChildren(targetNode.getChildrenAsList());
    targetNode.setChildren(ft.tree.getChildrenAsList());
    return t;
  }

  public String toString() {
    return super.toString() + "<-" + adjunctionTree.toString();
  }




}
