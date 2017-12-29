// #THIRDPARTY# Spring Framework

/*
 * Copyright 2002-2006 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.datasource.springframework.jdbc;

import java.sql.SQLException;

import org.xipki.datasource.springframework.dao.InvalidDataAccessResourceUsageException;

/**
 *
 * Exception thrown when SQL specified is invalid. Such exceptions always have
 * a {@code java.sql.SQLException} root cause.
 *
 * <p>It would be possible to have subclasses for no such table, no such column etc.
 * A custom SQLExceptionTranslator could create such more specific exceptions,
 * without affecting code using this class.
 *
 * @author Rod Johnson
 * @see InvalidResultSetAccessException
 */
@SuppressWarnings("serial")
public class BadSqlGrammarException extends InvalidDataAccessResourceUsageException {

    private String sql;

    /**
     * Constructor for BadSqlGrammarException.
     * @param sql the offending SQL statement
     * @param ex the root cause
     */
    public BadSqlGrammarException(String sql, SQLException ex) {
        super("bad SQL grammar [" + sql + "]", ex);
        this.sql = sql;
    }

    /**
     * @return the wrapped SQLException.
     */
    public SQLException cause() {
        return (SQLException) getCause();
    }

    /**
     * @return the SQL that caused the problem.
     */
    public String sql() {
        return this.sql;
    }

}
