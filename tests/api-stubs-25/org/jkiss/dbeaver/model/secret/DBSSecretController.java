package org.jkiss.dbeaver.model.secret;
public interface DBSSecretController {
    java.util.Map<String,String> STORE = new java.util.HashMap<>();
    static DBSSecretController getGlobalSecretController() throws org.jkiss.dbeaver.DBException {
        return new DBSSecretController() {};
    }
    default String getPrivateSecretValue(String id) throws org.jkiss.dbeaver.DBException { return STORE.get(id); }
    default void setPrivateSecretValue(String id, String v) throws org.jkiss.dbeaver.DBException { STORE.put(id, v); }
}
