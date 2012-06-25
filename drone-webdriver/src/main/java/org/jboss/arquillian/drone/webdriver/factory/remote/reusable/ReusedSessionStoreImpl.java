/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.drone.webdriver.factory.remote.reusable;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Storage for ReusedSession. It allows to work with sessions stored with different versions of Drones in a single place.
 *
 *
 *
 * @author <a href="mailto:lryc@redhat.com">Lukas Fryc</a>
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 */
public class ReusedSessionStoreImpl implements ReusedSessionStore {
    private static final Logger log = Logger.getLogger(ReusedSessionStoreImpl.class.getName());

    private static final long serialVersionUID = 914857799370645455L;

    // session is valid for two days
    private static final int SESSION_VALID_IN_SECONDS = 3600 * 48;

    // represents a "raw" list of reused sessions, storing sessions with timeout information
    private Map<ByteArray, LinkedList<ByteArray>> rawStore;

    // represents a list of sessions available at the moment
    // this list is not serialized, however it is constructed for available data in the storage
    private transient Map<InitializationParameter, LinkedList<RawDisposableReusedSession>> store;

    public ReusedSessionStoreImpl() {
        this.rawStore = new HashMap<ByteArray, LinkedList<ByteArray>>();
        this.store = new HashMap<InitializationParameter, LinkedList<RawDisposableReusedSession>>();
    }

    @Override
    public ReusedSession pull(InitializationParameter key) {
        synchronized (rawStore) {
            LinkedList<RawDisposableReusedSession> list = store.get(key);
            if (list == null || list.isEmpty()) {
                return null;
            }

            // get session and remove it from raw data
            RawDisposableReusedSession disposableSession = list.removeLast();
            disposableSession.dispose();

            return disposableSession.getSession();
        }
    }

    @Override
    public void store(InitializationParameter key, ReusedSession session) {
        synchronized (rawStore) {
            // update map of raw data
            ByteArray rawKey = ByteArray.fromObject(key);
            if (rawKey == null) {
                log.severe("Unable to store browser initialization parameter in ReusedSessionStore for browser: "
                        + key.getDesiredCapabilities().getBrowserName());
                return;
            }

            LinkedList<ByteArray> rawList = rawStore.get(rawKey);
            if (rawList == null) {
                rawList = new LinkedList<ByteArray>();
                rawStore.put(rawKey, rawList);
            }
            ByteArray rawSession = ByteArray.fromObject(session);
            if (rawSession == null) {
                log.severe("Unable to store browser session in ReusedSessionStore for browser: "
                        + key.getDesiredCapabilities().getBrowserName());
                return;
            }
            // add a timestamp to the session and store in the list
            TimeStampedSession timeStampedSession = new TimeStampedSession(rawSession);
            rawList.add(ByteArray.fromObject(timeStampedSession));

            // regenerate view so session view will have the same content as raw one
            regenerateView();
        }
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        // read object content
        ois.defaultReadObject();
        // regenerate view
        regenerateView();
    }

    private void regenerateView() {
        // construct a view of valid ReusedSessions for current version of Drone
        Map<InitializationParameter, LinkedList<RawDisposableReusedSession>> newStore = new HashMap<InitializationParameter, LinkedList<RawDisposableReusedSession>>();
        for (Map.Entry<ByteArray, LinkedList<ByteArray>> entry : rawStore.entrySet()) {
            InitializationParameter initializationParam = entry.getKey().as(InitializationParameter.class);

            // if not null, check what reused sessions are inside
            if (initializationParam != null) {
                LinkedList<RawDisposableReusedSession> sessions = new LinkedList<RawDisposableReusedSession>();
                LinkedList<ByteArray> rawSessions = entry.getValue();
                if (rawSessions != null) {
                    Iterator<ByteArray> byteArrayIterator = entry.getValue().iterator();
                    while (byteArrayIterator.hasNext()) {
                        TimeStampedSession session = byteArrayIterator.next().as(TimeStampedSession.class);
                        // add session if valid and it can be deserialized
                        ReusedSession reusedSession = session.getSession();
                        if (session.isValid(SESSION_VALID_IN_SECONDS) && reusedSession != null) {
                            sessions.add(new RawDisposableReusedSession(session.getRawSession(), rawSessions, reusedSession));
                        }
                        // remove completely if session is not valid
                        else if (!session.isValid(SESSION_VALID_IN_SECONDS)) {
                            byteArrayIterator.remove();
                        }
                    }
                    // add a queue of sessions into available sessions list
                    newStore.put(initializationParam, sessions);
                }
            }
        }
        this.store = newStore;
    }

    /**
     * Wrapper for array of bytes to act as a key/value in a map. This abstraction allows as to have stored sessions for Drones
     * with incompatible serialVersionUID, for instance Drones based on different Selenium version.
     *
     * This implementation ignores invalid content, it simply returns null when a object cannot be deserialized
     *
     * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
     *
     */
    private static class ByteArray implements Serializable {
        private static final long serialVersionUID = 1L;

        private byte[] raw = new byte[0];

        static ByteArray fromObject(Serializable object) {
            ByteArray bytes = new ByteArray();
            try {
                bytes.raw = SerializationUtils.serializeToBytes(object);
                return bytes;
            } catch (IOException e) {
                log.warning("Unable to serializable object of " + object.getClass() + " due to " + e.getMessage());
            }
            return null;
        }

        <T extends Serializable> T as(Class<T> classType) {
            try {
                return SerializationUtils.deserializeFromBytes(classType, raw);
            } catch (ClassNotFoundException e) {
                log.warning("Unable to deserialize object of " + classType.getName() + " due to " + e.getMessage());
            } catch (IOException e) {
                log.warning("Unable to deserialize object of " + classType.getName() + " due to " + e.getMessage());
            }

            return null;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(raw);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ByteArray other = (ByteArray) obj;
            if (!Arrays.equals(raw, other.raw))
                return false;
            return true;
        }

    }

    /**
     * Wrapper for ReusedSession. This session is stored in binary format including a timestamp.
     *
     * This allows implementation to invalidate a session without actually trying to deserialize it.
     *
     * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
     *
     */
    private static class TimeStampedSession implements Serializable {
        private static final long serialVersionUID = 1L;

        private Date timestamp;

        private ByteArray rawSession;

        public TimeStampedSession(ByteArray rawSession) {
            this.timestamp = new Date();
            this.rawSession = rawSession;
        }

        public boolean isValid(int timeoutInSeconds) {

            Date now = new Date();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(timestamp);
            calendar.add(Calendar.SECOND, timeoutInSeconds);

            return calendar.getTime().after(now);
        }

        public ReusedSession getSession() {
            return rawSession.as(ReusedSession.class);
        }

        public ByteArray getRawSession() {
            return rawSession;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((rawSession == null) ? 0 : rawSession.hashCode());
            result = prime * result + ((timestamp == null) ? 0 : timestamp.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TimeStampedSession other = (TimeStampedSession) obj;
            if (rawSession == null) {
                if (other.rawSession != null)
                    return false;
            } else if (!rawSession.equals(other.rawSession))
                return false;
            if (timestamp == null) {
                if (other.timestamp != null)
                    return false;
            } else if (!timestamp.equals(other.timestamp))
                return false;
            return true;
        }
    }

    /**
     * Wrapper for reusable session with ability to dispose data from rawStore.
     *
     * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
     *
     */
    private static class RawDisposableReusedSession {
        private final ByteArray key;
        private final ReusedSession session;
        private final LinkedList<ByteArray> parentList;

        public RawDisposableReusedSession(ByteArray key, LinkedList<ByteArray> parentList, ReusedSession session) {
            this.key = key;
            this.parentList = parentList;
            this.session = session;
        }

        /**
         * Removes current session from queue of relevant raw data
         */
        public void dispose() {
            synchronized (parentList) {
                Iterator<ByteArray> iterator = parentList.iterator();
                while (iterator.hasNext()) {
                    if (key.equals(iterator.next())) {
                        iterator.remove();
                    }
                }
            }
        }

        public ReusedSession getSession() {
            return session;
        }
    }
}
