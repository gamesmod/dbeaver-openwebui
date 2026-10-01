package org.jkiss.dbeaver.model.ai.registry;
/** Signatures of DBeaver 25.2.4 */
public class AIFunctionDescriptor {
    public static class Parameter {
        private final String name, type, description; private final String[] valid;
        public Parameter(String name, String type, String description, String[] valid){this.name=name;this.type=type;this.description=description;this.valid=valid;}
        public String getName(){return name;} public String getType(){return type;}
        public String getDescription(){return description;} public String[] getValidValues(){return valid;}
    }
    private final String id, description; private final Parameter[] parameters;
    public AIFunctionDescriptor(String id, String description, Parameter[] parameters){this.id=id;this.description=description;this.parameters=parameters;}
    public String getId(){return id;} public String getDescription(){return description;} public Parameter[] getParameters(){return parameters;}
}
