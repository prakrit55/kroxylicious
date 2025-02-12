/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.kroxylicious.proxy.future;

/**
 *  A generic event handler.
 *  <p>
 *  This interface is used heavily throughout Vert.x as a handler for all types of asynchronous occurrences.
 *  <p>
 *
 *  @author <a href="http://tfox.org">Tim Fox</a>
 */
@FunctionalInterface
public interface Handler<E> {

    /**
     * Something has happened, so handle it.
     *
     * @param event  the event to handle
     */
    void handle(E event);
}
