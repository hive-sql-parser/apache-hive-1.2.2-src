/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.processors;

import static org.apache.commons.lang.StringUtils.isBlank;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.metadata.*;
import org.apache.hadoop.hive.ql.session.SessionState;

/**
 * CommandProcessorFactory.
 *
 */
public final class CommandProcessorFactory {

  private CommandProcessorFactory() {
    // prevent instantiation
  }

  private static final Map<HiveConf, Driver> mapDrivers = Collections.synchronizedMap(new HashMap<HiveConf, Driver>());

  public static CommandProcessor get(String cmd)
      throws SQLException {
    return get(new String[]{cmd}, null);
  }

  public static CommandProcessor getForHiveCommand(String[] cmd, HiveConf conf)
    throws SQLException {
    return getForHiveCommandInternal(cmd, conf, false);
  }

  /**
   * 其中getForHiveCommand函数首先根据tokens的第一个字串，也就是用户输入指令的第一个单词，
   * 在HiveCommand这个enum中定义的一些非SQL查询操作集合中进行匹配，确定相应的HiveCommand类型。
   * 在依据HiveCommand选择合适的CommandProcessor实现方式，
   * 比如dfs命令对应的DFSProcessor，set命令对应的SetProcessor等，
   * 如果用户输入的是诸如select之类的SQL查询， getForHiveCommand返回null，
   * 直接在get函数中根据配置文件conf选择或者生成一个Driver类实例，并作为CommandProcessor返回。
   * 详细的代码参考CommandProcessorFactory和HiveCommand类。
   * @param cmd
   * @param conf
   * @param testOnly
   * @return
   * @throws SQLException
   */
  public static CommandProcessor getForHiveCommandInternal(String[] cmd, HiveConf conf,
                                                           boolean testOnly)
    throws SQLException {
    HiveCommand hiveCommand = HiveCommand.find(cmd, testOnly);
    if (hiveCommand == null || isBlank(cmd[0])) {
      return null;
    }
    if (conf == null) {
      conf = new HiveConf();
    }
    Set<String> availableCommands = new HashSet<String>();
    for (String availableCommand : conf.getVar(HiveConf.ConfVars.HIVE_SECURITY_COMMAND_WHITELIST)
      .split(",")) {
      availableCommands.add(availableCommand.toLowerCase().trim());
    }
    if (!availableCommands.contains(cmd[0].trim().toLowerCase())) {
      throw new SQLException("Insufficient privileges to execute " + cmd[0], "42000");
    }
    switch (hiveCommand) {
      case SET:
        return new SetProcessor();
      case RESET:
        return new ResetProcessor();
      case DFS:
        SessionState ss = SessionState.get();
        return new DfsProcessor(ss.getConf());
      case ADD:
        return new AddResourceProcessor();
      case LIST:
        return new ListResourceProcessor();
      case DELETE:
        return new DeleteResourceProcessor();
      case COMPILE:
        return new CompileProcessor();
      case RELOAD:
        return new ReloadProcessor();
      case CRYPTO:
        try {
          return new CryptoProcessor(SessionState.get().getHdfsEncryptionShim(), conf);
        } catch (HiveException e) {
          throw new SQLException("Fail to start the command processor due to the exception: ", e);
        }
      default:
        throw new AssertionError("Unknown HiveCommand " + hiveCommand);
    }
  }

  /**
   * CommandProcessorFactory根据用户指令生成的tokens和配置文件，
   * 返回CommandProcessor的一个具体实现。
   * @param cmd
   * @param conf
   * @return
   * @throws SQLException
   */
  public static CommandProcessor get(String[] cmd, HiveConf conf)
      throws SQLException {
    CommandProcessor result = getForHiveCommand(cmd, conf);
    if (result != null) {
      return result;
    }
    if (isBlank(cmd[0])) {
      return null;
    } else {
      if (conf == null) {
        return new Driver();
      }
      Driver drv = mapDrivers.get(conf);
      if (drv == null) {
        drv = new Driver();
        mapDrivers.put(conf, drv);
      }
      drv.init();
      return drv;
    }
  }

  public static void clean(HiveConf conf) {
    Driver drv = mapDrivers.get(conf);
    if (drv != null) {
      drv.destroy();
    }

    mapDrivers.remove(conf);
  }
}
