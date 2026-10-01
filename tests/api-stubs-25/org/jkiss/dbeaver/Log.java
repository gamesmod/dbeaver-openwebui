package org.jkiss.dbeaver;
public class Log { private final String n; private Log(String n){this.n=n;}
 public static Log getLog(Class<?> c){return new Log(c.getSimpleName());}
 public static boolean ENABLED = Boolean.getBoolean("stub.log");
 public void debug(Object m){ if(ENABLED) System.err.println("[DEBUG "+n+"] "+m);} public void debug(Object m, Throwable t){debug(m+" "+t);}
 public void warn(Object m){System.err.println("[WARN "+n+"] "+m);} public void warn(Object m, Throwable t){warn(m+" "+t);}
 public void error(Object m){System.err.println("[ERROR "+n+"] "+m);} public void error(Object m, Throwable t){error(m+" "+t);} }
