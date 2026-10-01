package org.eclipse.jface.layout; import org.eclipse.swt.layout.GridData;
public class GridDataFactory { public static GridDataFactory fillDefaults(){return new GridDataFactory();} public GridDataFactory grab(boolean h, boolean v){return this;}
 public GridDataFactory span(int h, int v){return this;} public GridDataFactory hint(int x, int y){return this;} public GridData create(){return new GridData();} }
