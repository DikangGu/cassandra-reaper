/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spotify.reaper.cassandra;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;

import com.spotify.reaper.ReaperException;
import com.spotify.reaper.service.RingRange;

import org.apache.cassandra.db.ColumnFamilyStoreMBean;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.compaction.CompactionManagerMBean;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.management.InstanceNotFoundException;
import javax.management.JMX;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import static com.google.common.base.Preconditions.checkNotNull;

public class JmxProxy implements NotificationListener, Serializable, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(JmxProxy.class);

  private static final int JMX_PORT = 7199;
  private static final String JMX_URL = "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi";
  private static final String SS_OBJECT_NAME = "org.apache.cassandra.db:type=StorageService";

  private final JMXConnector jmxConnector;
  private final ObjectName ssMbeanName;
  private final MBeanServerConnection mbeanServer;
  private final StorageServiceMBean ssProxy;
  private final Optional<RepairStatusHandler> repairStatusHandler;
  private final String host;
  private final CompactionManagerMBean cmProxy;

  private JmxProxy(Optional<RepairStatusHandler> handler, String host, JMXConnector jmxConnector,
      StorageServiceMBean ssProxy, ObjectName ssMbeanName, MBeanServerConnection mbeanServer,
      CompactionManagerMBean cmProxy) {
    this.host = host;
    this.jmxConnector = jmxConnector;
    this.ssMbeanName = ssMbeanName;
    this.mbeanServer = mbeanServer;
    this.ssProxy = ssProxy;
    this.repairStatusHandler = handler;
    this.cmProxy = cmProxy;
  }

  /**
   * Connect to JMX interface on the given host and default JMX port without RepairStatusHandler.
   */
  public static JmxProxy connect(String host) throws ReaperException {
    return connect(Optional.<RepairStatusHandler>absent(), host);
  }

  /**
   * Connect to JMX interface on the given host and default JMX port.
   *
   * @param handler Implementation of {@link RepairStatusHandler} to process incoming notifications
   *                of repair events.
   */
  public static JmxProxy connect(Optional<RepairStatusHandler> handler, String host)
      throws ReaperException {
    String[] parts = host.split(":");
    if (parts.length == 2) {
      return connect(handler, parts[0], Integer.valueOf(parts[1]));
    } else {
      return connect(handler, host, JMX_PORT);
    }
  }

  /**
   * Connect to JMX interface on the given host and port.
   *
   * @param handler Implementation of {@link RepairStatusHandler} to process incoming notifications
   *                of repair events.
   */
  public static JmxProxy connect(Optional<RepairStatusHandler> handler, String host, int port)
      throws ReaperException {
    JMXServiceURL jmxUrl;
    ObjectName ssMbeanName;
    ObjectName cmMbeanName;
    try {
      jmxUrl = new JMXServiceURL(String.format(JMX_URL, host, port));
      ssMbeanName = new ObjectName(SS_OBJECT_NAME);
      cmMbeanName = new ObjectName(CompactionManager.MBEAN_OBJECT_NAME);
    } catch (MalformedURLException | MalformedObjectNameException e) {
      LOG.error("Failed to prepare the JMX connection");
      throw new ReaperException("Failure during preparations for JMX connection", e);
    }
    try {
      JMXConnector jmxConn = JMXConnectorFactory.connect(jmxUrl);
      MBeanServerConnection mbeanServerConn = jmxConn.getMBeanServerConnection();
      StorageServiceMBean ssProxy =
          JMX.newMBeanProxy(mbeanServerConn, ssMbeanName, StorageServiceMBean.class);
      CompactionManagerMBean cmProxy =
          JMX.newMBeanProxy(mbeanServerConn, cmMbeanName, CompactionManagerMBean.class);
      JmxProxy proxy =
          new JmxProxy(handler, host, jmxConn, ssProxy, ssMbeanName, mbeanServerConn, cmProxy);
      // registering a listener throws bunch of exceptions, so we do it here rather than in the
      // constructor
      mbeanServerConn.addNotificationListener(ssMbeanName, proxy, null, null);
      LOG.info(String.format("JMX connection to %s properly connected.", host));
      return proxy;
    } catch (IOException | InstanceNotFoundException e) {
      LOG.error("Failed to establish JMX connection");
      throw new ReaperException("Failure when establishing JMX connection", e);
    }
  }

  /**
   * @return list of tokens in the cluster
   */
  public List<BigInteger> getTokens() {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    return Lists.transform(
        Lists.newArrayList(ssProxy.getTokenToEndpointMap().keySet()),
        new Function<String, BigInteger>() {
          @Override
          public BigInteger apply(String s) {
            return new BigInteger(s);
          }
        });
  }

  /**
   * @return all hosts owning a range of tokens
   */
  @Nullable
  public List<String> tokenRangeToEndpoint(String keyspace, RingRange tokenRange) {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    Set<Map.Entry<List<String>, List<String>>> entries =
        ssProxy.getRangeToEndpointMap(keyspace).entrySet();
    for (Map.Entry<List<String>, List<String>> entry : entries) {
      BigInteger rangeStart = new BigInteger(entry.getKey().get(0));
      BigInteger rangeEnd = new BigInteger(entry.getKey().get(1));
      if (new RingRange(rangeStart, rangeEnd).encloses(tokenRange)) {
        return entry.getValue();
      }
    }
    return null;
  }

  /**
   * @return full class name of Cassandra's partitioner.
   */
  public String getPartitioner() {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    return ssProxy.getPartitionerName();
  }

  public static String toSymbolicName(String s) {
    return s.toLowerCase().replaceAll("[^a-z0-9_]", "");
  }

  /**
   * @return Cassandra cluster name.
   */
  public String getClusterName() {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    return toSymbolicName(ssProxy.getClusterName());
  }

  /**
   * @return list of available keyspaces
   */
  public List<String> getKeyspaces() {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    return ssProxy.getKeyspaces();
  }

  /**
   * @return number of pending compactions on the node this proxy is connected to
   */
  public int getPendingCompactions() {
    checkNotNull(cmProxy, "Looks like the proxy is not connected");
    return cmProxy.getPendingTasks();
  }

  /**
   * Terminates all ongoing repairs on the node this proxy is connected to
   */
  public void cancelAllRepairs() {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    ssProxy.forceTerminateAllRepairSessions();
  }

  /**
   * Checks if table exists in the cluster by instantiating a MBean for that table.
   *
   * @throws ReaperException if the query fails, not when the table doesn't exist
   */
  public boolean tableExists(String ks, String cf) throws ReaperException {
    try {
      String type = cf.contains(".") ? "IndexColumnFamilies" : "ColumnFamilies";
      String nameStr = String.format("org.apache.cassandra.db:type=*%s,keyspace=%s,columnfamily=%s",
          type, ks, cf);
      Set<ObjectName> beans = mbeanServer.queryNames(new ObjectName(nameStr), null);
      if (beans.isEmpty() || beans.size() != 1) {
        return false;
      }
      ObjectName bean = beans.iterator().next();
      JMX.newMBeanProxy(mbeanServer, bean, ColumnFamilyStoreMBean.class);
    } catch (MalformedObjectNameException | IOException e) {
      String errMsg = String.format("ColumnFamilyStore for %s/%s not found: %s", ks, cf,
          e.getMessage());
      LOG.warn(errMsg);
      return false;
    }
    return true;
  }

  /**
   * Triggers a repair of range (beginToken, endToken] for given keyspace and column family.
   *
   * The repair is triggered by {@link org.apache.cassandra.service.StorageServiceMBean#forceRepairRangeAsync}
   * For time being, we don't allow local nor snapshot repairs.
   *
   * @return Repair command number, or 0 if nothing to repair
   */
  public int triggerRepair(BigInteger beginToken, BigInteger endToken, String keyspace,
      String columnFamily) {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    String msg = String.format("Triggering repair of range (%s,%s] for %s.%s via host %s",
        beginToken.toString(), endToken.toString(), keyspace, columnFamily, this.host);
    LOG.info(msg);
    return ssProxy.forceRepairRangeAsync(
        beginToken.toString(),
        endToken.toString(),
        keyspace,
        false,                      // isSequential - if true, do "snapshot repairs"
        false,                      // isLocal - if false, repair all DCs
        columnFamily);
  }

  /**
   * Invoked when the MBean this class listens to publishes an event.
   *
   * We're only interested in repair-related events. Their format is explained at {@link
   * org.apache.cassandra.service.StorageServiceMBean#forceRepairAsync} The format is: notification
   * type: "repair" notification userData: int array of length 2 where [0] = command number [1] =
   * ordinal of AntiEntropyService.Status
   */
  @Override
  public void handleNotification(Notification notification, Object handback) {
    // we're interested in "repair"
    String type = notification.getType();
    LOG.debug(String.format("Received notification: %s", notification.toString()));
    if (repairStatusHandler.isPresent() && type.equals("repair")) {
      int[] data = (int[]) notification.getUserData();
      // get the repair sequence number
      int repairNo = data[0];
      // get the repair status
      ActiveRepairService.Status status = ActiveRepairService.Status.values()[data[1]];
      // this is some text message like "Starting repair...", "Finished repair...", etc.
      String message = notification.getMessage();
      // let the handler process the event
      repairStatusHandler.get().handle(repairNo, status, message);
    }
  }

  public String getConnectionId() throws IOException {
    return jmxConnector.getConnectionId();
  }

  public boolean isConnectionAlive() {
    try {
      String connectionId = getConnectionId();
      return null != connectionId && connectionId.length() > 0;
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  /**
   * Cleanly shut down by un-registering the listener and closing the JMX connection.
   */
  @Override
  public void close() throws ReaperException {
    try {
      mbeanServer.removeNotificationListener(ssMbeanName, this);
      jmxConnector.close();
    } catch (IOException | InstanceNotFoundException | ListenerNotFoundException e) {
      throw new ReaperException(e);
    }
  }
}
