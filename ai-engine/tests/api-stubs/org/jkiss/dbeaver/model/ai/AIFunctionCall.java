package org.jkiss.dbeaver.model.ai; import java.util.*;
public class AIFunctionCall { private final UUID id=UUID.randomUUID(); private String functionName; private Map<String,Object> arguments; private Map<String,String> messageMetadata;
 public AIFunctionCall(String n, Map<String,Object> a, Map<String,String> m){functionName=n;arguments=a;messageMetadata=m;}
 public UUID getId(){return id;} public String getFunctionName(){return functionName;} public Map<String,Object> getArguments(){return arguments!=null?arguments:Map.of();}
 public Map<String,String> getMessageMetadata(){return messageMetadata;} public String toString(){return functionName+"("+arguments+") meta="+messageMetadata;} }
