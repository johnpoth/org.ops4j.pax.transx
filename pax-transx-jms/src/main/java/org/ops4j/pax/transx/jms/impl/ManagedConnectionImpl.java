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
 *
 */
package org.ops4j.pax.transx.jms.impl;

import org.ops4j.pax.transx.connection.utils.CredentialExtractor;

import javax.jms.Connection;
import javax.jms.ConnectionMetaData;
import javax.jms.JMSException;
import javax.jms.ResourceAllocationException;
import javax.jms.Session;
import javax.jms.XAConnection;
import javax.jms.XASession;
import javax.resource.ResourceException;
import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.IllegalStateException;
import javax.resource.spi.LocalTransaction;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionMetaData;
import javax.resource.spi.SecurityException;
import javax.security.auth.Subject;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.ops4j.pax.transx.jms.impl.Utils.doClose;
import static org.ops4j.pax.transx.jms.impl.Utils.trace;

public class ManagedConnectionImpl implements ManagedConnection {

    private final ManagedConnectionFactoryImpl mcf;
    private final ConnectionRequestInfoImpl cri;
    private final CredentialExtractor credentialExtractor;
    private final Set<SessionImpl> handles = Collections.synchronizedSet(new HashSet<>());
    private final List<ConnectionEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private ReentrantLock lock = new ReentrantLock();

    private final XAConnection xaConnection;
    private final XASession xaSession;
    private final Connection connection;
    private final Session session;

    private XAResource xaResource;
    private boolean inManagedTx;

    public ManagedConnectionImpl(ManagedConnectionFactoryImpl mcf,
                                 Subject subject,
                                 ConnectionRequestInfoImpl cri) throws ResourceException {
        this.mcf = mcf;
        this.cri = cri;
        this.credentialExtractor = new CredentialExtractor(subject, cri, mcf);

        try {
            boolean transacted = cri != null && cri.isTransacted();
            int acknowledgeMode = Session.AUTO_ACKNOWLEDGE;
            String userName = credentialExtractor.getUserName();
            String password = credentialExtractor.getPassword();
            if (userName != null && password != null) {
                connection = mcf.getConnectionFactory().createConnection(userName, password);
                xaConnection = mcf.getXaConnectionFactory().createXAConnection(userName, password);
            } else {
                connection = mcf.getConnectionFactory().createConnection();
                xaConnection = mcf.getXaConnectionFactory().createXAConnection();
            }
            connection.setExceptionListener(this::onException);
            xaConnection.setExceptionListener(this::onException);
            xaSession = xaConnection.createXASession();
            session = connection.createSession(transacted, acknowledgeMode);
        } catch (JMSException e) {
            throw new ResourceException(e.getMessage(), e);
        }
    }

    ConnectionMetaData getConnectionMetaData() throws JMSException {
        return connection.getMetaData();
    }

    CredentialExtractor getCredentialExtractor() {
        return credentialExtractor;
    }

    private void onException(final JMSException exception) {
        if (isDestroyed.get()) {
            return;
        }
        safe(() -> connection.setExceptionListener(null), "Unable to unset exception listener");
        safe(() -> xaConnection.setExceptionListener(null), "Unable to unset exception listener");
        ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.CONNECTION_ERROR_OCCURRED, exception);
        sendEvent(event);
    }

    void lock() {
        lock.lock();
    }

    void tryLock() throws ResourceAllocationException {
        Integer tryLock = mcf.getUseTryLock();
        if (tryLock == null || tryLock <= 0) {
            lock();
            return;
        }
        try {
            if (!lock.tryLock(tryLock, TimeUnit.SECONDS)) {
                throw new ResourceAllocationException("Unable to obtain lock in " + tryLock + " seconds: " + this);
            }
        } catch (InterruptedException e) {
            throw new ResourceAllocationException("Interrupted attempting lock: " + this);
        }
    }

    void unlock() {
        lock.unlock();
    }

    Session getSession() throws JMSException {
        if (xaResource != null && inManagedTx) {
            return xaSession.getSession();
        } else {
            return session;
        }
    }

    void removeHandle(SessionImpl handle) {
        handles.remove(handle);
    }

    void sendEvent(ConnectionEvent event) {
        int type = event.getId();
        for (ConnectionEventListener l : eventListeners) {
            switch (type) {
                case ConnectionEvent.CONNECTION_CLOSED:
                    l.connectionClosed(event);
                    break;
                case ConnectionEvent.LOCAL_TRANSACTION_STARTED:
                    l.localTransactionStarted(event);
                    break;
                case ConnectionEvent.LOCAL_TRANSACTION_COMMITTED:
                    l.localTransactionCommitted(event);
                    break;
                case ConnectionEvent.LOCAL_TRANSACTION_ROLLEDBACK:
                    l.localTransactionRolledback(event);
                    break;
                case ConnectionEvent.CONNECTION_ERROR_OCCURRED:
                    l.connectionErrorOccurred(event);
                    break;
                default:
                    throw new IllegalArgumentException("Illegal eventType: " + type);
            }
        }
   }

    @Override
    public void addConnectionEventListener(final ConnectionEventListener l) {
        eventListeners.add(l);
    }

    @Override
    public void removeConnectionEventListener(final ConnectionEventListener l) {
        eventListeners.remove(l);
    }

    @Override
    public void setLogWriter(PrintWriter out) throws ResourceException {
    }

    @Override
    public PrintWriter getLogWriter() throws ResourceException {
        return null;
    }

    void start() throws JMSException {
        if (xaConnection != null) {
            xaConnection.start();
        }
        if (connection != null) {
            connection.start();
        }
    }

    void stop() throws JMSException {
        if (xaConnection != null) {
            xaConnection.stop();
        }
        if (connection != null) {
            connection.stop();
        }
    }

    @Override
    public void associateConnection(Object connection) throws ResourceException {
        throw new UnsupportedOperationException();
    }

    private void cleanupHandles() throws ResourceException {
        safe(connection::stop, "Error stopping connection");
        safe(xaConnection::stop, "Error stopping xaConnection");
        doClose(handles, SessionImpl::cleanup);
    }

    /**
     * Destroy the physical xaConnection.
     *
     * @throws ResourceException Could not property close the session and xaConnection.
     */
    @Override
    public void destroy() throws ResourceException {
        if (!isDestroyed.compareAndSet(false, true)) {
            return;
        }

        cleanupHandles();
        try {
            // The following calls should not be necessary
            safe(session::close, "Error closing session");
            safe(xaSession::close, "Error closing session");

            /*
             * (xa|nonXA)Session.close() may NOT be called BEFORE xaConnection.close()
             * <p>
             * If the ClientSessionFactory is trying to fail-over or reconnect with -1 attempts, and
             * one calls session.close() it may effectively dead-lock.
             * <p>
             * xaConnection close will close the ClientSessionFactory which will close all sessions.
             */
            connection.close();
            xaConnection.close();

        } catch (Throwable e) {
            throw new ResourceException("Could not properly close the connection", e);
        }
    }



    @Override
    public void cleanup() throws ResourceException {
        if (isDestroyed.get()) {
            return;
        }

        cleanupHandles();

        inManagedTx = false;

        // I'm recreating the lock object when we return to the pool
        // because it looks too nasty to expect the xaConnection handle
        // to unlock properly in certain race conditions
        // where the dissociation of the managed xaConnection is "random".
        lock = new ReentrantLock();
    }

    @Override
    public Object getConnection(Subject subject, ConnectionRequestInfo cxRequestInfo) throws ResourceException {
        // Check user first
        String userName = credentialExtractor.getUserName();
        CredentialExtractor credential = new CredentialExtractor(subject, cxRequestInfo, mcf);

        // Null users are allowed!
        if (userName != null && !userName.equals(credential.getUserName())) {
            throw new SecurityException("Password credentials not the same, reauthentication not allowed");
        }

        if (userName == null && credential.getUserName() != null) {
            throw new SecurityException("Password credentials not the same, reauthentication not allowed");
        }

        if (isDestroyed.get()) {
            throw new IllegalStateException("The managed xaConnection is already destroyed");
        }

        SessionImpl session = new SessionImpl(this, (ConnectionRequestInfoImpl) cxRequestInfo);
        handles.add(session);
        return session;
    }

    @Override
    public ManagedConnectionMetaData getMetaData() throws ResourceException {
        return null;
    }

    boolean isValid() {
        try {
            session.createMessage();
            xaSession.createMessage();
            connection.getMetaData();
            return true;
        } catch (JMSException e) {
            return false;
        }
    }

    @Override
    public LocalTransaction getLocalTransaction() throws ResourceException {
        return new LocalTransaction() {
            @Override
            public void begin() throws ResourceException {
            }

            @Override
            public void commit() throws ResourceException {
                lock();
                try {
                    if (getSession().getTransacted()) {
                        getSession().commit();
                    }
                } catch (JMSException e) {
                    throw new ResourceException("Could not commit LocalTransaction", e);
                } finally {
                    unlock();
                }
            }

            @Override
            public void rollback() throws ResourceException {
                lock();
                try {
                    if (getSession().getTransacted()) {
                        getSession().rollback();
                    }
                } catch (JMSException ex) {
                    throw new ResourceException("Could not rollback LocalTransaction", ex);
                } finally {
                    unlock();
                }
            }
        };
    }

    @Override
    public XAResource getXAResource() throws ResourceException {
        if (xaResource == null) {
            XAResource xares = xaSession.getXAResource();
            xaResource = new XAResourceProxy(xares);
        }
        return xaResource;
    }

    private class XAResourceProxy implements XAResource {

        private final XAResource xares;

        public XAResourceProxy(XAResource xares) {
            this.xares = xares;
        }

        @Override
        public void start(Xid xid, int flags) throws XAException {
            lock();
            try {
                xares.start(xid, flags);
            } finally {
                setInManagedTx(true);
                unlock();
            }
        }

        @Override
        public void end(Xid xid, int flags) throws XAException {
            lock();
            try {
                xares.end(xid, flags);
            } finally {
                setInManagedTx(false);
                unlock();
            }
        }

        @Override
        public int prepare(Xid xid) throws XAException {
            return xares.prepare(xid);
        }

        @Override
        public void commit(Xid xid, boolean onePhase) throws XAException {
            xares.commit(xid, onePhase);
        }

        @Override
        public void rollback(Xid xid) throws XAException {
            xares.rollback(xid);
        }

        @Override
        public void forget(Xid xid) throws XAException {
            lock();
            try {
                xares.forget(xid);
            } finally {
                setInManagedTx(false);
                unlock();
            }
        }

        @Override
        public boolean isSameRM(XAResource xaResource) throws XAException {
            return xares.isSameRM(xaResource);
        }

        @Override
        public Xid[] recover(int flag) throws XAException {
            return xares.recover(flag);
        }

        @Override
        public int getTransactionTimeout() throws XAException {
            return xares.getTransactionTimeout();
        }

        @Override
        public boolean setTransactionTimeout(int seconds) throws XAException {
            return xares.setTransactionTimeout(seconds);
        }

        private void setInManagedTx(boolean inManagedTx) {
            ManagedConnectionImpl.this.inManagedTx = inManagedTx;
        }

    }

    static private void safe(Utils.RunnableWithException<JMSException> cb, String msg) {
        try {
            cb.run();
        } catch (JMSException e) {
            trace(msg, e);
        }
    }

}
