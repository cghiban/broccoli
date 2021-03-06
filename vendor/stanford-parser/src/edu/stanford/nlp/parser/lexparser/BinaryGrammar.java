package edu.stanford.nlp.parser.lexparser;

import edu.stanford.nlp.util.Numberer;
import edu.stanford.nlp.ling.Label;

import java.io.*;
import java.util.*;

/**
 * Maintains efficient indexing of binary grammar rules.
 *
 * @author Dan Klein
 * @author Christopher Manning (generified and optimized storage)
 */
public class BinaryGrammar implements Serializable, Iterable<BinaryRule> {

  private String stateSpace;
  private int numStates;
  private List<BinaryRule> allRules;

  private transient List<BinaryRule>[] rulesWithParent;
  private transient List<BinaryRule>[] rulesWithLC;
  private transient List<BinaryRule>[] rulesWithRC;
  private transient Set<BinaryRule>[] ruleSetWithLC;
  private transient Set<BinaryRule>[] ruleSetWithRC;
  private transient BinaryRule[][] splitRulesWithLC;
  private transient BinaryRule[][] splitRulesWithRC;
  //  private transient BinaryRule[][] splitRulesWithParent = null;
  private transient Map<BinaryRule,BinaryRule> ruleMap;
  // for super speed! (maybe)
  private transient boolean[] synthetic;


  public int numRules() {
    return allRules.size();
  }

  public List<BinaryRule> rules() {
    return allRules;
  }

  public String stateSpace() {
    return stateSpace;
  }

  public boolean isSynthetic(int state) {
    return synthetic[state];
  }

  // CDM TODO: It seems like we should now replace this with ArrayList.toArray!
  private static BinaryRule[] toBRArray(List<BinaryRule> list) {
    // Collections.sort(list, Rule.scoreComparator()); // didn't seem to help
    BinaryRule[] array = new BinaryRule[list.size()];
    for (int i = 0; i < array.length; i++) {
      array[i] = list.get(i);
    }
    return array;
  }

  /**
   * Populates the "splitRules" accessor lists using the existing rule lists.
   * If the state is synthetic, these lists contain all rules for the state.
   * If the state is NOT synthetic, these lists contain only the rules in
   * which both children are not synthetic.
   * <p>
   * <i>This method must be called before the grammar is
   * used, either after training or deserializing grammar.</i>
   */
  public void splitRules() {
    // first initialize the synthetic array
    Numberer stateNumberer = Numberer.getGlobalNumberer(stateSpace);
    synthetic = new boolean[numStates];
    for (int s = 0; s < numStates; s++) {
      try {
        //System.out.println(((String)stateNumberer.object(s))); // debugging
        if (stateNumberer.object(s) instanceof String) {
          synthetic[s] = (((String) stateNumberer.object(s)).charAt(0) == '@');
        } else {
          synthetic[s] = (((Label) stateNumberer.object(s)).value().charAt(0) == '@');
        }
      } catch (NullPointerException e) {
        synthetic[s] = true;
      }
    }

    splitRulesWithLC = new BinaryRule[numStates][];
    splitRulesWithRC = new BinaryRule[numStates][];
    //    splitRulesWithParent = new BinaryRule[numStates][];
    // rules accesed by their "synthetic" child or left child if none
    for (int state = 0; state < numStates; state++) {
      //      System.out.println("Splitting rules for state: " + stateNumberer.object(state));
      // check synthetic
      if (isSynthetic(state)) {
        splitRulesWithLC[state] = toBRArray(rulesWithLC[state]);
        splitRulesWithRC[state] = toBRArray(rulesWithRC[state]);
      } else {
        // if state is not synthetic, we add rule to splitRules only if both children are not synthetic
        // do left
        List<BinaryRule> ruleList = new ArrayList<BinaryRule>();
        for (BinaryRule br : rulesWithLC[state]) {
          if ( ! isSynthetic(br.rightChild)) {
            ruleList.add(br);
          }
        }
        splitRulesWithLC[state] = toBRArray(ruleList);
        // do right
        ruleList.clear();
        for (BinaryRule br : rulesWithRC[state]) {
          if ( ! isSynthetic(br.leftChild)) {
            ruleList.add(br);
          }
        }
        splitRulesWithRC[state] = toBRArray(ruleList);
      }
      // parent accessor
      //      splitRulesWithParent[state] = toBRArray(rulesWithParent[state]);
    }
  }

  public BinaryRule[] splitRulesWithLC(int state) {
    if (state >= splitRulesWithLC.length) {
      return new BinaryRule[0];
    }
    return splitRulesWithLC[state];
  }

  public BinaryRule[] splitRulesWithRC(int state) {
    if (state >= splitRulesWithRC.length) {
      return new BinaryRule[0];
    }
    return splitRulesWithRC[state];
  }

  //  public BinaryRule[] splitRulesWithParent(int state) {
  //    return splitRulesWithParent[state];
  //  }

  // the sensible version

  public double scoreRule(BinaryRule br) {
    BinaryRule rule = ruleMap.get(br);
    return (rule != null ? rule.score : Double.NEGATIVE_INFINITY);
  }

  public void addRule(BinaryRule br) {
    //    System.out.println("BG adding rule " + br);
    rulesWithParent[br.parent].add(br);
    rulesWithLC[br.leftChild].add(br);
    rulesWithRC[br.rightChild].add(br);
    ruleSetWithLC[br.leftChild].add(br);
    ruleSetWithRC[br.rightChild].add(br);
    allRules.add(br);
    ruleMap.put(br, br);
  }


  public Iterator<BinaryRule> iterator() {
    return allRules.iterator();
  }

  public Iterator<BinaryRule> ruleIteratorByParent(int state) {
    if (state >= rulesWithParent.length) {
      return Collections.<BinaryRule>emptyList().iterator();
    }
    return rulesWithParent[state].iterator();
  }

  public Iterator<BinaryRule> ruleIteratorByRightChild(int state) {
    if (state >= rulesWithRC.length) {
      return Collections.<BinaryRule>emptyList().iterator();
    }
    return rulesWithRC[state].iterator();
  }

  public Iterator<BinaryRule> ruleIteratorByLeftChild(int state) {
    if (state >= rulesWithLC.length) {
      return Collections.<BinaryRule>emptyList().iterator();
    }
    return rulesWithLC[state].iterator();
  }

  public List<BinaryRule> ruleListByParent(int state) {
    if (state >= rulesWithParent.length) {
      return Collections.<BinaryRule>emptyList();
    }
    return rulesWithParent[state];
  }

  public List<BinaryRule> ruleListByRightChild(int state) {
    if (state >= rulesWithRC.length) {
      return Collections.<BinaryRule>emptyList();
    }
    return rulesWithRC[state];
  }

  public List<BinaryRule> ruleListByLeftChild(int state) {
    if (state >= rulesWithRC.length) {
      return Collections.<BinaryRule>emptyList();
    }
    return rulesWithLC[state];
  }

  public Set<BinaryRule> ruleSetByRightChild(int state) {
    if (state >= ruleSetWithRC.length) {
      return Collections.<BinaryRule>emptySet();
    }
    return ruleSetWithRC[state];
  }

  public Set<BinaryRule> ruleSetByLeftChild(int state) {
    if (state >= ruleSetWithRC.length) {
      return Collections.<BinaryRule>emptySet();
    }
    return ruleSetWithLC[state];
  }


  private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
    stream.defaultReadObject();
    init();
    for (BinaryRule br : allRules) {
      rulesWithParent[br.parent].add(br);
      rulesWithLC[br.leftChild].add(br);
      rulesWithRC[br.rightChild].add(br);
      ruleMap.put(br, br);
    }
    //    splitRules(); // this can't happen now because Numberers aren't necessarily set
  }

  @SuppressWarnings("unchecked")
  private void init() {
    ruleMap = new HashMap<BinaryRule,BinaryRule>();
    rulesWithParent = new List[numStates];
    rulesWithLC = new List[numStates];
    rulesWithRC = new List[numStates];
    ruleSetWithLC = new Set[numStates];
    ruleSetWithRC = new Set[numStates];
    for (int s = 0; s < numStates; s++) {
      rulesWithParent[s] = new ArrayList<BinaryRule>();
      rulesWithLC[s] = new ArrayList<BinaryRule>();
      rulesWithRC[s] = new ArrayList<BinaryRule>();
      ruleSetWithLC[s] = new HashSet<BinaryRule>();
      ruleSetWithRC[s] = new HashSet<BinaryRule>();
    }
  }

  public BinaryGrammar(int numStates) {
    this(numStates, "states");
  }

  public BinaryGrammar(int numStates, String stateSpace) {
    this.stateSpace = stateSpace;
    this.numStates = numStates;
    allRules = new ArrayList<BinaryRule>();
    init();
  }

  /**
   * Populates data in this BinaryGrammar from the character stream
   * given by the Reader r.
   *
   * @param in Where input is read from
   * @throws IOException If format is bung
   */
  public void readData(BufferedReader in) throws IOException {
    //if (Test.verbose) System.err.println(">> readData");
    String line;
    int lineNum = 1;
    Numberer n = Numberer.getGlobalNumberer("states");
    line = in.readLine();
    while (line != null && line.length() > 0) {
      try {
        addRule(new BinaryRule(line, n));
      } catch (Exception e) {
        throw new IOException("Error on line " + lineNum);
      }
      lineNum++;
      line = in.readLine();
    }
    splitRules();
  }

  /**
   * Writes out data from this Object to the Writer w.
   *
   * @param w Where output is written
   * @throws IOException If data can't be written
   */
  public void writeData(Writer w) throws IOException {
    PrintWriter out = new PrintWriter(w);
    for (BinaryRule br : this) {
      out.println(br);
    }
    out.flush();
  }

  private static final long serialVersionUID = 1L;

} // end class BinaryGrammar

