package com.orientechnologies.orient.core.sql.executor;

/**
 * Created by luigidellaquila on 08/08/16.
 */

import com.orientechnologies.orient.core.command.OCommandContext;

import java.util.Collections;
import java.util.List;

/**
 * @author Luigi Dell'Aquila (l.dellaquila-(at)-orientdb.com)
 */
public class OIfExecutionPlan implements OInternalExecutionPlan {

  private String location;

  private final OCommandContext ctx;

  protected IfStep step;

  public OIfExecutionPlan(OCommandContext ctx) {
    this.ctx = ctx;
  }

  @Override public void reset(OCommandContext ctx) {
    //TODO
    throw new UnsupportedOperationException();
  }

  @Override public void close() {
    step.close();
  }

  @Override public OTodoResultSet fetchNext(int n) {
    return step.syncPull(ctx, n);
  }

  @Override public String prettyPrint(int depth, int indent) {
    StringBuilder result = new StringBuilder();
    result.append(step.prettyPrint(depth, indent));
    return result.toString();
  }

  public void chain(IfStep step) {
    this.step = step;
  }

  @Override public List<OExecutionStep> getSteps() {
    //TODO do a copy of the steps
    return Collections.singletonList(step);
  }

  public void setSteps(List<OExecutionStepInternal> steps) {
    this.step = (IfStep) steps.get(0);
  }

  @Override public OResult toResult() {
    return new OResultInternal();
  }

  @Override public long getCost() {
    return 0l;
  }

  public boolean containsReturn() {
    return step.getPositivePlan().containsReturn() || step.getNegativePlan() != null && step.getPositivePlan().containsReturn();
  }

  public OExecutionStepInternal executeUntilReturn() {
    step.init(ctx);
    return step;
  }
}

