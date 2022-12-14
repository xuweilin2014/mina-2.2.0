/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.core.write;

import java.util.Collection;

/**
 * An exception thrown whe a write is rejected
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class WriteRejectedException extends WriteException {
    private static final long serialVersionUID = 6272160412793858438L;

    /**
     * Create a new WriteRejectedException instance
     * 
     * @param requests The {@link WriteRequest} which has been rejected
     * @param message  The error message
     */
    public WriteRejectedException(WriteRequest requests, String message) {
        super(requests, message);
    }

    /**
     * Create a new WriteRejectedException instance
     * 
     * @param requests The {@link WriteRequest} which has been rejected
     */
    public WriteRejectedException(WriteRequest requests) {
        super(requests);
    }

    /**
     * Create a new WriteRejectedException instance
     * 
     * @param requests The {@link WriteRequest} which has been rejected
     */
    public WriteRejectedException(Collection<WriteRequest> requests) {
        super(requests);
    }
    
    /**
     * Create a new WriteRejectedException instance
     * 
     * @param requests The {@link WriteRequest} which has been rejected
     * @param message  The error message
     */
    public WriteRejectedException(Collection<WriteRequest> requests, String message) {
        super(requests, message);
    }
}
