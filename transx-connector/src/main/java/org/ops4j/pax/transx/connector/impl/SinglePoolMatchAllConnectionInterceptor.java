/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.ops4j.pax.transx.connector.impl;

import javax.resource.ResourceException;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * This pool is the most spec-compliant pool.  It can be used by itself with no partitioning.
 * It is apt to be the slowest pool.
 * For each connection request, it synchronizes access to the pool and asks the
 * ManagedConnectionFactory for a match from among all managed connections.  If none is found,
 * it may discard a random existing connection, and creates a new connection.
 */
public class SinglePoolMatchAllConnectionInterceptor extends AbstractSinglePoolConnectionInterceptor {

    private final Map<ManagedConnection, ManagedConnectionInfo> pool;

    public SinglePoolMatchAllConnectionInterceptor(ConnectionInterceptor next,
                                                   int maxSize,
                                                   int minSize,
                                                   Duration blockingTimeout,
                                                   Duration idleTimeout,
                                                   boolean backgroundValidation,
                                                   Duration validatingPeriod,
                                                   boolean validateOnMatch) {

        super(next, maxSize, minSize, blockingTimeout, idleTimeout, backgroundValidation, validatingPeriod, validateOnMatch);
        pool = new IdentityHashMap<>(maxSize);
    }

    protected void internalGetConnection(ConnectionInfo connectionInfo) throws ResourceException {
        synchronized (pool) {
            if (destroyed) {
                throw new ResourceException("ManagedConnection pool has been destroyed");
            }
            if (!pool.isEmpty()) {
                ManagedConnectionInfo mci = connectionInfo.getManagedConnectionInfo();
                ManagedConnectionFactory managedConnectionFactory = mci.getManagedConnectionFactory();
                ManagedConnection matchedMC =
                        managedConnectionFactory
                                .matchManagedConnections(pool.keySet(), mci.getSubject(), mci.getConnectionRequestInfo());
                if (matchedMC != null) {
                    connectionInfo.setManagedConnectionInfo(pool.get(matchedMC));
                    pool.remove(matchedMC);
                    if (LOG.isLoggable(Level.FINEST)) {
                        LOG.log(Level.FINEST, "Supplying existing connection from pool " + this + " " + connectionInfo);
                    }
                    if (connectionCount < minSize) {
                        timer.schedule(new FillTask(connectionInfo), 10);
                    }
                    return;
                }
            }
            //matching failed or pool is empty
            //if pool is at maximum size, pick a cx to kill
            if (connectionCount == maxSize) {
                LOG.log(Level.FINEST, "Pool is at max size but no connections match, picking one to destroy");
                Iterator iterator = pool.entrySet().iterator();
                ManagedConnectionInfo kill = (ManagedConnectionInfo) ((Map.Entry) iterator.next()).getValue();
                iterator.remove();
                ConnectionInfo killInfo = new ConnectionInfo(kill);
                internalReturn(killInfo, ConnectionReturnAction.DESTROY);
            }
            next.getConnection(connectionInfo);
            connectionCount++;
            if (LOG.isLoggable(Level.FINEST)) {
                LOG.log(Level.FINEST, "Supplying new connection from pool " + this + " " + connectionInfo);
            }
            if (connectionCount < minSize) {
                timer.schedule(new FillTask(connectionInfo), 10);
            }

        }
    }

    protected void doAdd(ManagedConnectionInfo mci) {
        pool.put(mci.getManagedConnection(), mci);
    }

    protected Object getPool() {
        return pool;
    }

    protected boolean doRemove(ManagedConnectionInfo mci) {
        return pool.remove(mci.getManagedConnection()) == null;
    }

    protected void internalDestroy() {
        synchronized (pool) {
            for (ManagedConnection managedConnection : pool.keySet()) {
                try {
                    managedConnection.destroy();
                } catch (ResourceException ignore) {
                }
            }
            pool.clear();
        }
    }

    public int getIdleConnectionCount() {
        synchronized (pool) {
            return pool.size();
        }
    }

    protected void transferConnections(int maxSize, int shrinkNow) {
        List<ConnectionInfo> killList = new ArrayList<>(shrinkNow);
        Iterator<Map.Entry<ManagedConnection, ManagedConnectionInfo>> it = pool.entrySet().iterator();
        for (int i = 0; i < shrinkNow; i++) {
            killList.add(new ConnectionInfo(it.next().getValue()));
        }
        for (ConnectionInfo killInfo: killList) {
            internalReturn(killInfo, ConnectionReturnAction.DESTROY);
        }
    }

    protected void getExpiredManagedConnectionInfos(Instant threshold, List<ManagedConnectionInfo> killList) {
        synchronized (pool) {
            for (Iterator<Map.Entry<ManagedConnection, ManagedConnectionInfo>> mcis = pool.entrySet().iterator(); mcis.hasNext(); ) {
                ManagedConnectionInfo mci = mcis.next().getValue();
                if (mci.getLastUsed().isBefore(threshold)) {
                    mcis.remove();
                    killList.add(mci);
                    connectionCount--;
                }
            }
        }
    }

    @Override
    void getManagedConnectionInfos(List<ManagedConnectionInfo> mcis) {
        synchronized (pool) {
            mcis.addAll(pool.values());
        }
    }

    public void info(StringBuilder s) {
        s.append(getClass().getName());
        s.append("[minSize=").append(minSize);
        s.append(",maxSize=").append(maxSize);
        s.append(",idleTimeout=").append(idleTimeout);
        s.append(",blockingTimeout=").append(blockingTimeout).append("]\n");
        next.info(s);
    }

}
