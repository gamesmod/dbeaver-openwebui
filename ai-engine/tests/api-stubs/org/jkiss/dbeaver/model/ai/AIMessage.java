package org.jkiss.dbeaver.model.ai;
public class AIMessage { private final AIMessageType role; private final String content; private final AIFunctionCall functionCall;
 public AIMessage(AIMessageType r, String c, AIFunctionCall fc){role=r;content=c;functionCall=fc;}
 public AIMessageType getRole(){return role;} public String getContent(){return content;} public AIFunctionCall getFunctionCall(){return functionCall;} }
