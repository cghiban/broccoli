package edu.stanford.nlp.parser.lexparser;

import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.ling.LabeledWord;
import edu.stanford.nlp.io.NumberRangesFileFilter;
import edu.stanford.nlp.io.EncodingPrintWriter;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.Treebank;
import edu.stanford.nlp.trees.DiskTreebank;
import edu.stanford.nlp.trees.TreebankLanguagePack;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.util.Numberer;
import edu.stanford.nlp.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.text.NumberFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is the default concrete instantiation of the Lexicon interface. It was
 * originally built for Penn Treebank English.
 *
 * @author Dan Klein
 * @author Galen Andrew
 * @author Christopher Manning
 */
public class BaseLexicon implements Lexicon {

  protected static final boolean DEBUG_LEXICON = false;
  protected static final boolean DEBUG_LEXICON_SCORE = false;
  private static final boolean DOCUMENT_UNKNOWNS = false;

  protected static final int nullWord = -1;

  protected static final short nullTag = -1;

  /** What type of equivalence classing is done in getSignature */
  protected int unknownLevel;

  /**
   * If a word has been seen more than this many times, then relative
   * frequencies of tags are used for POS assignment; if not, they are smoothed
   * with tag priors.
   */
  protected int smoothInUnknownsThreshold;

  /**
   * Have tags changeable based on statistics on word types having various
   * taggings.
   */
  protected boolean smartMutation;

  protected int unknownSuffixSize = 0;
  protected int unknownPrefixSize = 0;

  /** An array of Lists of rules (IntTaggedWord), indexed by word. */
  public transient List<IntTaggedWord>[] rulesWithWord;

  // protected transient Set<IntTaggedWord> rules = new
  // HashSet<IntTaggedWord>();
  // When it existed, rules somehow held a few less things than rulesWithWord
  // I never figured out why [cdm, Dec 2004]

  /** Set of all tags as IntTaggedWord. Alive in both train and runtime
   *  phases, but transient.
   */
  protected transient Set<IntTaggedWord> tags = new HashSet<IntTaggedWord>();

  protected transient Set<IntTaggedWord> words = new HashSet<IntTaggedWord>();

  // protected transient Set<IntTaggedWord> sigs=new HashSet<IntTaggedWord>();

  /** Records the number of times word/tag pair was seen in training data.
   *  Includes word/tag pairs where one is a wildcard not a real word/tag.
   */
  public Counter<IntTaggedWord> seenCounter = new Counter<IntTaggedWord>();

  /**
   * Has counts for taggings in terms of unseen signatures. The IntTagWords are
   * for (tag,sig), (tag,null), (null,sig), (null,null). (None for basic UNK if
   * there are signatures.)
   */
  protected Counter<IntTaggedWord> unSeenCounter = new Counter<IntTaggedWord>();

  /**
   * We cache the last signature looked up, because it asks for the same one
   * many times when an unknown word is encountered! (Note that under the
   * current scheme, one unknown word, if seen sentence-initially and
   * non-initially, will be parsed with two different signatures....)
   */
  protected transient int lastSignatureIndex = -1;

  protected transient int lastSentencePosition = -1;

  protected transient int lastWordToSignaturize = -1;

  double[] smooth = { 1.0, 1.0 };

  // these next two are used for smartMutation calculation
  transient double[][] m_TT = null;

  transient double[] m_T = null;

  private static final int MIN_UNKNOWN = 0;

  private static final int MAX_UNKNOWN = 9;

  private boolean flexiTag;

  public BaseLexicon() {
    this(new Options.LexOptions());
  }

  public BaseLexicon(Options.LexOptions op) {
    flexiTag = op.flexiTag;
    unknownLevel = op.useUnknownWordSignatures;
    if (unknownLevel < MIN_UNKNOWN || unknownLevel > MAX_UNKNOWN) {
      if (unknownLevel < MIN_UNKNOWN) {
        unknownLevel = MIN_UNKNOWN;
      } else if (unknownLevel > MAX_UNKNOWN) {
        unknownLevel = MAX_UNKNOWN;
      }
      System.err.println("Invalid value for useUnknownWordSignatures");
    }
    this.smoothInUnknownsThreshold = op.smoothInUnknownsThreshold;
    this.smartMutation = op.smartMutation;
    this.unknownSuffixSize = op.unknownSuffixSize;
    this.unknownPrefixSize = op.unknownPrefixSize;
  }

  /**
   * Checks whether a word is in the lexicon. This version will compile the
   * lexicon into the rulesWithWord array, if that hasn't already happened
   *
   * @param word The word as an int index to a Numberer
   * @return Whether the word is in the lexicon
   */
  public boolean isKnown(int word) {
    if (rulesWithWord == null) {
      initRulesWithWord();
    }
    return (word < rulesWithWord.length && ! rulesWithWord[word].isEmpty());
  }

  /**
   * Checks whether a word is in the lexicon. This version works even while
   * compiling lexicon with current counters (rather than using the compiled
   * rulesWithWord array).
   *
   * @param word
   *          The word as a String
   * @return Whether the word is in the lexicon
   */
  public boolean isKnown(String word) {
    IntTaggedWord iW = new IntTaggedWord(wordNumberer().number(word), nullTag);
    return seenCounter.getCount(iW) > 0.0;
  }

  /**
   * Returns the possible POS taggings for a word.
   *
   * @param word
   *          The word, represented as an integer in Numberer
   * @param loc
   *          The position of the word in the sentence (counting from 0).
   *          <i>Implementation note: The BaseLexicon class doesn't actually
   *          make use of this position information.</i>
   * @return An Iterator over a List ofIntTaggedWords, which pair the word with
   *         possible taggings as integer pairs. (Each can be thought of as a
   *         <code>tag -&gt; word<code> rule.)
   */
  public Iterator<IntTaggedWord> ruleIteratorByWord(String word, int loc) {
    return ruleIteratorByWord(wordNumberer().number(word), loc);
  }

  /** Generate the possible taggings for a word at a sentence position.
   *  This may either be based on a strict lexicon or an expanded generous
   *  set of possible taggings. <p>
   *  <i>Implementation note:</i> Expanded sets of possible taggings are
   *  calculated dynamically at runtime, so as to reduce the memory used by
   *  the lexicon (a space/time tradeoff).
   *
   *  @param word The word (as an int)
   *  @param loc  Its index in the sentence (usually only relevant for unknown words)
   *  @return A list of possible taggings
   */
  public Iterator<IntTaggedWord> ruleIteratorByWord(int word, int loc) {
    // if (rulesWithWord == null) { // tested in isKnown already
    // initRulesWithWord();
    // }
    List<IntTaggedWord> wordTaggings;
    if (isKnown(word)) {
      if ( ! flexiTag) {
        // Strict lexical tagging for seen items
        wordTaggings = rulesWithWord[word];
      } else {
        /* Allow all tags with same basicCategory */
        /* Allow all scored taggings, unless very common */
        IntTaggedWord iW = new IntTaggedWord(word, nullTag);
        if (seenCounter.getCount(iW) > smoothInUnknownsThreshold) {
          return rulesWithWord[word].iterator();
        } else {
          // give it flexible tagging not just lexicon
          wordTaggings = new ArrayList<IntTaggedWord>(40);
          for (IntTaggedWord iTW2 : tags) {
            IntTaggedWord iTW = new IntTaggedWord(word, iTW2.tag);
            if (score(iTW, loc) > Float.NEGATIVE_INFINITY) {
              wordTaggings.add(iTW);
            }
          }
        }
      }
    } else {
      // we copy list so we can insert correct word in each item
      wordTaggings = new ArrayList<IntTaggedWord>(40);
      for (IntTaggedWord iTW : rulesWithWord[wordNumberer.number(UNKNOWN_WORD)]) {
        wordTaggings.add(new IntTaggedWord(word, iTW.tag));
      }
    }
    if (DEBUG_LEXICON) {
      EncodingPrintWriter.err.println("Lexicon: " + wordNumberer().object(word) + " (" +
              (isKnown(word) ? "known": "unknown") + ", loc=" + loc + ", n=" +
              (isKnown(word) ? word: wordNumberer.number(UNKNOWN_WORD)) + ") " +
              (flexiTag ? "flexi": "lexicon") + " taggings: " + wordTaggings, "UTF-8");
    }
    return wordTaggings.iterator();
 }

  protected void initRulesWithWord() {
    if (Test.verbose || DEBUG_LEXICON) {
      System.err.print("\nInitializing lexicon scores ... ");
    }
    // int numWords = words.size()+sigs.size()+1;
    int unkWord = wordNumberer().number(UNKNOWN_WORD);
    int numWords = wordNumberer().total();
    rulesWithWord = new List[numWords];
    for (int w = 0; w < numWords; w++) {
      rulesWithWord[w] = new ArrayList<IntTaggedWord>(1); // most have 1 or 2
                                                          // items in them
    }
    // for (Iterator ruleI = rules.iterator(); ruleI.hasNext();) {
    tags = new HashSet<IntTaggedWord>();
    for (IntTaggedWord iTW : seenCounter.keySet()) {
      if (iTW.word() == nullWord && iTW.tag() != nullTag) {
        tags.add(iTW);
      }
    }

    // tags for unknown words
    if (DEBUG_LEXICON) {
      System.err.println("Lexicon initializing tags for UNKNOWN WORD (" +
                         Lexicon.UNKNOWN_WORD + ", " + unkWord + ")");
    }
    for (IntTaggedWord iT : tags) {
      double types = unSeenCounter.getCount(iT);
      if (types > Train.openClassTypesThreshold) {
        // Number of types before it's treated as open class
        IntTaggedWord iTW = new IntTaggedWord(unkWord, iT.tag);
        rulesWithWord[iTW.word].add(iTW);
      }
    }
    if (Test.verbose || DEBUG_LEXICON) {
      System.err.print("The " + rulesWithWord[unkWord].size() + " open class tags are: [");
      for (IntTaggedWord item : rulesWithWord[unkWord]) {
        System.err.print(" " + tagNumberer().object(item.tag()));
        if (DEBUG_LEXICON) {
          System.err.print(" (tag " + item.tag() + ", type count is " +
                           unSeenCounter.getCount(item) + ")");
        }
      }
      System.err.println(" ] ");
    }

    for (IntTaggedWord iTW : seenCounter.keySet()) {
      if (iTW.tag() != nullTag && iTW.word() != nullWord) {
        rulesWithWord[iTW.word].add(iTW);
      }
    }
  }


  protected List<IntTaggedWord> treeToEvents(Tree tree, boolean keepTagsAsLabels) {
    if (!keepTagsAsLabels) { return treeToEvents(tree); }
    List<LabeledWord> labeledWords = tree.labeledYield();
//     for (LabeledWord tw : labeledWords) {
//       System.err.println(tw);
//     }
    return listOfLabeledWordsToEvents(labeledWords);
  }

  protected List<IntTaggedWord> treeToEvents(Tree tree) {
    List<TaggedWord> taggedWords = tree.taggedYield();
    return listToEvents(taggedWords);
  }

  protected List<IntTaggedWord> listToEvents(List<TaggedWord> taggedWords) {
    List<IntTaggedWord> itwList = new ArrayList<IntTaggedWord>();
    for (TaggedWord tw : taggedWords) {
     IntTaggedWord iTW = new IntTaggedWord(wordNumberer().number(tw.word()),
                                            tagNumberer().number(tw.tag()));
      itwList.add(iTW);
    }
    return itwList;
  }

  protected List<IntTaggedWord> listOfLabeledWordsToEvents(List<LabeledWord> taggedWords) {
    List<IntTaggedWord> itwList = new ArrayList<IntTaggedWord>();
    for (LabeledWord tw : taggedWords) {
     IntTaggedWord iTW = new IntTaggedWord(wordNumberer().number(tw.word()),
                                            tagNumberer().number(tw.tag()));
      itwList.add(iTW);
    }
    return itwList;
  }

  public void addAll(List<TaggedWord> tagWords) {
    addAll(tagWords, 1.0);
  }

  public void addAll(List<TaggedWord> taggedWords, double weight) {
    List<IntTaggedWord> tagWords = listToEvents(taggedWords);

  }

  public void trainWithExpansion(Collection<TaggedWord> taggedWords) {
  }

  /**
   * Trains this lexicon on the Collection of trees.
   */
  public void train(Collection<Tree> trees) {
    train(trees, 1.0, false);
  }

  /**
   * Trains this lexicon on the Collection of trees.
   */
  public void train(Collection<Tree> trees, boolean keepTagsAsLabels) {
    train(trees, 1.0, keepTagsAsLabels);
  }

  public void train(Collection<Tree> trees, double weight) {
    train(trees, weight, false);
  }

  /**
   * Trains this lexicon on the Collection of trees.
   */
  public void train(Collection<Tree> trees, double weight, boolean keepTagsAsLabels) {
    // scan data
    int tNum = 0;
    int tSize = trees.size();
    int indexToStartUnkCounting = (int) (tSize * Train.fractionBeforeUnseenCounting);
    Numberer wordNumberer = wordNumberer();
    Numberer tagNumberer = tagNumberer();
    int unkNum = wordNumberer.number(UNKNOWN_WORD);
    if (DOCUMENT_UNKNOWNS) {
      System.err.println("Collecting " + UNKNOWN_WORD + " from trees " +
                         (indexToStartUnkCounting + 1) + " to " + tSize);
    }

    for (Tree tree : trees) {
      tNum++;
      List<IntTaggedWord> taggedWords = treeToEvents(tree, keepTagsAsLabels);
      for (int w = 0, sz = taggedWords.size(); w < sz; w++) {
        IntTaggedWord iTW = taggedWords.get(w);
        seenCounter.incrementCount(iTW, weight);
        IntTaggedWord iT = new IntTaggedWord(nullWord, iTW.tag);
        seenCounter.incrementCount(iT, weight);
        IntTaggedWord iW = new IntTaggedWord(iTW.word, nullTag);
        seenCounter.incrementCount(iW, weight);
        IntTaggedWord i = new IntTaggedWord(nullWord, nullTag);
        seenCounter.incrementCount(i, weight);
        // rules.add(iTW);
        tags.add(iT);
        words.add(iW);
        if (tNum > indexToStartUnkCounting) {
          // start doing this once some way through trees; tNum is 1 based counting
          if (seenCounter.getCount(iW) < 2) {
            // it's an entirely unknown word
            int s = getSignatureIndex(iTW.word, w);
            if (DOCUMENT_UNKNOWNS) {
              String wStr = (String) wordNumberer.object(iTW.word);
              String tStr = (String) tagNumberer.object(iTW.tag);
              String sStr = (String) wordNumberer.object(s);
              EncodingPrintWriter.err.println("Unknown word/tag/sig:\t" +
                                              wStr + "\t" + tStr + "\t" + sStr,
                                              "UTF-8");
            }
            IntTaggedWord iTS = new IntTaggedWord(s, iTW.tag);
            IntTaggedWord iS = new IntTaggedWord(s, nullTag);
            unSeenCounter.incrementCount(iTS, weight);
            unSeenCounter.incrementCount(iT, weight);
            unSeenCounter.incrementCount(iS, weight);
            unSeenCounter.incrementCount(i, weight);
            // rules.add(iTS);
            // sigs.add(iS);
          } // else {
          // if (seenCounter.getCount(iTW) < 2) {
          // it's a new tag for a known word
          // do nothing for now
          // }
          // }
        }
      }
    }
    // make sure the unseen counter isn't empty!  If it is, put in
    // a uniform unseen over tags
    if (unSeenCounter.isEmpty()) {
      int numTags = tagNumberer().total();
      for (int tt = 0; tt < numTags; tt++) {
        if ( ! BOUNDARY_TAG.equals(tagNumberer().object(tt))) {
          IntTaggedWord iT = new IntTaggedWord(nullWord, tt);
          IntTaggedWord i = new IntTaggedWord(nullWord, nullTag);
          unSeenCounter.incrementCount(iT, weight);
          unSeenCounter.incrementCount(i, weight);
        }
      }
    }
    // index the possible tags for each word
    // numWords = wordNumberer.total();
    // unknownWordIndex = Numberer.number("words",Lexicon.UNKNOWN_WORD);
    // initRulesWithWord();
    tune(trees);
    if (DEBUG_LEXICON) {
      printLexStats();
    }
  }

  /**
   * Adds the tagging with count to the data structures in this Lexicon.
   */
  protected void addTagging(boolean seen, IntTaggedWord itw, double count) {
    if (seen) {
      seenCounter.incrementCount(itw, count);
      if (itw.tag() == nullTag) {
        words.add(itw);
      } else if (itw.word() == nullWord) {
        tags.add(itw);
      } else {
        // rules.add(itw);
      }
    } else {
      unSeenCounter.incrementCount(itw, count);
      // if (itw.tag() == nullTag) {
      // sigs.add(itw);
      // }
    }
  }

  /**
   * Returns the index of the signature of the word numbered wordIndex, where
   * the signature is the String representation of unknown word features.
   * Caches the last signature index returned.
   */
  protected int getSignatureIndex(int wordIndex, int sentencePosition) {
    if (wordIndex == lastWordToSignaturize && sentencePosition == lastSentencePosition) {
      // System.err.println("Signature: cache mapped " + wordIndex + " to " +
      // lastSignatureIndex);
      return lastSignatureIndex;
    } else {
      String uwSig = getSignature((String) wordNumberer().object(wordIndex), sentencePosition);
      int sig = wordNumberer().number(uwSig);
      lastSignatureIndex = sig;
      lastSentencePosition = sentencePosition;
      lastWordToSignaturize = wordIndex;
      return sig;
    }
  }

  /**
   * This routine returns a String that is the "signature" of the class of a
   * word. For, example, it might represent whether it is a number of ends in
   * -s. The strings returned by convention match the pattern UNK or UNK-.* ,
   * which is just assumed to not match any real word. Behavior depends on the
   * unknownLevel (-uwm flag) passed in to the class. The recognized numbers are
   * 1-9: 5 is fairly English-specific; 4, 3, and 2 look for various word
   * features (digits, dashes, etc.) which are only vaguely English-specific; 1
   * uses the last two characters combined with a simple classification by
   * capitalization. 6-9 were added for Arabic. 6 looks for the prefix Al- (and
   * knows that Buckwalter uses various symbols as letters), while 7 just looks
   * for numbers and last letter. 8 looks for Al-, looks for several useful
   * suffixes, and tracks the first letter of the word. (note that the first
   * letter seems a bit more informative than the last letter, overall.)
   * 9 tries to build on 8, but avoiding some of its perceived flaws: really it
   * was using the first AND last letter.
   *
   * @param word The word to make a signature for
   * @param loc Its position in the sentence (mainly so sentence-initial
   *          capitalized words can be treated differently)
   * @return A String that is its signature (equivalence class)
   */
  public String getSignature(String word, int loc) {
    StringBuilder sb = new StringBuilder("UNK");
    switch (unknownLevel) {

      case 9: // Chris' attempt at improving Roger's Arabic attempt, Nov 2006.
      {
        boolean allDigitPlus = ArabicUnknownWordSignatures.allDigitPlus(word);
        int leng = word.length();
        if (allDigitPlus) {
          sb.append("-NUM");
        } else if (word.startsWith("Al") || word.startsWith("\u0627\u0644")) {
          sb.append("-Al");
        } else {
          // the first letters of a word seem more informative overall than the
          // last letters.
          // Alternatively we could add on the first two letters, if there's
          // enough data.
          if (unknownPrefixSize > 0) {
            int min = leng < unknownPrefixSize ? leng: unknownPrefixSize;
            sb.append("-").append(word.substring(0, min));
          }
        }

        sb.append(ArabicUnknownWordSignatures.likelyAdjectivalSuffix(word));
        sb.append(ArabicUnknownWordSignatures.pastTenseVerbNumberSuffix(word));
        sb.append(ArabicUnknownWordSignatures.presentTenseVerbNumberSuffix(word));
        String ans = ArabicUnknownWordSignatures.abstractionNounSuffix(word);
        if (! "".equals(ans)) {
          sb.append(ans);
        } else {
          sb.append(ArabicUnknownWordSignatures.taaMarbuuTaSuffix(word));
        }
        if (unknownSuffixSize > 0 && ! allDigitPlus) {
          int min = leng < unknownSuffixSize ? leng: unknownSuffixSize;
          sb.append("-").append(word.substring(word.length() - min));
        }
        break;
      }

      case 8: // Roger's attempt at an Arabic UWM, May 2006.
      {
        if (word.startsWith("Al")) {
          sb.append("-Al");
        }
        boolean allDigitPlus = ArabicUnknownWordSignatures.allDigitPlus(word);
        if (allDigitPlus) {
          sb.append("-NUM");
        } else {
          // the first letters of a word seem more informative overall than the
          // last letters.
          // Alternatively we could add on the first two letters, if there's
          // enough data.
          sb.append("-").append(word.charAt(0));
        }
        sb.append(ArabicUnknownWordSignatures.likelyAdjectivalSuffix(word));
        sb.append(ArabicUnknownWordSignatures.pastTenseVerbNumberSuffix(word));
        sb.append(ArabicUnknownWordSignatures.presentTenseVerbNumberSuffix(word));
        sb.append(ArabicUnknownWordSignatures.taaMarbuuTaSuffix(word));
        sb.append(ArabicUnknownWordSignatures.abstractionNounSuffix(word));
      }

      case 7: {
        // For Arabic with Al's separated off (cdm, May 2006)
        // { -NUM, -lastChar }
        boolean allDigitPlus = ArabicUnknownWordSignatures.allDigitPlus(word);
        if (allDigitPlus) {
          sb.append("-NUM");
        } else {
          sb.append(word.charAt(word.length() - 1));
        }
        break;
      }

      case 6: {
        // For Arabic (cdm, May 2006), with Al- as part of word
        // { -Al, 0 } +
        // { -NUM, -last char(s) }
        if (word.startsWith("Al")) {
          sb.append("-Al");
        }
        boolean allDigitPlus = ArabicUnknownWordSignatures.allDigitPlus(word);
        if (allDigitPlus) {
          sb.append("-NUM");
        } else {
          sb.append(word.charAt(word.length() - 1));
        }
        break;
      }

      case 5: {
        // Reformed Mar 2004 (cdm); hopefully much better now.
        // { -CAPS, -INITC ap, -LC lowercase, 0 } +
        // { -KNOWNLC, 0 } + [only for INITC]
        // { -NUM, 0 } +
        // { -DASH, 0 } +
        // { -last lowered char(s) if known discriminating suffix, 0}
        int wlen = word.length();
        int numCaps = 0;
        boolean hasDigit = false;
        boolean hasDash = false;
        boolean hasLower = false;
        for (int i = 0; i < wlen; i++) {
          char ch = word.charAt(i);
          if (Character.isDigit(ch)) {
            hasDigit = true;
          } else if (ch == '-') {
            hasDash = true;
          } else if (Character.isLetter(ch)) {
            if (Character.isLowerCase(ch)) {
              hasLower = true;
            } else if (Character.isTitleCase(ch)) {
              hasLower = true;
              numCaps++;
            } else {
              numCaps++;
            }
          }
        }
        char ch0 = word.charAt(0);
        String lowered = word.toLowerCase();
        if (Character.isUpperCase(ch0) || Character.isTitleCase(ch0)) {
          if (loc == 0 && numCaps == 1) {
            sb.append("-INITC");
            if (isKnown(lowered)) {
              sb.append("-KNOWNLC");
            }
          } else {
            sb.append("-CAPS");
          }
        } else if (!Character.isLetter(ch0) && numCaps > 0) {
          sb.append("-CAPS");
        } else if (hasLower) { // (Character.isLowerCase(ch0)) {
          sb.append("-LC");
        }
        if (hasDigit) {
          sb.append("-NUM");
        }
        if (hasDash) {
          sb.append("-DASH");
        }
        if (lowered.endsWith("s") && wlen >= 3) {
          // here length 3, so you don't miss out on ones like 80s
          char ch2 = lowered.charAt(wlen - 2);
          // not -ess suffixes or greek/latin -us, -is
          if (ch2 != 's' && ch2 != 'i' && ch2 != 'u') {
            sb.append("-s");
          }
        } else if (word.length() >= 5 && !hasDash && !(hasDigit && numCaps > 0)) {
          // don't do for very short words;
          // Implement common discriminating suffixes
          if (lowered.endsWith("ed")) {
            sb.append("-ed");
          } else if (lowered.endsWith("ing")) {
            sb.append("-ing");
          } else if (lowered.endsWith("ion")) {
            sb.append("-ion");
          } else if (lowered.endsWith("er")) {
            sb.append("-er");
          } else if (lowered.endsWith("est")) {
            sb.append("-est");
          } else if (lowered.endsWith("ly")) {
            sb.append("-ly");
          } else if (lowered.endsWith("ity")) {
            sb.append("-ity");
          } else if (lowered.endsWith("y")) {
            sb.append("-y");
          } else if (lowered.endsWith("al")) {
            sb.append("-al");
            // } else if (lowered.endsWith("ble")) {
            // sb.append("-ble");
            // } else if (lowered.endsWith("e")) {
            // sb.append("-e");
          }
        }
        break;
      }

      case 4: {
        boolean hasDigit = false;
        boolean hasNonDigit = false;
        boolean hasLetter = false;
        boolean hasLower = false;
        boolean hasDash = false;
        boolean hasPeriod = false;
        boolean hasComma = false;
        for (int i = 0; i < word.length(); i++) {
          char ch = word.charAt(i);
          if (Character.isDigit(ch)) {
            hasDigit = true;
          } else {
            hasNonDigit = true;
            if (Character.isLetter(ch)) {
              hasLetter = true;
              if (Character.isLowerCase(ch) || Character.isTitleCase(ch)) {
                hasLower = true;
              }
            } else {
              if (ch == '-') {
                hasDash = true;
              } else if (ch == '.') {
                hasPeriod = true;
              } else if (ch == ',') {
                hasComma = true;
              }
            }
          }
        }
        // 6 way on letters
        if (Character.isUpperCase(word.charAt(0)) || Character.isTitleCase(word.charAt(0))) {
          if (!hasLower) {
            sb.append("-AC");
          } else if (loc == 0) {
            sb.append("-SC");
          } else {
            sb.append("-C");
          }
        } else if (hasLower) {
          sb.append("-L");
        } else if (hasLetter) {
          sb.append("-U");
        } else {
          // no letter
          sb.append("-S");
        }
        // 3 way on number
        if (hasDigit && !hasNonDigit) {
          sb.append("-N");
        } else if (hasDigit) {
          sb.append("-n");
        }
        // binary on period, dash, comma
        if (hasDash) {
          sb.append("-H");
        }
        if (hasPeriod) {
          sb.append("-P");
        }
        if (hasComma) {
          sb.append("-C");
        }
        if (word.length() > 3) {
          // don't do for very short words: "yes" isn't an "-es" word
          // try doing to lower for further densening and skipping digits
          char ch = word.charAt(word.length() - 1);
          if (Character.isLetter(ch)) {
            sb.append("-");
            sb.append(Character.toLowerCase(ch));
          }
        }
        break;
      }

      case 3: {
        // This basically works right, except note that 'S' is applied to all
        // capitalized letters in first word of sentence, not just first....
        sb.append("-");
        char lastClass = '-'; // i.e., nothing
        char newClass;
        int num = 0;
        for (int i = 0; i < word.length(); i++) {
          char ch = word.charAt(i);
          if (Character.isUpperCase(ch) || Character.isTitleCase(ch)) {
            if (loc == 0) {
              newClass = 'S';
            } else {
              newClass = 'L';
            }
          } else if (Character.isLetter(ch)) {
            newClass = 'l';
          } else if (Character.isDigit(ch)) {
            newClass = 'd';
          } else if (ch == '-') {
            newClass = 'h';
          } else if (ch == '.') {
            newClass = 'p';
          } else {
            newClass = 's';
          }
          if (newClass != lastClass) {
            lastClass = newClass;
            sb.append(lastClass);
            num = 1;
          } else {
            if (num < 2) {
              sb.append('+');
            }
            num++;
          }
        }
        if (word.length() > 3) {
          // don't do for very short words: "yes" isn't an "-es" word
          // try doing to lower for further densening and skipping digits
          char ch = Character.toLowerCase(word.charAt(word.length() - 1));
          sb.append('-');
          sb.append(ch);
        }
        break;
      }

      case 2: {
        // {-ALLC, -INIT, -UC, -LC, zero} +
        // {-DASH, zero} +
        // {-NUM, -DIG, zero} +
        // {lowerLastChar, zeroIfShort}
        boolean hasDigit = false;
        boolean hasNonDigit = false;
        boolean hasLower = false;
        int wlen = word.length();
        for (int i = 0; i < wlen; i++) {
          char ch = word.charAt(i);
          if (Character.isDigit(ch)) {
            hasDigit = true;
          } else {
            hasNonDigit = true;
            if (Character.isLetter(ch)) {
              if (Character.isLowerCase(ch) || Character.isTitleCase(ch)) {
                hasLower = true;
              }
            }
          }
        }
        if (wlen > 0
            && (Character.isUpperCase(word.charAt(0)) || Character.isTitleCase(word.charAt(0)))) {
          if (!hasLower) {
            sb.append("-ALLC");
          } else if (loc == 0) {
            sb.append("-INIT");
          } else {
            sb.append("-UC");
          }
        } else if (hasLower) { // if (Character.isLowerCase(word.charAt(0))) {
          sb.append("-LC");
        }
        // no suffix = no (lowercase) letters
        if (word.indexOf('-') >= 0) {
          sb.append("-DASH");
        }
        if (hasDigit) {
          if (!hasNonDigit) {
            sb.append("-NUM");
          } else {
            sb.append("-DIG");
          }
        } else if (wlen > 3) {
          // don't do for very short words: "yes" isn't an "-es" word
          // try doing to lower for further densening and skipping digits
          char ch = word.charAt(word.length() - 1);
          sb.append(Character.toLowerCase(ch));
        }
        // no suffix = short non-number, non-alphabetic
        break;
      }

      case 1:
        sb.append("-");
        sb.append(word.substring(Math.max(word.length() - 2, 0), word.length()));
        sb.append("-");
        if (Character.isLowerCase(word.charAt(0))) {
          sb.append("LOWER");
        } else {
          if (Character.isUpperCase(word.charAt(0))) {
            if (loc == 0) {
              sb.append("INIT");
            } else {
              sb.append("UPPER");
            }
          } else {
            sb.append("OTHER");
          }
        }
      default:
    // 0 = do nothing so it just stays as "UNK"
    } // end switch (unknownLevel)
    // System.err.println("Summarized " + word + " to " + sb.toString());
    return sb.toString();
  } // end getSignature()

  /**
   * This records how likely it is for a word with one tag to also have another
   * tag. This won't work after serialization/deserialization, but that is how
   * it is currently called....
   */
  void buildPT_T() {
    int numTags = tagNumberer().total();
    m_TT = new double[numTags][numTags];
    m_T = new double[numTags];
    double[] tmp = new double[numTags];
    for (IntTaggedWord word : words) {
      IntTaggedWord iTW = new IntTaggedWord(word.word, nullTag);
      double tot = 0.0;
      for (int t = 0; t < numTags; t++) {
        iTW.tag = (short) t;
        tmp[t] = seenCounter.getCount(iTW);
        tot += tmp[t];
      }
      if (tot < 10) {
        continue;
      }
      for (int t = 0; t < numTags; t++) {
        for (int t2 = 0; t2 < numTags; t2++) {
          if (tmp[t2] > 0.0) {
            double c = tmp[t] / tot;
            m_T[t] += c;
            m_TT[t2][t] += c;
          }
        }
      }
    }
  }

  /**
   * Get the score of this word with this tag (as an IntTaggedWord) at this
   * location. (Presumably an estimate of P(word | tag).)
   * <p>
   * <i>Implementation documentation:</i> Seen: c_W = count(W) c_TW =
   * count(T,W) c_T = count(T) c_Tunseen = count(T) among new words in 2nd half
   * total = count(seen words) totalUnseen = count("unseen" words) p_T_U =
   * Pmle(T|"unseen") pb_T_W = P(T|W). If (c_W > smoothInUnknownsThreshold) =
   * c_TW/c_W Else (if not smart mutation) pb_T_W = bayes prior smooth[1] with
   * p_T_U p_T= Pmle(T) p_W = Pmle(W) pb_W_T = log(pb_T_W * p_W / p_T) [Bayes
   * rule] Note that this doesn't really properly reserve mass to unknowns.
   *
   * Unseen: c_TS = count(T,Sig|Unseen) c_S = count(Sig) c_T = count(T|Unseen)
   * c_U = totalUnseen above p_T_U = Pmle(T|Unseen) pb_T_S = Bayes smooth of
   * Pmle(T|S) with P(T|Unseen) [smooth[0]] pb_W_T = log(P(W|T)) inverted
   *
   * @param iTW
   *          An IntTaggedWord pairing a word and POS tag
   * @param loc
   *          The position in the sentence. <i>In the default implementation
   *          this is used only for unknown words to change their probability
   *          distribution when sentence initial</i>
   * @return A float score, usually - log P(word|tag)
   */
  public float score(IntTaggedWord iTW, int loc) {
    int word = iTW.word;
    short tag = iTW.tag;

    iTW.tag = nullTag;
    double c_W = seenCounter.getCount(iTW);
    // double x_W = xferCounter.getCount(iTW);
    iTW.tag = tag;

    double pb_W_T; // always set below

    if (DEBUG_LEXICON) {
      // dump info about last word
      if (iTW.word != debugLastWord) {
        if (debugLastWord >= 0 && debugPrefix != null) {
          // the 2nd conjunct in test above handles older serialized files
          EncodingPrintWriter.err.println(debugPrefix + debugProbs + debugNoProbs, "UTF-8");
        }
      }
    }

    boolean seen = (c_W > 0.0);

    if (seen) {
      // known word model for P(T|W)
      if (DEBUG_LEXICON_SCORE) {
        System.err.println("Lexicon.score " + wordNumberer().object(word) + " as known word.");
      }

      double c_TW = seenCounter.getCount(iTW);
      // double x_TW = xferCounter.getCount(iTW);
      iTW.word = nullWord;
      double c_T = seenCounter.getCount(iTW);
      double c_Tunseen = unSeenCounter.getCount(iTW);
      iTW.tag = nullTag;
      double total = seenCounter.getCount(iTW);
      double totalUnseen = unSeenCounter.getCount(iTW);
      iTW.tag = tag;
      iTW.word = word;

      // c_TW = Math.sqrt(c_TW);
      // c_TW += 0.5;

      double p_T_U = c_Tunseen / totalUnseen;
      double pb_T_W; // always set below

      if (DEBUG_LEXICON_SCORE) {
        System.err.println("c_W is " + c_W + " smoothInUnknownsThresh is " +
             smoothInUnknownsThreshold + " mle = " + (c_TW/c_W));
      }
      if (c_W > smoothInUnknownsThreshold) {
        // we've seen the word enough times to have confidence in its tagging
        pb_T_W = c_TW / c_W;
      } else {

        // we haven't seen the word enough times to have confidence in its
        // tagging
        if (smartMutation) {
          int numTags = tagNumberer().total();
          if (m_TT == null || numTags != m_T.length) {
            buildPT_T();
          }
          p_T_U *= 0.1;
          // System.out.println("Checking "+iTW);
          for (int t = 0; t < numTags; t++) {
            IntTaggedWord iTW2 = new IntTaggedWord(word, t);
            double p_T_W2 = seenCounter.getCount(iTW2) / c_W;
            if (p_T_W2 > 0) {
              // System.out.println(" Observation of "+tagNumberer.object(t)+"
              // ("+seenCounter.getCount(iTW2)+") mutated to
              // "+tagNumberer.object(iTW.tag)+" at rate
              // "+(m_TT[tag][t]/m_T[t]));
              p_T_U += p_T_W2 * m_TT[tag][t] / m_T[t] * 0.9;
            }
          }
        }
        if (DEBUG_LEXICON_SCORE) {
          System.err.println("c_TW = " + c_TW + " c_W = " + c_W +
                             " p_T_U = " + p_T_U);
        }
        pb_T_W = (c_TW + smooth[1] * p_T_U) / (c_W + smooth[1]);
      }
      // double pb_T_W = (c_TW+smooth[1]*x_TW)/(c_W+smooth[1]*x_W);

      double p_T = (c_T / total);
      double p_W = (c_W / total);
      pb_W_T = Math.log(pb_T_W * p_W / p_T);

      if (DEBUG_LEXICON) {
        if (iTW.word != debugLastWord) {
          debugLastWord = iTW.word;
          debugLoc = loc;
          debugProbs = new StringBuilder();
          debugNoProbs = new StringBuilder("impossible: ");
          debugPrefix = "Lexicon: " + wordNumberer().object(debugLastWord) + " (known): ";
        }
        if (pb_W_T > Double.NEGATIVE_INFINITY) {
          NumberFormat nf = NumberFormat.getNumberInstance();
          nf.setMaximumFractionDigits(3);
          debugProbs.append(tagNumberer().object(tag) + ": cTW=" + c_TW + " c_T=" + c_T
                            + " pb_T_W=" + nf.format(pb_T_W) + " log pb_W_T=" + nf.format(pb_W_T)
                            + ", ");
          // debugProbs.append("\n" + "smartMutation=" + smartMutation + "
          // smoothInUnknownsThreshold=" + smoothInUnknownsThreshold + "
          // smooth0=" + smooth[0] + "smooth1=" + smooth[1] + " p_T_U=" + p_T_U
          // + " c_W=" + c_W);
        } else {
          debugNoProbs.append(tagNumberer().object(tag)).append(" ");
        }
      } // end if (DEBUG_LEXICON)

    } else { // when unseen

      // unknown word model for P(T|S)
      int sig = getSignatureIndex(iTW.word, loc);

      iTW.word = sig;
      double c_TS = unSeenCounter.getCount(iTW);
      iTW.tag = nullTag;
      double c_S = unSeenCounter.getCount(iTW);
      iTW.word = nullWord;
      double c_U = unSeenCounter.getCount(iTW);
      double total = seenCounter.getCount(iTW);
      iTW.tag = tag;
      double c_T = unSeenCounter.getCount(iTW);
      double c_Tseen = seenCounter.getCount(iTW);
      iTW.word = word;

      double p_T_U = c_T / c_U;
      if (unknownLevel == 0) {
        c_TS = 0;
        c_S = 0;
      }
      double pb_T_S = (c_TS + smooth[0] * p_T_U) / (c_S + smooth[0]);

      double p_T = (c_Tseen / total);
      double p_W = 1.0 / total;
      pb_W_T = Math.log(pb_T_S * p_W / p_T);
      if (DEBUG_LEXICON) {
        if (iTW.word != debugLastWord) {
          debugLastWord = iTW.word;
          debugLoc = loc;
          debugProbs = new StringBuilder();
          debugNoProbs = new StringBuilder(" impossible: ");
          int sigIdx = getSignatureIndex(debugLastWord, debugLoc);
          debugPrefix = "Lexicon: " + wordNumberer.object(debugLastWord) + " ("
                        + debugLastWord + ") idx " + debugLoc + " -> "
            + wordNumberer().object(sigIdx) + " (" + sigIdx
                        + "): ";
        }
        if (pb_W_T > Double.NEGATIVE_INFINITY) {
          NumberFormat nf = NumberFormat.getNumberInstance();
          nf.setMaximumFractionDigits(4);
          debugProbs.append(tagNumberer().object(tag) + ": cTS=" + c_TS
                            + " c_T=" + c_T + " pb_T_S=" + nf.format(pb_T_S) + " log pb_W_T="
                            + nf.format(pb_W_T) + " c_S=" + c_S + " c_U=" + c_U + " c_T=" + c_T
                            + ", ");
          // + " pb_W_T=" + nf.format(Math.exp(pb_W_T))
        } else {
          debugNoProbs.append(tagNumberer().object(tag)).append(" ");
        }
      } // end if (DEBUG_LEXICON)
    }

    if (pb_W_T > -100.0) {
      return (float) pb_W_T;
    }
    return Float.NEGATIVE_INFINITY;
  } // end score()

  private transient int debugLastWord = -1;

  private transient int debugLoc = -1;

  private transient StringBuilder debugProbs;

  private transient StringBuilder debugNoProbs;

  private transient String debugPrefix;

  public void tune(Collection<Tree> trees) {
    double bestScore = Double.NEGATIVE_INFINITY;
    double[] bestSmooth = { 0.0, 0.0 };
    for (smooth[0] = 1; smooth[0] <= 1; smooth[0] *= 2.0) {// 64
      for (smooth[1] = 0.2; smooth[1] <= 0.2; smooth[1] *= 2.0) {// 3
        // for (smooth[0]=0.5; smooth[0]<=64; smooth[0] *= 2.0) {//64
        // for (smooth[1]=0.1; smooth[1]<=12.8; smooth[1] *= 2.0) {//3
        double score = 0.0;
        // score = scoreAll(trees);
        if (Test.verbose) {
          System.out.println("Tuning lexicon: s0 " + smooth[0] + " s1 " + smooth[1] + " is "
                             + score + " " + trees.size() + " trees.");
        }
        if (score > bestScore) {
          System.arraycopy(smooth, 0, bestSmooth, 0, smooth.length);
          bestScore = score;
        }
      }
    }
    System.arraycopy(bestSmooth, 0, smooth, 0, bestSmooth.length);
    if (smartMutation) {
      smooth[0] = 8.0;
      // smooth[1] = 1.6;
      // smooth[0] = 0.5;
      smooth[1] = 0.1;
    }
    if (Test.unseenSmooth > 0.0) {
      smooth[0] = Test.unseenSmooth;
    }
    if (Test.verbose) {
      System.out.println("Tuning selected smoothUnseen " + smooth[0] + " smoothSeen " + smooth[1]
                         + " at " + bestScore);
    }
  }

  /**
   * Populates data in this Lexicon from the character stream given by the
   * Reader r.
   */
  public void readData(BufferedReader in) throws IOException {
    final String SEEN = "SEEN";
    String line;
    int lineNum = 1;
    boolean seen;
    // all lines have one tagging with raw count per line
    line = in.readLine();
    Pattern p = Pattern.compile("^smooth\\[([0-9])\\] = (.*)$");
    while (line != null && line.length() > 0) {
      try {
        Matcher m = p.matcher(line);
        if (m.matches()) {
          int i = Integer.parseInt(m.group(1));
          smooth[i] = Double.parseDouble(m.group(2));
        } else {
          // split on spaces, quote with doublequote, and escape with backslash
          String[] fields = StringUtils.splitOnCharWithQuoting(line, ' ', '\"', '\\');
          // System.out.println("fields:\n" + fields[0] + "\n" + fields[1] +
          // "\n" + fields[2] + "\n" + fields[3] + "\n" + fields[4]);
          seen = fields[3].equals(SEEN);
          addTagging(seen, new IntTaggedWord(fields[2], fields[0]), Double.parseDouble(fields[4]));
        }
      } catch (RuntimeException e) {
        throw new IOException("Error on line " + lineNum + ": " + line);
      }
      lineNum++;
      line = in.readLine();
    }
  }

  /**
   * Writes out data from this Object to the Writer w. Rules are separated by
   * newline, and rule elements are delimited by \t.
   */
  public void writeData(Writer w) throws IOException {
    PrintWriter out = new PrintWriter(w);

    for (IntTaggedWord itw : seenCounter.keySet()) {
      out.println(itw.toLexicalEntry() + " SEEN " + seenCounter.getCount(itw));
    }
    for (IntTaggedWord itw : unSeenCounter.keySet()) {
      out.println(itw.toLexicalEntry() + " UNSEEN " + unSeenCounter.getCount(itw));
    }
    for (int i = 0; i < smooth.length; i++) {
      out.println("smooth[" + i + "] = " + smooth[i]);
    }
    out.flush();
  }

  /** Returns the number of rules (tag rewrites as word) in the Lexicon.
   *  This method assumes that the lexicon has been initialized.
   */
  public int numRules() {
    if (rulesWithWord == null) {
      initRulesWithWord();
    }
    int accumulated = 0;
    for (List<IntTaggedWord> lis : rulesWithWord) {
      accumulated += lis.size();
    }
    return accumulated;
  }


  private static final int STATS_BINS = 15;

  /** Print some statistics about this lexicon. */
  public void printLexStats() {
    if (rulesWithWord == null) {
      initRulesWithWord();
    }
    System.out.println("BaseLexicon statistics");
    System.out.println("unknownLevel is " + unknownLevel);
    // System.out.println("Rules size: " + rules.size());
    System.out.println("Sum of rulesWithWord: " + numRules());
    System.out.println("Tags size: " + tags.size());
    int wsize = words.size();
    System.out.println("Words size: " + wsize);
    // System.out.println("Unseen Sigs size: " + sigs.size() +
    // " [number of unknown equivalence classes]");
    System.out.println("rulesWithWord length: " + rulesWithWord.length
                       + " [should be sum of words + unknown sigs]");
    int[] lengths = new int[STATS_BINS];
    ArrayList[] wArr = new ArrayList[STATS_BINS];
    for (int j = 0; j < STATS_BINS; j++) {
      wArr[j] = new ArrayList();
    }
    for (int i = 0; i < rulesWithWord.length; i++) {
      int num = rulesWithWord[i].size();
      if (num > STATS_BINS - 1) {
        num = STATS_BINS - 1;
      }
      lengths[num]++;
      if (wsize <= 20 || num >= STATS_BINS / 2) {
        wArr[num].add(wordNumberer().object(i));
      }
    }
    System.out.println("Stats on how many taggings for how many words");
    for (int j = 0; j < STATS_BINS; j++) {
      System.out.print(j + " taggings: " + lengths[j] + " words ");
      if (wsize <= 20 || j >= STATS_BINS / 2) {
        System.out.print(wArr[j]);
      }
      System.out.println();
    }
    NumberFormat nf = NumberFormat.getNumberInstance();
    nf.setMaximumFractionDigits(0);
    System.out.println("Unseen counter: " + unSeenCounter.toString(nf));
  }

  /**
   * Evaluates how many words (= terminals) in a collection of trees are
   * covered by the lexicon. First arg is the collection of trees; second
   * through fourth args get the results. Currently unused; this probably
   * only works if train and test at same time so tags and words variables
   * are initialized.
   */
  public double evaluateCoverage(Collection<Tree> trees, Set missingWords,
                                 Set missingTags, Set<IntTaggedWord> missingTW) {

    List<IntTaggedWord> iTW1 = new ArrayList<IntTaggedWord>();
    for (Tree t : trees) {
      iTW1.addAll(treeToEvents(t));
    }

    int total = 0;
    int unseen = 0;

    for (IntTaggedWord itw : iTW1) {
      total++;
      if (!words.contains(new IntTaggedWord(itw.word(), nullTag))) {
        missingWords.add(Numberer.object("word", itw.word()));
      }
      if (!tags.contains(new IntTaggedWord(nullWord, itw.tag()))) {
        missingTags.add(Numberer.object("tag", itw.tag()));
      }
      // if (!rules.contains(itw)) {
      if (seenCounter.getCount(itw) == 0.0) {
        unseen++;
        missingTW.add(itw);
      }
    }
    return (double) unseen / total;
  }

  private static final long serialVersionUID = 40L;

  int[] tagsToBaseTags = null;

  public int getBaseTag(int tag, TreebankLanguagePack tlp) {
    if (tagsToBaseTags == null) {
      populateTagsToBaseTags(tlp);
    }
    return tagsToBaseTags[tag];
  }

  private void populateTagsToBaseTags(TreebankLanguagePack tlp) {
    Numberer tagNumberer = tagNumberer();
    int total = tagNumberer.total();
    tagsToBaseTags = new int[total];
    for (int i = 0; i < total; i++) {
      String tag = (String) tagNumberer.object(i);
      String baseTag = tlp.basicCategory(tag);
      int j = tagNumberer.number(baseTag);
      tagsToBaseTags[i] = j;
    }
  }

  /** Provides some testing and opportunities for exploration of the
   *  probabilities of a BaseLexicon.  What's here currently probably
   *  only works for the English Penn Treeebank, as it uses default
   *  constructors.  Of the words given to test on,
   *  the first is treated as sentence initial, and the rest as not
   *  sentence initial.
   *
   *  @param args The command line arguments:
   *     java BaseLexicon treebankPath fileRange unknownWordModel words*
   */
  public static void main(String[] args) {
    if (args.length < 3) {
      System.err.println("java BaseLexicon treebankPath fileRange unknownWordModel words*");
      return;
    }
    System.out.print("Training BaseLexicon from " + args[0] + " " + args[1] + " ... ");
    Treebank tb = new DiskTreebank();
    tb.loadPath(args[0], new NumberRangesFileFilter(args[1], true));
    BaseLexicon lex = new BaseLexicon();
    lex.unknownLevel = Integer.parseInt(args[2]);
    lex.train(tb);
    System.out.println("done.");
    System.out.println();
    Numberer numb = Numberer.getGlobalNumberer("tags");
    Numberer wNumb = Numberer.getGlobalNumberer("words");
    NumberFormat nf = NumberFormat.getNumberInstance();
    nf.setMaximumFractionDigits(4);
    List<String> impos = new ArrayList<String>();
    for (int i = 3; i < args.length; i++) {
      if (lex.isKnown(args[i])) {
        System.out.println(args[i] + " is a known word.  Log probabilities [log P(w|t)] for its taggings are:");
        for (Iterator<IntTaggedWord> it = lex.ruleIteratorByWord(wNumb.number(args[i]), i - 3); it.hasNext(); ) {
          IntTaggedWord iTW = it.next();
          System.out.println(StringUtils.pad(iTW, 24) + nf.format(lex.score(iTW, i - 3)));
        }
      } else {
        String sig = lex.getSignature(args[i], i-3);
        System.out.println(args[i] + " is an unknown word.  Signature with uwm " + lex.unknownLevel + ((i == 3) ? " init": "non-init") + " is: " + sig);
        Set<String> tags = (Set<String>) numb.objects();
        impos.clear();
        List<String> lis = new ArrayList<String>(tags);
        Collections.sort(lis);
        for (String tStr : lis) {
          IntTaggedWord iTW = new IntTaggedWord(args[i], tStr);
          double score = lex.score(iTW, 1);
          if (score == Float.NEGATIVE_INFINITY) {
            impos.add(tStr);
          } else {
            System.out.println(StringUtils.pad(iTW, 24) + nf.format(score));
          }
        }
        if (impos.size() > 0) {
          System.out.println(args[i] + " impossible tags: " + impos);
        }
      }
      System.out.println();
    }
  }

  private transient Numberer tagNumberer;

  private Numberer tagNumberer() {
    if (tagNumberer == null) {
      tagNumberer = Numberer.getGlobalNumberer("tags");
    }
    return tagNumberer;
  }

  private transient Numberer wordNumberer;

  private Numberer wordNumberer() {
    if (wordNumberer == null) {
      wordNumberer = Numberer.getGlobalNumberer("words");
    }
    return wordNumberer;
  }


}
