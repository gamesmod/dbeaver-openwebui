package org.eclipse.jface.layout;
public class GridLayoutFactory { public static GridLayoutFactory swtDefaults(){return new GridLayoutFactory();} public static GridLayoutFactory fillDefaults(){return new GridLayoutFactory();}
 public GridLayoutFactory numColumns(int n){return this;} public Object create(){return null;} }
