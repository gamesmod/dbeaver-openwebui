package org.jkiss.utils;
public class CommonUtils {
  public static String toString(Object o){ return o==null?"":o.toString(); }
  public static String toString(Object o, String def){ return o==null?def:o.toString(); }
  public static boolean equalObjects(Object a, Object b){ return java.util.Objects.equals(a,b); }
  public static String getAllExceptionMessages(Throwable t){ return t==null?null:t.getMessage(); }
  public static boolean isEmpty(java.util.Collection<?> c){ return c==null||c.isEmpty(); }
  public static boolean isEmpty(String s){ return s==null||s.isEmpty(); }
}
