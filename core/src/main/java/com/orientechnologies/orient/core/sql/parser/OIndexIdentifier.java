/* Generated By:JJTree: Do not edit this line. OIndexIdentifier.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import java.util.Map;

public class OIndexIdentifier extends SimpleNode {

  public enum Type {
    INDEX, VALUES, VALUESASC, VALUESDESC
  }

  protected Type   type;
  protected String indexName;

  public OIndexIdentifier(int id) {
    super(id);
  }

  public OIndexIdentifier(OrientSql p, int id) {
    super(p, id);
  }

  /** Accept the visitor. **/
  public Object jjtAccept(OrientSqlVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  public void toString(Map<Object, Object> params, StringBuilder builder) {
    switch (type) {
    case INDEX:
      builder.append("INDEX");
      break;
    case VALUES:
      builder.append("INDEXVALUES");
      break;
    case VALUESASC:
      builder.append("INDEXVALUESASC");
      break;
    case VALUESDESC:
      builder.append("INDEXVALUESDESC");
      break;
    }
    builder.append(":");
    builder.append(indexName);
  }
}
/* JavaCC - OriginalChecksum=025f134fd4b27b84210738cdb6dd027c (do not edit this line) */