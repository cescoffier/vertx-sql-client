/*
 * Copyright (c) 2011-2021 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.oracle.impl.commands;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.oracle.OracleConnectOptions;
import io.vertx.sqlclient.impl.PreparedStatement;
import oracle.jdbc.OracleConnection;

import java.sql.SQLException;
import java.sql.Statement;

public class PrepareStatementCommand extends AbstractCommand<PreparedStatement> {

  private final String sql;

  public PrepareStatementCommand(OracleConnectOptions options, String sql) {
    super(options);
    this.sql = sql;
  }

  @Override
  public Future<PreparedStatement> execute(OracleConnection conn, Context context) {
    boolean autoGeneratedKeys = options == null || options.isAutoGeneratedKeys();
    boolean autoGeneratedIndexes = options != null && options.getAutoGeneratedKeysIndexes() != null;

    if (autoGeneratedKeys && !autoGeneratedIndexes) {
      return prepareReturningKey(conn);
    } else if (autoGeneratedIndexes) {
      return prepareWithAutoGeneratedIndexes(conn, context);
    } else {
      return prepare(conn, context);
    }
  }

  private Future<PreparedStatement> prepareWithAutoGeneratedIndexes(OracleConnection conn, Context context) {
    return context.owner().executeBlocking(p -> {
      // convert json array to int or string array
      JsonArray indexes = options.getAutoGeneratedKeysIndexes();
      try {
        if (indexes.getValue(0) instanceof Number) {
          int[] keys = new int[indexes.size()];
          for (int i = 0; i < keys.length; i++) {
            keys[i] = indexes.getInteger(i);
          }
          OraclePreparedStatement statement = create(conn.prepareStatement(sql, keys));
          p.complete(statement);
        } else if (indexes.getValue(0) instanceof String) {
          String[] keys = new String[indexes.size()];
          for (int i = 0; i < keys.length; i++) {
            keys[i] = indexes.getString(i);
          }
          OraclePreparedStatement statement = create(conn.prepareStatement(sql, keys));
          p.complete(statement);
        } else {
          p.fail(new SQLException("Invalid type of index, only [int, String] allowed"));
        }
      } catch (RuntimeException | SQLException e) {
        p.fail(e);
      }
    });
  }

  private Future<PreparedStatement> prepareReturningKey(OracleConnection connection) {
    try {
      java.sql.PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
      applyStatementOptions(ps);
      return Future.succeededFuture(new OraclePreparedStatement(sql, ps));
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  private Future<PreparedStatement> prepare(OracleConnection connection, Context context) {
    return context.owner().executeBlocking(p -> {
      try {
        OraclePreparedStatement result = create(connection.prepareStatement(sql));
        p.complete(result);
      } catch (Exception e) {
        p.fail(e);
      }
    });
  }

  private OraclePreparedStatement create(java.sql.PreparedStatement statement) throws SQLException {
    applyStatementOptions(statement);
    return new OraclePreparedStatement(sql, statement);
  }
}
