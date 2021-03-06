package edu.stanford.nlp.util;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Gives unique integer serial numbers to a family of objects, identified
 * by a name space.  A Numberer is like a collection of {@link Index}es,
 * and for
 * many purposes it is more straightforward to use an Index, but
 * Numberer can be useful precisely because it maintains a global name
 * space for numbered object families, and provides facilities for mapping
 * across numberings within that space.  At any rate, it's widely used in
 * some existing packages.
 * <p>
 * This implementation is not thread-safe and should be externally 
 * synchronised if a Numberer is being accessed from multiple threads.
 *
 * @author Dan Klein
 */
public class Numberer implements Serializable {

  private static Map<String,Numberer> numbererMap = new HashMap<String,Numberer>();
  // private boolean changed = false;
  private int total;
  private Map intToObject;
  private Map objectToInt;
  private MutableInteger tempInt;  // should really be transient
  private boolean locked; // = false;


  public static Map<String,Numberer> getNumberers() {
    return numbererMap;
  }

  /** You need to call this after deserializing Numberer objects to 
   *  restore the global namespace, since static objects aren't serialized.
   */
  public static void setNumberers(Map<String,Numberer> numbs) {
    numbererMap = numbs;
  }

  public static void setGlobalNumberer(String key, Numberer numb) {
    numbererMap.put(key, numb);
  }

  public static Numberer getGlobalNumberer(String type) {
    Numberer n = numbererMap.get(type);
    if (n == null) {
      n = new Numberer();
      numbererMap.put(type, n);
    }
    return n;
  }


  /** Get a number for an object in namespace type.
   *  This looks up the Numberer for <code>type</code> in the global
   *  namespace map (creating it if none previously existed), and then
   *  returns the appropriate number for the key.
   */
  public static int number(String type, Object o) {
    return getGlobalNumberer(type).number(o);
  }

  public static Object object(String type, int n) {
    return getGlobalNumberer(type).object(n);
  }


  /**
   * For an Object <i>o</i> that occurs in Numberers of type
   * <i>sourceType</i> and <i>targetType</i>, translates the serial
   * number <i>n</i> of <i>o</i> in the <i>sourceType</i> Numberer to
   * the serial number in the <i>targetType</i> Numberer.
   */
  public static int translate(String sourceType, String targetType, int n) {
    return getGlobalNumberer(targetType).number(getGlobalNumberer(sourceType).object(n));
  }


  public int total() {
    return total;
  }


  public void lock() {
    locked = true;
  }


  public void unlock() {
    locked = false;
  }


  public boolean hasSeen(Object o) {
    return objectToInt.keySet().contains(o);
  }

  public Set objects() {
    return objectToInt.keySet();
  }


  public int number(Object o) {
    MutableInteger i = (MutableInteger) objectToInt.get(o);
    if (i == null) {
      if (locked) {
        throw new NoSuchElementException("Numberer locked but trying to number unseen object " + o.toString());
      }
      i = new MutableInteger(total);
      total++;
      objectToInt.put(o, i);
      intToObject.put(i, o);
    }
    return i.intValue();
  }


  public Object object(int n) {
    tempInt.set(n);
    return intToObject.get(tempInt);
  }


  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < total; i++) {
      sb.append(i);
      sb.append("->");
      sb.append(object(i));
      if (i < total - 1) {
        sb.append(", ");
      }
    }
    sb.append("]");
    return sb.toString();
  }


  public Numberer() {
    // total = 0; // by default
    tempInt = new MutableInteger();
    intToObject = new HashMap();
    objectToInt = new HashMap();
  }


  /** Initialize a new Numberer with a copy of the contents of an
   *  existing Numberer.  The Numberer is a fully new object, but
   *  the objectss stored in the Numberer are the same.
   */
  public Numberer(Numberer numb) {
    // total = 0; // by default
    tempInt = new MutableInteger();
    intToObject = new HashMap(numb.total());
    objectToInt = new HashMap(numb.total());
    for (int i = 0; i < numb.total(); i++) {
      Object obj = numb.object(i);
      int x = number(obj);
      if (i != x) {
        throw new IllegalStateException("Something bung!\n");
      }
    }
  }


  private static final long serialVersionUID = 1L;

}
