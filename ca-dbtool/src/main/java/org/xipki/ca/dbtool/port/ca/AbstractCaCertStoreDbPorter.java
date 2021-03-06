/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.dbtool.port.ca;

import java.util.concurrent.atomic.AtomicBoolean;

import org.xipki.ca.dbtool.port.DbPorter;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataSourceWrapper;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

class AbstractCaCertStoreDbPorter extends DbPorter {

  AbstractCaCertStoreDbPorter(DataSourceWrapper datasource, String baseDir, AtomicBoolean stopMe,
      boolean evaluateOnly) throws DataAccessException {
    super(datasource, baseDir, stopMe, evaluateOnly);
  }

}
