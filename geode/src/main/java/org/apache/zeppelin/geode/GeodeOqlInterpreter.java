/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.zeppelin.geode;

import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterPropertyBuilder;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterResult.Code;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.cache.query.SelectResults;
import com.gemstone.gemfire.cache.query.Struct;
import com.gemstone.gemfire.pdx.PdxInstance;

/**
 * Apache Geode OQL Interpreter (http://geode.incubator.apache.org)
 * 
 * <ul>
 * <li>{@code geode.locator.host} - The Geode Locator {@code <HOST>} to connect to.</li>
 * <li>{@code geode.locator.port} - The Geode Locator {@code <PORT>} to connect to.</li>
 * </ul>
 * <p>
 * Sample usage: <br/>
 * {@code %geode.oql} <br/>
 * {@code SELECT * FROM /regionEmployee e WHERE e.companyId > 95}
 * </p>
 * 
 * The OQL spec and sample queries:
 * http://geode-docs.cfapps.io/docs/getting_started/querying_quick_reference.html
 * 
 * <p>
 * Known issue:http://gemfire.docs.pivotal.io/bugnotes/KnownIssuesGemFire810.html #43673 Using query
 * "select * from /exampleRegion.entrySet" fails in a client-server topology and/or in a
 * PartitionedRegion.
 * </p>
 */
public class GeodeOqlInterpreter extends Interpreter {

  private static final String DEFAULT_PORT = "10334";
  private static final String DEFAULT_HOST = "localhost";

  private static final char NEWLINE = '\n';
  private static final char TAB = '\t';
  private static final char WHITESPACE = ' ';

  Logger logger = LoggerFactory.getLogger(GeodeOqlInterpreter.class);

  private static final String TABLE_MAGIC_TAG = "%table ";

  public static final String LOCATOR_HOST = "geode.locator.host";
  public static final String LOCATOR_PORT = "geode.locator.port";

  static {
    Interpreter.register("oql", "geode", GeodeOqlInterpreter.class.getName(),
        new InterpreterPropertyBuilder().add(LOCATOR_HOST, DEFAULT_HOST, "The Geode Locator Host.")
            .add(LOCATOR_PORT, DEFAULT_PORT, "The Geode Locator Port").build());
  }

  private ClientCache clientCache = null;
  private QueryService queryService = null;
  private Exception exceptionOnConnect;

  public GeodeOqlInterpreter(Properties property) {
    super(property);
  }

  protected ClientCache getClientCache() {

    String locatorHost = getProperty(LOCATOR_HOST);
    int locatorPort = Integer.valueOf(getProperty(LOCATOR_PORT));

    ClientCache clientCache =
        new ClientCacheFactory().addPoolLocator(locatorHost, locatorPort).create();

    return clientCache;
  }

  @Override
  public void open() {
    logger.info("Geode open connection called!");
    try {
      clientCache = getClientCache();
      queryService = clientCache.getQueryService();
      exceptionOnConnect = null;
      logger.info("Successfully created Geode connection");
    } catch (Exception e) {
      logger.error("Cannot open connection", e);
      exceptionOnConnect = e;
    }
  }

  @Override
  public void close() {
    try {
      if (clientCache != null) {
        clientCache.close();
      }

      if (queryService != null) {
        queryService.closeCqs();
      }

    } catch (Exception e) {
      logger.error("Cannot close connection", e);
    } finally {
      clientCache = null;
      queryService = null;
      exceptionOnConnect = null;
    }
  }

  private InterpreterResult executeOql(String oql) {
    try {

      if (getExceptionOnConnect() != null) {
        return new InterpreterResult(Code.ERROR, getExceptionOnConnect().getMessage());
      }

      @SuppressWarnings("unchecked")
      SelectResults<Object> results =
          (SelectResults<Object>) getQueryService().newQuery(oql).execute();

      StringBuilder msg = new StringBuilder(TABLE_MAGIC_TAG);
      boolean isTableHeaderSet = false;

      Iterator<Object> iterator = results.iterator();
      while (iterator.hasNext()) {

        Object entry = iterator.next();

        if (entry instanceof Number) {
          handleNumberEntry(isTableHeaderSet, entry, msg);
        } else if (entry instanceof Struct) {
          handleStructEntry(isTableHeaderSet, entry, msg);
        } else if (entry instanceof PdxInstance) {
          handlePdxInstanceEntry(isTableHeaderSet, entry, msg);
        } else {
          handleUnsupportedTypeEntry(isTableHeaderSet, entry, msg);
        }

        isTableHeaderSet = true;
        msg.append(NEWLINE);
      }

      return new InterpreterResult(Code.SUCCESS, msg.toString());

    } catch (Exception ex) {
      logger.error("Cannot run " + oql, ex);
      return new InterpreterResult(Code.ERROR, ex.getMessage());
    }
  }

  /**
   * For %table response replace Tab and Newline characters from the content.
   */
  private String replaceReservedChars(String str) {

    if (StringUtils.isBlank(str)) {
      return str;
    }

    return str.replace(TAB, WHITESPACE).replace(NEWLINE, WHITESPACE);
  }

  private void handleStructEntry(boolean isHeaderSet, Object entry, StringBuilder msg) {
    Struct struct = (Struct) entry;
    if (!isHeaderSet) {
      for (String titleName : struct.getStructType().getFieldNames()) {
        msg.append(replaceReservedChars(titleName)).append(TAB);
      }
      msg.append(NEWLINE);
    }

    for (String titleName : struct.getStructType().getFieldNames()) {
      msg.append(replaceReservedChars("" + struct.get(titleName))).append(TAB);
    }
  }

  private void handlePdxInstanceEntry(boolean isHeaderSet, Object entry, StringBuilder msg) {
    PdxInstance pdxEntry = (PdxInstance) entry;
    if (!isHeaderSet) {
      for (String titleName : pdxEntry.getFieldNames()) {
        msg.append(replaceReservedChars(titleName)).append(TAB);
      }
      msg.append(NEWLINE);
    }

    for (String titleName : pdxEntry.getFieldNames()) {
      msg.append(replaceReservedChars("" + pdxEntry.getField(titleName))).append(TAB);
    }
  }

  private void handleNumberEntry(boolean isHeaderSet, Object entry, StringBuilder msg) {
    if (!isHeaderSet) {
      msg.append("Result").append(NEWLINE);
    }
    msg.append((Number) entry);
  }

  private void handleUnsupportedTypeEntry(boolean isHeaderSet, Object entry, StringBuilder msg) {
    if (!isHeaderSet) {
      msg.append("Unsuppoted Type").append(NEWLINE);
    }
    msg.append("" + entry);
  }


  @Override
  public InterpreterResult interpret(String cmd, InterpreterContext contextInterpreter) {
    logger.info("Run OQL command '{}'", cmd);
    return executeOql(cmd);
  }

  @Override
  public void cancel(InterpreterContext context) {
    // Do nothing
  }

  @Override
  public FormType getFormType() {
    return FormType.SIMPLE;
  }

  @Override
  public int getProgress(InterpreterContext context) {
    return 0;
  }

  @Override
  public Scheduler getScheduler() {
    return SchedulerFactory.singleton().createOrGetFIFOScheduler(
        GeodeOqlInterpreter.class.getName() + this.hashCode());
  }

  @Override
  public List<String> completion(String buf, int cursor) {
    return null;
  }

  // Test only
  QueryService getQueryService() {
    return this.queryService;
  }

  Exception getExceptionOnConnect() {
    return this.exceptionOnConnect;
  }
}
