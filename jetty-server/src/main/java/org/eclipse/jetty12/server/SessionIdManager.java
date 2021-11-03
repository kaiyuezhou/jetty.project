//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty12.server;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty12.server.session.HouseKeeper;

/**
 * Session ID Manager.
 *
 * Manages session IDs across multiple contexts.
 */
public interface SessionIdManager extends LifeCycle
{

    /**
     * @param id The plain session ID (ie no workername extension)
     * @return True if the session ID is in use by at least one context.
     */
    public boolean isIdInUse(String id);

    /**
     * Expire all sessions on all contexts that share the same id.
     *
     * @param id The session ID without any cluster node extension
     */
    public void expireAll(String id);

    /**
     * Invalidate all sessions on all contexts that share the same id.
     *
     * @param id the session id
     */
    public void invalidateAll(String id);

    /**
     * Create a new Session ID.
     *
     * @param request the request with the sesion
     * @param created the timestamp for when the session was created
     * @return the new session id
     */
    public String newSessionId(Request request, long created);

    /**
     * @return the unique name of this server instance
     */
    public String getWorkerName();

    /**
     * Get just the session id from an id that includes the worker name
     * as a suffix.
     *
     * Strip node identifier from a located session ID.
     *
     * @param qualifiedId the session id including the worker name
     * @return the cluster id
     */
    public String getId(String qualifiedId);

    /**
     * Get an extended id for a session. An extended id contains
     * the workername as a suffix.
     *
     * @param id The id of the session
     * @param request The request that for the session (or null)
     * @return The session id qualified with the worker name
     */
    public String getExtendedId(String id, Request request);

    /**
     * Change the existing session id.
     *
     * @param oldId the old plain session id
     * @param oldExtendedId the old fully qualified id
     * @param request the request containing the session
     * @return the new session id
     */
    public String renewSessionId(String oldId, String oldExtendedId, Request request);
    
    public void scavenge();

    /**
     * @param houseKeeper the housekeeper for doing scavenging
     */
    public void setSessionHouseKeeper(HouseKeeper houseKeeper);

    /**
     * @return the housekeeper for doing scavenging
     */
    public HouseKeeper getSessionHouseKeeper();
}
