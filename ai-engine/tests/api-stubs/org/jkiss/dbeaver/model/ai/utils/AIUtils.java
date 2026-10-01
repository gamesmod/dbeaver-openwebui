package org.jkiss.dbeaver.model.ai.utils;
import org.jkiss.dbeaver.model.ai.AIConfigurationProfile;
public class AIUtils { public static final double DEFAULT_TEMPERATURE = 0.0;
 public static double normalizeTemperature(double t){ return Math.max(0, Math.min(2, t)); }
 public static String getSecretValueOrDefault(AIConfigurationProfile p, String id, String d) throws org.jkiss.dbeaver.DBException { String v=p.secrets.get(id); return v==null||v.isEmpty()?d:v; }
 public static void setSecretValue(AIConfigurationProfile p, String id, String v) throws org.jkiss.dbeaver.DBException { p.secrets.put(id,v);} 
 public static void deleteSecretValue(AIConfigurationProfile p, String id) throws org.jkiss.dbeaver.DBException { p.secrets.remove(id);} }
