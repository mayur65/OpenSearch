/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.datafeed;

import org.elasticsearch.test.ESTestCase;

public class DatafeedStatusTests extends ESTestCase {

    public void testForString() {
        assertEquals(DatafeedState.fromString("started"), DatafeedState.STARTED);
        assertEquals(DatafeedState.fromString("stopped"), DatafeedState.STOPPED);
    }

    public void testValidOrdinals() {
        assertEquals(0, DatafeedState.STARTED.ordinal());
        assertEquals(1, DatafeedState.STOPPED.ordinal());
    }

}
