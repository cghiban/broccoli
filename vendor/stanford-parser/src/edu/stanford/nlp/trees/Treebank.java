package edu.stanford.nlp.trees;

import edu.stanford.nlp.io.ExtensionFileFilter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.util.Sets;

import java.io.*;
import java.text.NumberFormat;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


/**
 * A <code>Treebank</code> object provides access to a corpus of examples with
 * given tree structures.
 * This class now implements the Collection interface. However, it may offer
 * less than the full power of the Collection interface: some Treebanks are
 * read only, and so may throw the UnsupportedOperationException.
 *
 * @author Christopher Manning
 * @author Roger Levy (added encoding variable and method)
 */
public abstract class Treebank extends AbstractCollection<Tree> {

  /**
   * Stores the <code>TreeReaderFactory</code> that will be used to
   * create a <code>TreeReader</code> to process a file of trees.
   */
  private TreeReaderFactory trf;

  /**
   * Stores the charset encoding of the Treebank on disk.
   */
  private String encoding = TreebankLanguagePack.DEFAULT_ENCODING;

  public static final String DEFAULT_TREE_FILE_SUFFIX = "mrg";

  /**
   * Create a new Treebank (using a LabeledScoredTreeReaderFactory).
   */
  public Treebank() {
    this(new LabeledScoredTreeReaderFactory());
  }


  /**
   * Create a new Treebank.
   *
   * @param trf the factory class to be called to create a new
   *            <code>TreeReader</code>
   */
  public Treebank(TreeReaderFactory trf) {
    this.trf = trf;
  }


  /**
   * Create a new Treebank.
   *
   * @param trf      the factory class to be called to create a new
   *                 <code>TreeReader</code>
   * @param encoding The charset encoding to use for treebank file decoding
   */
  public Treebank(TreeReaderFactory trf, String encoding) {
    this.trf = trf;
    this.encoding = encoding;
  }


  /**
   * Create a new Treebank.
   *
   * @param initialCapacity The initial size of the underlying Collection,
   *                        (if a Collection-based storage mechanism is being provided)
   */
  public Treebank(int initialCapacity) {
    this(initialCapacity, new LabeledScoredTreeReaderFactory());
  }


  /**
   * Create a new Treebank.
   *
   * @param initialCapacity The initial size of the underlying Collection,
   *                        (if a Collection-based storage mechanism is being provided)
   * @param trf             the factory class to be called to create a new
   *                        <code>TreeReader</code>
   */
  public Treebank(int initialCapacity, TreeReaderFactory trf) {
    this.trf = trf;
  }


  /**
   * Get the <code>TreeReaderFactory</code> for a <code>Treebank</code> --
   * this method is provided in order to make the
   * <code>TreeReaderFactory</code> available to subclasses.
   *
   * @return The TreeReaderFactory
   */
  protected TreeReaderFactory treeReaderFactory() {
    return trf;
  }


  /**
   * Returns the encoding in use for treebank file bytestream access.
   */
  public String encoding() {
    return encoding;
  }


  /**
   * Empty a <code>Treebank</code>.
   */
  public abstract void clear();


  /**
   * Load a sequence of trees from given directory and its subdirectories.
   * Trees should reside in files with the suffix "mrg".
   * Or: load a single file with the given pathName (including extension)
   *
   * @param pathName file or directory name
   */
  public void loadPath(String pathName) {
    loadPath(new File(pathName));
  }


  /**
   * Load a sequence of trees from given file or directory and its subdirectories.
   * Either this loads from directories and
   * trees must reside in files with the suffix "mrg" (this is a somewhat
   * non-general Penn Treebank hold over!),
   * or it loads a single file with the given path (including extension)
   *
   * @param path File specification
   */
  public void loadPath(File path) {
    loadPath(path, DEFAULT_TREE_FILE_SUFFIX, true);
  }


  /**
   * Load trees from given directory.
   *
   * @param pathName    File or directory name
   * @param suffix      Extension of files to load: If <code>pathName</code>
   *                    is a directory, then, if this is
   *                    non-<code>null</code>, all and only files ending in "." followed
   *                    by this extension will be loaded; if it is <code>null</code>,
   *                    all files in directories will be loaded.  If <code>pathName</code>
   *                    is not a directory, this parameter is ignored.
   * @param recursively descend into subdirectories as well
   */
  public void loadPath(String pathName, String suffix, boolean recursively) {
    loadPath(new File(pathName), new ExtensionFileFilter(suffix, recursively));
  }


  /**
   * Load trees from given directory.
   *
   * @param path        file or directory to load from
   * @param suffix      suffix of files to load
   * @param recursively descend into subdirectories as well
   */
  public void loadPath(File path, String suffix, boolean recursively) {
    loadPath(path, new ExtensionFileFilter(suffix, recursively));
  }


  /**
   * Load a sequence of trees from given directory and its subdirectories
   * which match the file filter.
   * Or: load a single file with the given pathName (including extension)
   *
   * @param pathName file or directory name
   * @param filt     A filter used to determine which files match
   */
  public void loadPath(String pathName, FileFilter filt) {
    loadPath(new File(pathName), filt);
  }


  /**
   * Load trees from given path specification.
   *
   * @param path file or directory to load from
   * @param filt a FilenameFilter of files to load
   */
  public abstract void loadPath(File path, FileFilter filt);

  /**
   * Apply a TreeVisitor to each tree in the Treebank.
   * For all current implementations of Treebank, this is the fastest
   * way to traverse all the trees in the Treebank.
   *
   * @param tp The TreeVisitor to be applied
   */
  public abstract void apply(TreeVisitor tp);


  /**
   * Return a Treebank (actually a TransformingTreebank) where each
   * Tree in the current treebank has been transformed using the
   * TreeTransformer.  The argument Treebank is unchanged (assuming
   * that the TreeTransformer correctly doesn't change input Trees).
   *
   * @param treeTrans The TreeTransformer to use
   */
  public Treebank transform(TreeTransformer treeTrans) {
    return new TransformingTreebank(this, treeTrans);
  }


  /**
   * Return the whole treebank as a series of big bracketed lists.
   * Calling this is a really bad idea if your treebank is large.
   */
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    apply(new TreeVisitor() {
      public void visitTree(Tree t) {
        sb.append(t.toString());
        sb.append("\n");
      }
    });
    return sb.toString();
  }


  private final static class CounterTreeProcessor implements TreeVisitor {
    int i; // = 0;

    public void visitTree(Tree t) {
      i++;
    }

    public int total() {
      return i;
    }
  }


  /**
   * Returns the size of the Treebank.
   *
   * @return size How many trees are in the treebank
   */
  public int size() {
    CounterTreeProcessor counter = new CounterTreeProcessor();
    apply(counter);
    return counter.total();
  }


  /** Divide a Treebank into 3, by taking every 9th sentence for the dev
   *  set and every 10th for the test set.  Penn people do this.
   */
  public void decimate(Writer trainW, Writer devW, Writer testW) throws IOException {
    PrintWriter trainPW = new PrintWriter(trainW, true);
    PrintWriter devPW = new PrintWriter(devW, true);
    PrintWriter testPW = new PrintWriter(testW, true);
    int i = 0;
    for (Tree t : this) {
      if (i == 8) {
        t.pennPrint(devPW);
      } else if (i == 9) {
        t.pennPrint(testPW);
      } else {
        t.pennPrint(trainPW);
      }
      i = (i+1) % 10;
    }
  }

  /**
   * Return various statistics about the treebank (number of sentences,
   * words, tag set, etc.).
   */
  public String textualSummary() {
    return textualSummary(null);
  }

  /**
   * Return various statistics about the treebank (number of sentences,
   * words, tag set, etc.).
   */
  public String textualSummary(TreebankLanguagePack tlp) {
    int numTrees = 0;
    int numNonUnaryRoots = 0;
    Counter<Tree> nonUnaries = new Counter<Tree>();
    Counter<String> roots = new Counter<String>();
    Counter<String> starts = new Counter<String>();
    Counter<String> puncts = new Counter<String>();
    int numUnenclosedLeaves = 0;
    int numLeaves = 0;
    int numNonPhrasal = 0;
    int numWords = 0;
    int numTags = 0;
    int shortestSentence = Integer.MAX_VALUE;
    int longestSentence = 0;
    Set<String> words = new HashSet<String>();
    Counter<String> tags = new Counter<String>();
    Counter<String> cats = new Counter<String>();
    Tree leafEg = null;
    for (Tree t : this) {
      roots.incrementCount(t.value());
      numTrees++;
      int leng = t.yield().length();
      if (leng < shortestSentence) {
        shortestSentence = leng;
      }
      if (leng > longestSentence) {
        longestSentence = leng;
      }
      if (t.numChildren() > 1) {
        numNonUnaryRoots++;
        nonUnaries.incrementCount(t.localTree());
      } else if (t.isLeaf()) {
        numUnenclosedLeaves++;
      } else {
        Tree t2 = t.firstChild();
        if (t2.isLeaf()) {
          numLeaves++;
          leafEg = t;
        } else if (t2.isPreTerminal()) {
          numNonPhrasal++;
        }
        starts.incrementCount(t2.value());
      }
      for (Tree subtree : t) {
        if (subtree.isLeaf()) {
          numWords++;
          words.add(subtree.value());
        } else if (subtree.isPreTerminal()) {
          numTags++;
          tags.incrementCount(subtree.value());
          if (tlp != null && tlp.isPunctuationTag(subtree.value())) {
            puncts.incrementCount(subtree.firstChild().value());
          }
        } else if (subtree.isPhrasal()) {
          cats.incrementCount(subtree.value());
        } else {
          throw new IllegalStateException("Bad tree in treebank!: " + subtree);
        }
      }
    }
    StringWriter sw = new StringWriter(2000);
    PrintWriter pw = new PrintWriter(sw);
    NumberFormat nf = NumberFormat.getNumberInstance();
    nf.setMaximumFractionDigits(0);
    pw.println("Treebank has " + numTrees + " trees and " + numWords + " words (tokens)");
    if (numTags != numWords) {
      pw.println("  Warning! numTags differs and is " + numTags);
    }
    if (roots.size() == 1) {
      String root = (String) roots.keySet().toArray()[0];
      pw.println("  The root category is: " + root); 
    } else {
      pw.println("  Warning! " + roots.size() + " different roots in treebank: " + roots.toString(nf));
    }
    if (numNonUnaryRoots > 0) {
      pw.println("  Warning! " + numNonUnaryRoots + " trees without unary initial rewrite.  Subtrees: " + nonUnaries.toString(nf));
    }
    if (numUnenclosedLeaves > 0 || numLeaves > 0 || numNonPhrasal > 0) {
      pw.println("  Warning! Non-phrasal trees: " + numUnenclosedLeaves + " bare leaves; " + numLeaves + " root rewrites as leaf; and " + numNonPhrasal + " root rewrites as tagged word");
      if (numLeaves > 0) {
        pw.println("  Example bad root rewrites as leaf: " + leafEg);
      }
    }
    pw.println("  Sentences range from " + shortestSentence + " to " + longestSentence + " words, with an average length of " + (((numWords * 100) / numTrees) / 100.0) + " words.");
    pw.println("  " + cats.size() + " phrasal category types, " + tags.size() + " tag types, and " + words.size() + " word types");
    String[] empties = new String[] {"*", "0", "*T*", "*RNR*", "*U*", 
                                     "*?*", "*EXP*", "*ICH*", "*NOT*", "*PPA*",
                                     "*OP*", "*pro*", "*PRO*"};
    // What a dopey choice using 0 as an empty element name!!
    // The problem with the below is that words aren't turned into a basic
    // category, but empties commonly are indexed....  Would need to look
    // for them with a suffix of -[0-9]+
    Set<String> knownEmpties = new HashSet<String>(Arrays.asList(empties));
    Set emptiesIntersection = Sets.intersection(words, knownEmpties);
    if (emptiesIntersection.size() > 0) {
      pw.println("  Caution! " + emptiesIntersection.size() + 
                 " word types are known empty elements: " +
                 emptiesIntersection);
    }
    Set joint = Sets.intersection(cats.keySet(), tags.keySet());
    if (joint.size() > 0) {
      pw.println("  Warning! " + joint.size() + " items are tags and categories: " + joint);
    }
    pw.println("    Cats: " + cats.toString(nf));
    pw.println("    Tags: " + tags.toString(nf));
    pw.println("    " + starts.size() + " start categories: " + starts.toString(nf));
    if ( ! puncts.isEmpty()) {
      pw.println("    Puncts: " + puncts.toString(nf));
    }
    return sw.toString();
  }


  /**
   * This operation isn't supported for a Treebank.  Tell them immediately.
   */
  public boolean remove(Object o) {
    throw new UnsupportedOperationException("Treebank is read-only");
  }

}
