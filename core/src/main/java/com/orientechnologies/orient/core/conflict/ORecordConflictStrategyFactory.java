/*
 * Copyright 2010-2014 OrientDB LTD (info(-at-)orientdb.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.conflict;

import com.orientechnologies.common.factory.OConfigurableStatelessFactory;

/**
 * Factory to manage the record conflict strategy implementations.
 * 
 * @author Luca Garulli (l.garulli--(at)--orientdb.com)
 */
public class ORecordConflictStrategyFactory extends OConfigurableStatelessFactory<String, ORecordConflictStrategy> {
  public ORecordConflictStrategyFactory() {
    final OVersionRecordConflictStrategy def = new OVersionRecordConflictStrategy();

    registerImplementation(OVersionRecordConflictStrategy.NAME, def);
    registerImplementation(OAutoMergeRecordConflictStrategy.NAME, new OAutoMergeRecordConflictStrategy());
    registerImplementation(OContentRecordConflictStrategy.NAME, new OContentRecordConflictStrategy());

    setDefaultImplementation(def);
  }

  public ORecordConflictStrategy getStrategy(final String iStrategy) {
    return getImplementation(iStrategy);
  }

  public String getDefaultStrategy() {
    return OVersionRecordConflictStrategy.NAME;
  }
}
