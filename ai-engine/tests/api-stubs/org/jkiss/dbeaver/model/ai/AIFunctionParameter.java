package org.jkiss.dbeaver.model.ai; public interface AIFunctionParameter { String getName(); String getType(); String getDescription(); boolean isRequired(); String[] getValidValues(); }
