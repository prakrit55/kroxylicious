/*
 * Copyright (c) 2011-2020 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.kroxylicious.proxy.internal.future;

import java.util.function.Function;

import io.kroxylicious.proxy.future.Future;

/**
 * Function compose transformation.
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class Composition<T, U> extends Operation<U> implements Listener<T> {

    private final Function<T, Future<U>> successMapper;
    private final Function<Throwable, Future<U>> failureMapper;

    Composition(Function<T, Future<U>> successMapper, Function<Throwable, Future<U>> failureMapper) {
        super();
        this.successMapper = successMapper;
        this.failureMapper = failureMapper;
    }

    @Override
    public void onSuccess(T value) {
        FutureInternal<U> future;
        try {
            future = (FutureInternal<U>) successMapper.apply(value);
        }
        catch (Throwable e) {
            tryFail(e);
            return;
        }
        future.addListener(newListener());
    }

    @Override
    public void onFailure(Throwable failure) {
        FutureInternal<U> future;
        try {
            future = (FutureInternal<U>) failureMapper.apply(failure);
        }
        catch (Throwable e) {
            tryFail(e);
            return;
        }
        future.addListener(newListener());
    }

    private Listener<U> newListener() {
        return new Listener<U>() {
            @Override
            public void onSuccess(U value) {
                tryComplete(value);
            }

            @Override
            public void onFailure(Throwable failure) {
                tryFail(failure);
            }
        };
    }
}
